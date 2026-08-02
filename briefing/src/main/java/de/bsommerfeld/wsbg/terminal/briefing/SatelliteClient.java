package de.bsommerfeld.wsbg.terminal.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CelesTrak orbital elements (researched 2026-07-17, live-probed 2026-08-03,
 * keyless): the public catalogue of what is in orbit, as mean Keplerian
 * elements per object.
 *
 * <p>⚠️ CelesTrak blocks IP addresses that fetch repeatedly - the elements are
 * NOT a live feed and must not be polled like one. They are also barely worth
 * polling: mean elements change over days. Hence a
 * {@value #CACHE_TTL_HOURS}-hour cache, one crewed-station group, and the
 * MOVEMENT computed on the page from the elements rather than fetched. The
 * page's own propagation is what makes the dots travel; this client only ever
 * hands it the starting conditions.
 */
@Singleton
public class SatelliteClient {

    private static final Logger LOG = LoggerFactory.getLogger(SatelliteClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Crewed stations and their attached craft - few objects, all recognisable. */
    private static final String URL =
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=json";
    private static final long CACHE_TTL_HOURS = 12;
    private static final int MAX_OBJECTS = 30;

    /**
     * One object's mean orbital elements at its epoch - exactly the numbers a
     * Keplerian propagator needs, passed through verbatim so the page can do
     * the arithmetic. Angles in degrees, {@code meanMotion} in revolutions per
     * day, {@code epochIso} the instant the elements describe.
     */
    public record OrbitalElements(String name, int noradId, String epochIso,
            double meanMotion, double eccentricity, double inclination,
            double raan, double argOfPericenter, double meanAnomaly) {
    }

    private record Cached(Instant at, List<OrbitalElements> objects) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(20);
    private volatile Cached cached;

    /** Test/default: plain direct transport. */
    public SatelliteClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam (joker mandate 2026-07-14). */
    @Inject
    public SatelliteClient(@DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The catalogue; stale kept indefinitely on failure - old elements beat none. */
    public List<OrbitalElements> objects() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(Duration.ofHours(CACHE_TTL_HOURS)))) {
            return hit.objects();
        }
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<OrbitalElements> parsed = parse(resp.body());
                if (!parsed.isEmpty()) {
                    cached = new Cached(Instant.now(), parsed);
                    return parsed;
                }
            } else {
                LOG.debug("[CELESTRAK] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[CELESTRAK] fetch failed: {}", e.getMessage());
        }
        return hit == null ? List.of() : hit.objects();
    }

    /** Package-visible for the fixture test. */
    List<OrbitalElements> parse(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isArray()) return List.of();
            List<OrbitalElements> out = new ArrayList<>();
            for (JsonNode o : root) {
                if (!o.path("MEAN_MOTION").isNumber()) continue;
                out.add(new OrbitalElements(
                        o.path("OBJECT_NAME").asText("?").strip(),
                        o.path("NORAD_CAT_ID").asInt(0),
                        o.path("EPOCH").asText(null),
                        o.path("MEAN_MOTION").asDouble(),
                        o.path("ECCENTRICITY").asDouble(0),
                        o.path("INCLINATION").asDouble(0),
                        o.path("RA_OF_ASC_NODE").asDouble(0),
                        o.path("ARG_OF_PERICENTER").asDouble(0),
                        o.path("MEAN_ANOMALY").asDouble(0)));
                if (out.size() >= MAX_OBJECTS) break;
            }
            return List.copyOf(out);
        } catch (Exception e) {
            LOG.debug("[CELESTRAK] parse failed: {}", e.getMessage());
            return List.of();
        }
    }
}
