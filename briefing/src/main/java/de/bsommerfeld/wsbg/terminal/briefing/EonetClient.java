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
import java.util.Set;

/**
 * NASA EONET - the Earth Observatory Natural Event Tracker (researched +
 * live-probed 2026-07-17, keyless): NASA's own curated register of what the
 * planet is currently DOING, every event carrying real geometry. This is the
 * layer for the natural world beyond weather and disaster alerts: erupting
 * volcanoes, drifting icebergs, dust and haze plumes, landslides, sea and lake
 * ice, algal blooms, temperature extremes, and the man-made events NASA tracks
 * (oil spills, industrial fires).
 *
 * <p>Wildfires are dropped on purpose: {@link WildfireClient} already draws
 * them from the raw satellite detections, at a density EONET's curated list
 * cannot match - carrying both would paint the same fires twice.
 *
 * <p>An event's geometry is a TRACK, not a dot: a storm or an iceberg reports
 * a new position each day. The newest point is the one that counts.
 */
@Singleton
public class EonetClient {

    private static final Logger LOG = LoggerFactory.getLogger(EonetClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String URL =
            "https://eonet.gsfc.nasa.gov/api/v3/events?status=open&limit=500";
    /** A curated register moves in days, not minutes. */
    private static final Duration CACHE_TTL = Duration.ofHours(3);
    private static final int MAX_EVENTS = 180;

    /** {@link WildfireClient} owns fire, at a resolution this register cannot match. */
    private static final Set<String> OWNED_ELSEWHERE = Set.of("wildfires");

    /** One open natural event at its newest reported position. */
    public record NaturalEvent(String category, String title, double lat, double lon,
            String atIso, String link) {
    }

    private record Cached(Instant at, List<NaturalEvent> events) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(25);
    private volatile Cached cached;

    /** Test/default: plain direct transport. */
    public EonetClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam (joker mandate 2026-07-14). */
    @Inject
    public EonetClient(@DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The open events; stale kept on failure. */
    public List<NaturalEvent> events() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.events();
        }
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<NaturalEvent> parsed = parse(resp.body());
                if (!parsed.isEmpty()) {
                    cached = new Cached(Instant.now(), parsed);
                    return parsed;
                }
            } else {
                LOG.debug("[EONET] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[EONET] fetch failed: {}", e.getMessage());
        }
        return hit == null ? List.of() : hit.events();
    }

    /** Package-visible for the fixture test. */
    List<NaturalEvent> parse(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode events = root.path("events");
            if (!events.isArray()) return List.of();
            List<NaturalEvent> out = new ArrayList<>();
            for (JsonNode e : events) {
                String category = firstCategory(e);
                if (category == null || OWNED_ELSEWHERE.contains(category)) continue;
                JsonNode newest = newestPoint(e.path("geometry"));
                if (newest == null) continue;
                JsonNode at = newest.path("coordinates");
                out.add(new NaturalEvent(category, e.path("title").asText(""),
                        at.get(1).asDouble(), at.get(0).asDouble(),
                        newest.path("date").asText(null), e.path("link").asText(null)));
                if (out.size() >= MAX_EVENTS) break;
            }
            return List.copyOf(out);
        } catch (Exception e) {
            LOG.debug("[EONET] parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String firstCategory(JsonNode event) {
        JsonNode categories = event.path("categories");
        if (!categories.isArray() || categories.isEmpty()) return null;
        String id = categories.get(0).path("id").asText(null);
        return id == null || id.isBlank() ? null : id;
    }

    /**
     * The event's geometry is a track - the LAST entry is where it is now.
     * Polygon-shaped entries (some storms report an area) carry no single
     * point and are skipped rather than reduced to a fake centre.
     */
    private static JsonNode newestPoint(JsonNode geometry) {
        if (!geometry.isArray()) return null;
        for (int i = geometry.size() - 1; i >= 0; i--) {
            JsonNode g = geometry.get(i);
            if (!"Point".equals(g.path("type").asText())) continue;
            JsonNode at = g.path("coordinates");
            if (at.isArray() && at.size() >= 2 && at.get(0).isNumber() && at.get(1).isNumber()) {
                return g;
            }
        }
        return null;
    }
}
