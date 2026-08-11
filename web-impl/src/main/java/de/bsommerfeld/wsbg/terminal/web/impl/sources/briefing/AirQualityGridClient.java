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
 * The air the world is breathing (live-probed 2026-08-02, keyless): Open-Meteo's
 * air-quality API, sampled on the same {@value #STEP_DEG}° raster as
 * {@link GlobalWeatherGridClient} and read the same way (comma-separated
 * coordinates, one array back). Fine particulates are where wildfire smoke,
 * dust storms and industrial haze become visible from orbit - and where they
 * become economic: factory shutdowns, grounded flights, closed schools.
 *
 * <p>Only cells that are actually dirty ride ({@code pm2_5 >=}
 * {@value #MIN_PM25} µg/m³, the WHO daily guideline): clean air is the absence
 * of a marker, not thousands of invisible ones.
 */
@Singleton
public class AirQualityGridClient {

    private static final Logger LOG = LoggerFactory.getLogger(AirQualityGridClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BASE = "https://air-quality-api.open-meteo.com/v1/air-quality";
    private static final int STEP_DEG = 10;
    private static final int LAT_MIN = -50;
    private static final int LAT_MAX = 70;
    private static final int BATCH = 120;
    private static final long CACHE_TTL_MINUTES = 120;
    /** The WHO 24-hour guideline - below it a cell is simply clean. */
    private static final double MIN_PM25 = 15.0;

    /** Direct-first (joker mandate 2026-07-14): the keyless API is wall-less. */
    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    /** One dirty cell: position plus fine particulate load and the US AQI band. */
    public record AirCell(double lat, double lon, double pm25, Integer usAqi) {
    }

    private record Cached(Instant at, List<AirCell> cells) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);
    private volatile Cached cached;

    @Inject
    public AirQualityGridClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The dirty cells; stale kept on failure. */
    public List<AirCell> grid() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(Duration.ofMinutes(CACHE_TTL_MINUTES)))) {
            return hit.cells();
        }
        List<double[]> raster = raster();
        List<AirCell> cells = new ArrayList<>();
        boolean answered = false;
        for (int from = 0; from < raster.size(); from += BATCH) {
            List<double[]> batch = raster.subList(from, Math.min(raster.size(), from + BATCH));
            try {
                List<AirCell> part = fetchBatch(batch);
                answered |= part != null;
                if (part != null) cells.addAll(part);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.debug("[AIRQ] batch {} failed: {}", from / BATCH, e.getMessage());
            }
        }
        // A world that is simply clean answers with an EMPTY list - that is a
        // valid picture and gets cached, or every collect would refetch it.
        if (answered) {
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

    /** Null means the batch did not answer at all (as opposed to "clean"). */
    private List<AirCell> fetchBatch(List<double[]> batch) throws Exception {
        StringJoiner lats = new StringJoiner(",");
        StringJoiner lons = new StringJoiner(",");
        for (double[] p : batch) {
            lats.add(String.valueOf((int) p[0]));
            lons.add(String.valueOf((int) p[1]));
        }
        String url = BASE + "?latitude=" + lats + "&longitude=" + lons + "&current=pm2_5,us_aqi";
        WebResponse resp = fetcher.fetch(url,
                Map.of("Accept", "application/json"), requestTimeout, MODES);
        if (resp == null || resp.status() != 200) {
            LOG.debug("[AIRQ] answered status {}", resp == null ? "null" : resp.status());
            return null;
        }
        return parse(resp.body(), batch);
    }

    /** Package-visible for the fixture test. One answer object per coordinate, in order. */
    List<AirCell> parse(String body, List<double[]> batch) throws Exception {
        JsonNode root = JSON.readTree(body);
        List<JsonNode> answers = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(answers::add);
        } else if (root.isObject()) {
            answers.add(root); // a one-cell batch answers a bare object
        }
        List<AirCell> out = new ArrayList<>();
        for (int i = 0; i < answers.size() && i < batch.size(); i++) {
            JsonNode cur = answers.get(i).path("current");
            JsonNode pm = cur.path("pm2_5");
            if (!pm.isNumber() || pm.asDouble() < MIN_PM25) continue;
            JsonNode aqi = cur.path("us_aqi");
            out.add(new AirCell(batch.get(i)[0], batch.get(i)[1], pm.asDouble(),
                    aqi.isNumber() ? aqi.asInt() : null));
        }
        return out;
    }
}
