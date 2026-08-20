package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The GLOBAL sky as a coarse gauge grid (2026-07-17): Open-Meteo's keyless forecast API sampled on a fixed
 * {@value #STEP_DEG}° lat/lon raster — current cloud cover, precipitation and
 * temperature per cell, enough to paint rain fields, cloud decks and heat
 * domes as coarse washes. Same bulk mechanism as
 * {@link WorldWeatherClient} (comma-separated coordinate lists, one JSON
 * array back), batched to stay polite.
 *
 * <p>Budget arithmetic: ~{@code 13×36 = 468} cells per refresh, a
 * {@value #CACHE_TTL_MINUTES}-minute cache — well inside Open-Meteo's free
 * tier. A failed batch costs its cells, never the grid.
 */
@Singleton
public class GlobalWeatherGridClient {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalWeatherGridClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BASE = "https://api.open-meteo.com/v1/forecast";
    private static final int STEP_DEG = 10;
    private static final int LAT_MIN = -50;
    private static final int LAT_MAX = 70;
    private static final int BATCH = 120;
    private static final long CACHE_TTL_MINUTES = 90;

    /** One grid cell's current sky. */
    public record Cell(double lat, double lon, Double cloudCoverPercent,
            Double precipitationMm, Double temperatureC) {
    }

    private record Cached(Instant at, List<Cell> cells) {
    }

    /** Direct-first (joker mandate 2026-07-14); the browser is the rescue. */
    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);
    private volatile Cached cached;

    @Inject
    public GlobalWeatherGridClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The current grid; stale kept on failure, empty only before the first success. */
    public List<Cell> grid() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(Duration.ofMinutes(CACHE_TTL_MINUTES)))) {
            return hit.cells();
        }
        List<double[]> raster = raster();
        List<Cell> cells = new ArrayList<>(raster.size());
        for (int from = 0; from < raster.size(); from += BATCH) {
            List<double[]> batch = raster.subList(from, Math.min(raster.size(), from + BATCH));
            try {
                cells.addAll(fetchBatch(batch));
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.debug("[WXGRID] batch {} failed: {}", from / BATCH, e.getMessage());
            }
        }
        if (!cells.isEmpty()) {
            cached = new Cached(Instant.now(), List.copyOf(cells));
            return cached.cells();
        }
        return hit == null ? List.of() : hit.cells();
    }

    private static List<double[]> raster() {
        List<double[]> out = new ArrayList<>();
        for (int lat = LAT_MIN; lat <= LAT_MAX; lat += STEP_DEG) {
            for (int lon = -180; lon < 180; lon += STEP_DEG) {
                out.add(new double[]{lat, lon});
            }
        }
        return out;
    }

    private List<Cell> fetchBatch(List<double[]> batch) throws Exception {
        StringJoiner lats = new StringJoiner(",");
        StringJoiner lons = new StringJoiner(",");
        for (double[] p : batch) {
            lats.add(String.valueOf((int) p[0]));
            lons.add(String.valueOf((int) p[1]));
        }
        String url = BASE + "?latitude=" + lats + "&longitude=" + lons
                + "&current=temperature_2m,precipitation,cloud_cover";
        WebResponse resp = fetcher.fetch(url,
                Map.of("Accept", "application/json"), requestTimeout, MODES);
        if (resp == null || resp.status() != 200) {
            LOG.debug("[WXGRID] answered status {}", resp == null ? "null" : resp.status());
            return List.of();
        }
        return parse(resp.body(), batch);
    }

    /** Package-visible for the fixture test. One answer object per coordinate, in order. */
    List<Cell> parse(String body, List<double[]> batch) throws Exception {
        JsonNode root = JSON.readTree(body);
        List<JsonNode> answers = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(answers::add);
        } else if (root.isObject()) {
            answers.add(root); // a one-cell batch answers a bare object
        }
        List<Cell> out = new ArrayList<>();
        for (int i = 0; i < answers.size() && i < batch.size(); i++) {
            JsonNode cur = answers.get(i).path("current");
            if (cur.isMissingNode()) continue;
            out.add(new Cell(batch.get(i)[0], batch.get(i)[1],
                    numOrNull(cur, "cloud_cover"),
                    numOrNull(cur, "precipitation"),
                    numOrNull(cur, "temperature_2m")));
        }
        return out;
    }

    private static Double numOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.asDouble() : null;
    }
}
