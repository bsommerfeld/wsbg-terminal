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
import java.util.Map;
import java.util.Optional;

/**
 * Where the ISS is right now (live-probed 2026-08-02, keyless):
 * {@code api.wheretheiss.at} answers the station's current sub-satellite point,
 * altitude, ground speed and whether it is flying through daylight or shadow.
 *
 * <p>The most literal live fact there is - something actually up there, moving,
 * right now. The station covers roughly 2,300 km in five
 * minutes, so the position rides with its OWN timestamp: a stale fix that
 * pretends to be live would be a lie, a dated one is a fact.
 */
@Singleton
public class OrbitClient {

    private static final Logger LOG = LoggerFactory.getLogger(OrbitClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 25544 is the ISS (Zarya) NORAD catalogue number. */
    private static final String URL = "https://api.wheretheiss.at/v1/satellites/25544";
    /** It moves 7.7 km a second - a cached position is worth very little. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(45);

    /**
     * The station's current position: {@code atEpochSeconds} is the source's
     * own fix time, {@code footprintKm} the diameter of the ground circle it
     * is currently overhead of, {@code daylight} true while it is sunlit.
     */
    public record Station(String name, double lat, double lon, double altitudeKm,
            double speedKmh, double footprintKm, boolean daylight, long atEpochSeconds) {
    }

    private record Cached(Instant at, Station station) {
    }

    /** Transport order of the old {@code @DirectFirst} seam: direct first, browser joker as fallback. */
    private static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(10);
    private volatile Cached cached;

    /** Production: the shared house fetcher; {@code MODES} carries the old {@code @DirectFirst} order. */
    @Inject
    public OrbitClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The current fix; empty until the first success, stale kept only briefly. */
    public Optional<Station> station() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return Optional.of(hit.station());
        }
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("Accept", "application/json"),
                    requestTimeout, MODES);
            if (resp != null && resp.status() == 200) {
                Optional<Station> parsed = parse(resp.body());
                parsed.ifPresent(s -> cached = new Cached(Instant.now(), s));
                if (parsed.isPresent()) return parsed;
            } else {
                LOG.debug("[ISS] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[ISS] fetch failed: {}", e.getMessage());
        }
        // A position minutes old is worse than none - the dot would sit on the
        // wrong continent. Only the fresh fix is offered.
        return Optional.empty();
    }

    /** Package-visible for the fixture test. */
    Optional<Station> parse(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.path("latitude").isNumber() || !root.path("longitude").isNumber()) {
                return Optional.empty();
            }
            return Optional.of(new Station(
                    root.path("name").asText("iss"),
                    root.path("latitude").asDouble(),
                    root.path("longitude").asDouble(),
                    root.path("altitude").asDouble(0),
                    root.path("velocity").asDouble(0),
                    root.path("footprint").asDouble(0),
                    !"eclipsed".equalsIgnoreCase(root.path("visibility").asText("")),
                    root.path("timestamp").asLong(0)));
        } catch (Exception e) {
            LOG.debug("[ISS] parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
