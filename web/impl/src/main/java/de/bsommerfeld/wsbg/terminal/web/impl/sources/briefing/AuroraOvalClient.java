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
import java.util.Optional;

/**
 * NOAA SWPC OVATION aurora forecast (researched 2026-07-17, keyless):
 * {@code services.swpc.noaa.gov/json/ovation_aurora_latest.json} — a
 * 360×181 lon/lat grid of aurora probabilities (percent), refreshed every
 * five minutes. This is the DRAWABLE geometry of a geomagnetic storm: the
 * oval around the poles, ready for any map or figure that wants it.
 *
 * <p>The full grid is ~65k cells; the client thins it to the cells that
 * actually glow ({@code >= }{@value #MIN_PROBABILITY}%) on a
 * {@value #LON_STRIDE}°/{@value #LAT_STRIDE}° stride — a few hundred points
 * on a quiet day, low thousands in a storm, sized for one websocket payload.
 */
@Singleton
public class AuroraOvalClient {

    private static final Logger LOG = LoggerFactory.getLogger(AuroraOvalClient.class);

    private static final String URL =
            "https://services.swpc.noaa.gov/json/ovation_aurora_latest.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Source refreshes every 5 min; the cache keeps back-to-back collects free. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    /** Below this probability a cell is dark - not worth a point. */
    private static final int MIN_PROBABILITY = 10;
    private static final int LON_STRIDE = 3;
    private static final int LAT_STRIDE = 2;

    /** Direct-first (joker mandate 2026-07-14): NOAA's JSON services are wall-less. */
    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    /**
     * One thinned forecast: {@code points} as {@code [lonEast(-180..180), lat,
     * probabilityPercent]} triples, ready to be drawn.
     */
    public record AuroraOval(String forecastTime, List<double[]> points) {
    }

    private record Cached(Instant at, AuroraOval oval) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);
    private volatile Cached cached;

    @Inject
    public AuroraOvalClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The thinned current oval; empty on any failure (stale kept while it lasts). */
    public Optional<AuroraOval> latest() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return Optional.of(hit.oval());
        }
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("Accept", "application/json"),
                    requestTimeout, MODES);
            if (resp != null && resp.status() == 200) {
                Optional<AuroraOval> parsed = parse(resp.body());
                parsed.ifPresent(o -> cached = new Cached(Instant.now(), o));
                if (parsed.isPresent()) return parsed;
            } else {
                LOG.debug("[OVATION] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[OVATION] fetch failed: {}", e.getMessage());
        }
        return hit == null ? Optional.empty() : Optional.of(hit.oval());
    }

    /** Package-visible for the fixture test. */
    Optional<AuroraOval> parse(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode grid = root.path("coordinates");
            if (!grid.isArray()) return Optional.empty();
            List<double[]> points = new ArrayList<>();
            for (JsonNode cell : grid) {
                if (!cell.isArray() || cell.size() < 3) continue;
                int lon = cell.get(0).asInt();
                int lat = cell.get(1).asInt();
                int prob = cell.get(2).asInt();
                if (prob < MIN_PROBABILITY) continue;
                if (Math.floorMod(lon, LON_STRIDE) != 0 || Math.floorMod(lat, LAT_STRIDE) != 0) {
                    continue;
                }
                // Source longitudes run 0..359 east; the map speaks -180..180.
                double lonEast = lon > 180 ? lon - 360 : lon;
                points.add(new double[]{lonEast, lat, prob});
            }
            // An all-quiet sky is a VALID answer (empty points) — cached like
            // any other, so calm days don't refetch on every collect.
            return Optional.of(new AuroraOval(
                    root.path("Forecast Time").asText(null), List.copyOf(points)));
        } catch (Exception e) {
            LOG.debug("[OVATION] parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
