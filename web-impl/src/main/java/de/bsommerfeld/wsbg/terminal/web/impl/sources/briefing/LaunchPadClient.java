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

/**
 * The Space Devs' Launch Library 2 (researched + live-probed 2026-07-17,
 * keyless): every scheduled orbital launch worldwide with its pad's
 * coordinates, provider and T-0. A rocket on the pad is a dated, geolocated
 * event, and the industry behind it is listed (launch providers, their
 * suppliers, the satellite operators whose capacity rides up).
 *
 * <p>Only launches with a real pad position ride; the window is bounded to the
 * next few days, because a flight three months out is a calendar entry, not a
 * picture of today.
 */
@Singleton
public class LaunchPadClient {

    private static final Logger LOG = LoggerFactory.getLogger(LaunchPadClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String URL =
            "https://ll.thespacedevs.com/2.2.0/launch/upcoming/?limit=40";
    /** The manifest shifts by hours, not minutes - and the API is politeness-rate-limited. */
    private static final Duration CACHE_TTL = Duration.ofHours(2);
    /** Beyond this horizon a pad marker is a calendar entry, not a world event. */
    private static final Duration HORIZON = Duration.ofDays(7);
    private static final int MAX_LAUNCHES = 25;

    /**
     * One scheduled launch at its pad: {@code status} is the provider's own
     * short token (Go/TBD/Hold/Success), {@code netIso} the current T-0.
     */
    public record Launch(String name, String provider, String pad, String status,
            String netIso, double lat, double lon) {
    }

    private record Cached(Instant at, List<Launch> launches) {
    }

    /** Transport order of the old {@code @DirectFirst} seam: direct first, browser joker as fallback. */
    private static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);
    private volatile Cached cached;

    /** Production: the shared house fetcher; {@code MODES} carries the old {@code @DirectFirst} order. */
    @Inject
    public LaunchPadClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The launches inside the horizon; stale kept on failure. */
    public List<Launch> upcoming() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.launches();
        }
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("Accept", "application/json"),
                    requestTimeout, MODES);
            if (resp != null && resp.status() == 200) {
                List<Launch> parsed = parse(resp.body(), Instant.now());
                if (!parsed.isEmpty()) {
                    cached = new Cached(Instant.now(), parsed);
                    return parsed;
                }
            } else {
                LOG.debug("[LAUNCH] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[LAUNCH] fetch failed: {}", e.getMessage());
        }
        return hit == null ? List.of() : hit.launches();
    }

    /** Package-visible for the fixture test; {@code now} anchors the horizon. */
    List<Launch> parse(String body, Instant now) {
        try {
            JsonNode results = JSON.readTree(body).path("results");
            if (!results.isArray()) return List.of();
            Instant until = now.plus(HORIZON);
            List<Launch> out = new ArrayList<>();
            for (JsonNode r : results) {
                JsonNode pad = r.path("pad");
                Double lat = numOrNull(pad, "latitude");
                Double lon = numOrNull(pad, "longitude");
                if (lat == null || lon == null) continue;
                String netIso = r.path("net").asText(null);
                Instant net = parseInstant(netIso);
                if (net != null && net.isAfter(until)) continue;
                out.add(new Launch(
                        r.path("name").asText(""),
                        r.path("launch_service_provider").path("name").asText(null),
                        pad.path("location").path("name").asText(null),
                        r.path("status").path("abbrev").asText(null),
                        netIso, lat, lon));
                if (out.size() >= MAX_LAUNCHES) break;
            }
            return List.copyOf(out);
        } catch (Exception e) {
            LOG.debug("[LAUNCH] parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** The pad coordinates arrive as JSON strings, not numbers. */
    private static Double numOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isNumber()) return v.asDouble();
        if (!v.isTextual() || v.asText().isBlank()) return null;
        try {
            return Double.valueOf(v.asText().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant parseInstant(String iso) {
        try {
            return iso == null || iso.isBlank() ? null : Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }
}
