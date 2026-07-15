package de.bsommerfeld.wsbg.terminal.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * NOAA SWPC space-weather scales (live-probed 2026-07-14, keyless):
 * {@code services.swpc.noaa.gov/products/noaa-scales.json}. Object keyed by
 * relative day: {@code "0"} = the current observed R/S/G scales, {@code "1"}–
 * {@code "3"} = the forecast days ({@code "1"} is the remainder of today —
 * its DateStamp equals today's), {@code "-1"} = yesterday (ignored). Scale
 * shape pinned by fixture: observed entries carry {@code Scale}/{@code Text},
 * forecast entries carry {@code Scale} only for G (geomagnetic storms are
 * forecast as a level) while R and S forecast as event PROBABILITIES
 * ({@code MinorProb}/{@code MajorProb}/{@code Prob}, percent). A G3+ forecast
 * is the satellite/grid story for the Gewitterwarnung tile.
 */
@Singleton
public class SpaceWeatherClient {

    private static final Logger LOG = LoggerFactory.getLogger(SpaceWeatherClient.class);

    private static final String URL = "https://services.swpc.noaa.gov/products/noaa-scales.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Observed scales refresh every few minutes; 1h politeness cache is plenty. */
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    /**
     * The current scales plus the 3-day forecast maxima. {@code r}/{@code s}/
     * {@code g} are today's observed NOAA scale levels 0–5 ({@code -1}
     * unknown); {@code forecastMaxG} is the highest G level forecast over the
     * next three entries; the probability fields are the highest forecast
     * event probabilities in percent 0–100 ({@code -1} unknown) — R/S are
     * forecast as probabilities, not levels.
     */
    public record SpaceScales(String dateIso, int r, int s, int g, int forecastMaxG,
            int forecastMaxRMinorProb, int forecastMaxRMajorProb, int forecastMaxSProb) {
    }

    private record Cached(Instant at, SpaceScales scales) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(10);
    private volatile Cached cached;

    /** Test/default: plain direct transport. */
    public SpaceWeatherClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public SpaceWeatherClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The current scales + forecast maxima; empty on any failure (stale kept). */
    public Optional<SpaceScales> latest() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return Optional.of(hit.scales());
        }
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                Optional<SpaceScales> parsed = parse(resp.body());
                parsed.ifPresent(s -> cached = new Cached(Instant.now(), s));
                if (parsed.isPresent()) return parsed;
            } else {
                LOG.debug("[SWPC] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[SWPC] fetch failed: {}", e.getMessage());
        }
        return hit != null ? Optional.of(hit.scales()) : Optional.empty();
    }

    /** Package-private for tests: scales JSON → record, network-free. */
    static Optional<SpaceScales> parse(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode today = root.get("0");
            if (today == null || today.isNull()) return Optional.empty();
            String dateIso = today.path("DateStamp").asText("");
            int r = scaleOf(today.path("R"));
            int s = scaleOf(today.path("S"));
            int g = scaleOf(today.path("G"));
            int maxG = -1, maxRMinor = -1, maxRMajor = -1, maxSProb = -1;
            for (String key : new String[] {"1", "2", "3"}) {
                JsonNode day = root.get(key);
                if (day == null || day.isNull()) continue;
                maxG = Math.max(maxG, scaleOf(day.path("G")));
                maxRMinor = Math.max(maxRMinor, intField(day.path("R"), "MinorProb"));
                maxRMajor = Math.max(maxRMajor, intField(day.path("R"), "MajorProb"));
                maxSProb = Math.max(maxSProb, intField(day.path("S"), "Prob"));
            }
            if (dateIso.isEmpty() && r < 0 && s < 0 && g < 0) return Optional.empty();
            return Optional.of(new SpaceScales(dateIso, r, s, g, maxG,
                    maxRMinor, maxRMajor, maxSProb));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Scales arrive as STRING numbers ("0"–"5") or null. */
    private static int scaleOf(JsonNode scaleNode) {
        return intField(scaleNode, "Scale");
    }

    private static int intField(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return -1;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return -1;
        try {
            return Integer.parseInt(v.asText().trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
