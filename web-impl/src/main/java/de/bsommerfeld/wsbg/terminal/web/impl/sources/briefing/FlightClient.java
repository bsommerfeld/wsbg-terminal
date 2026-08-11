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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live aircraft from the ADS-B receiver network (researched 2026-07-17,
 * live-probed 2026-08-03, keyless): {@code api.adsb.lol} answers every
 * transponder its volunteers hear inside a radius. There is no global keyless
 * feed - the API is REGIONAL by construction, which is exactly right here.
 *
 * <p>The house does not scatter aircraft over the planet for decoration. It
 * asks around the airports its OWN hazard leg is already reporting as
 * disrupted: when the FAA says "ground delay at SFO", this layer shows what
 * that actually looks like from above. A calm sky means no query and an empty
 * layer - silence is the statement, as everywhere else in the house.
 */
@Singleton
public class FlightClient {

    private static final Logger LOG = LoggerFactory.getLogger(FlightClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BASE = "https://api.adsb.lol/v2/lat/%s/lon/%s/dist/%d";
    /** The network's own refresh is ~1 Hz; we are drawing, not controlling traffic. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(4);
    /** Nautical miles - the API's own unit, and its practical ceiling. */
    private static final int RADIUS_NM = 120;
    /** Enough to read a stack, not enough to bury a picture. */
    private static final int MAX_PER_FIELD = 60;

    /**
     * One aircraft: {@code callsign} as broadcast, {@code altitudeFt} null while
     * it is on the ground, {@code groundSpeedKt} its speed over the earth.
     */
    public record Aircraft(String callsign, String type, double lat, double lon,
            Integer altitudeFt, Double groundSpeedKt, Double trackDeg, String nearField) {
    }

    private record Cached(Instant at, List<Aircraft> aircraft) {
    }

    /** Direct-first (joker mandate 2026-07-14); the browser is the rescue. */
    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(15);
    private final Map<String, Cached> perField = new LinkedHashMap<>();

    @Inject
    public FlightClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The aircraft currently around one field. {@code label} only names the
     * result (it never selects it) - the position does the asking.
     */
    public List<Aircraft> around(double lat, double lon, String label) {
        String key = Math.round(lat * 10) + "," + Math.round(lon * 10);
        Cached hit;
        synchronized (perField) {
            hit = perField.get(key);
        }
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.aircraft();
        }
        try {
            String url = String.format(java.util.Locale.ROOT, BASE, trim(lat), trim(lon), RADIUS_NM);
            WebResponse resp = fetcher.fetch(url,
                    Map.of("Accept", "application/json"),
                    requestTimeout, MODES);
            if (resp != null && resp.status() == 200) {
                List<Aircraft> parsed = parse(resp.body(), label);
                synchronized (perField) {
                    perField.put(key, new Cached(Instant.now(), parsed));
                }
                return parsed;
            }
            LOG.debug("[ADSB] answered status {}", resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[ADSB] fetch failed: {}", e.getMessage());
        }
        return hit == null ? List.of() : hit.aircraft();
    }

    /** Package-visible for the fixture test. */
    List<Aircraft> parse(String body, String label) {
        try {
            JsonNode list = JSON.readTree(body).path("ac");
            if (!list.isArray()) return List.of();
            List<Aircraft> out = new ArrayList<>();
            for (JsonNode a : list) {
                if (!a.path("lat").isNumber() || !a.path("lon").isNumber()) continue;
                out.add(new Aircraft(
                        callsign(a), textOrNull(a, "t"),
                        a.path("lat").asDouble(), a.path("lon").asDouble(),
                        // "ground" instead of a number is the honest on-stand answer.
                        a.path("alt_baro").isNumber() ? a.path("alt_baro").asInt() : null,
                        a.path("gs").isNumber() ? a.path("gs").asDouble() : null,
                        a.path("track").isNumber() ? a.path("track").asDouble() : null,
                        label));
                if (out.size() >= MAX_PER_FIELD) break;
            }
            return List.copyOf(out);
        } catch (Exception e) {
            LOG.debug("[ADSB] parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Callsigns arrive padded to eight characters. */
    private static String callsign(JsonNode a) {
        String raw = textOrNull(a, "flight");
        if (raw != null && !raw.isBlank()) return raw.strip();
        String reg = textOrNull(a, "r");
        return reg == null ? null : reg.strip();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    private static String trim(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }
}
