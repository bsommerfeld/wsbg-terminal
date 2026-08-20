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
 * IODA - Georgia Tech's Internet Outage Detection and Analysis (researched
 * 2026-07-17, live-probed 2026-08-02, keyless): three independent measurement
 * planes (BGP routing, active probing, background radiation) watching whether
 * a COUNTRY is still reachable. When a state pulls the plug during unrest, or
 * a cable is cut, or the grid fails, this is where it shows first - hours
 * before anyone writes about it.
 *
 * <p>Only alerts at {@code critical} level ride, and only the newest one per
 * country: the feed also carries every recovery back to normal, which is the
 * end of a story, not a current event.
 */
@Singleton
public class InternetOutageClient {

    private static final Logger LOG = LoggerFactory.getLogger(InternetOutageClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BASE =
            "https://api.ioda.inetintel.cc.gatech.edu/v2/outages/alerts";
    private static final Duration CACHE_TTL = Duration.ofMinutes(20);
    /** How far back an outage still counts as "now". */
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final int MAX_OUTAGES = 40;
    /**
     * IODA's own outage condition: reachability has to fall below 80 % of the
     * country's normal level. The alert LEVEL alone is not enough - the feed
     * keeps calling a country critical while it is already back at 94 % of
     * normal (live-measured 2026-08-02, Ethiopia), and a marker reading
     * "connectivity down" over a 6 % dip is simply false.
     */
    private static final double OUTAGE_RATIO = 0.8;

    /**
     * One country currently reading as unreachable: {@code source} is IODA's
     * measurement plane (bgp / active-probing / merit-nt), {@code value} the
     * current reading against {@code normalValue}, the level it should be at.
     */
    public record Outage(String countryCode, String country, String source,
            double value, double normalValue, long atEpochSeconds) {
    }

    private record Cached(Instant at, List<Outage> outages) {
    }

    /** Direct-first (joker mandate 2026-07-14); the browser is the rescue. */
    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);
    private volatile Cached cached;

    @Inject
    public InternetOutageClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The countries currently dark; stale kept on failure. */
    public List<Outage> outages() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.outages();
        }
        try {
            long now = Instant.now().getEpochSecond();
            // The endpoint insists on absolute epoch seconds; relative offsets
            // answer "'from' timestamp must be set" (live-probed).
            String url = BASE + "?from=" + (now - WINDOW.toSeconds()) + "&until=" + now
                    + "&entityType=country&limit=300";
            WebResponse resp = fetcher.fetch(url,
                    Map.of("Accept", "application/json"),
                    requestTimeout, MODES);
            if (resp != null && resp.status() == 200) {
                List<Outage> parsed = parse(resp.body());
                // An all-connected world is a VALID answer and gets cached.
                cached = new Cached(Instant.now(), parsed);
                return parsed;
            }
            LOG.debug("[IODA] answered status {}", resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[IODA] fetch failed: {}", e.getMessage());
        }
        return hit == null ? List.of() : hit.outages();
    }

    /** Package-visible for the fixture test. */
    List<Outage> parse(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.path("error").isNull() && root.path("error").isTextual()) {
                LOG.debug("[IODA] answered error: {}", root.path("error").asText());
                return List.of();
            }
            JsonNode data = root.path("data");
            if (!data.isArray()) return List.of();
            // Newest alert per country wins; a recovery to normal ENDS the story.
            Map<String, Outage> live = new LinkedHashMap<>();
            for (JsonNode a : data) {
                JsonNode entity = a.path("entity");
                String code = entity.path("code").asText(null);
                if (code == null || code.isBlank()) continue;
                double value = a.path("value").asDouble(0);
                double normal = a.path("historyValue").asDouble(0);
                boolean down = "critical".equals(a.path("level").asText(""))
                        && normal > 0 && value / normal <= OUTAGE_RATIO;
                long at = a.path("time").asLong(0);
                Outage seen = live.get(code);
                if (seen != null && seen.atEpochSeconds() > at) continue;
                if (!down) {
                    live.remove(code);
                    continue;
                }
                live.put(code, new Outage(code, entity.path("name").asText(code),
                        a.path("datasource").asText(null), value, normal, at));
            }
            List<Outage> out = new ArrayList<>(live.values());
            return out.size() > MAX_OUTAGES
                    ? List.copyOf(out.subList(0, MAX_OUTAGES)) : List.copyOf(out);
        } catch (Exception e) {
            LOG.debug("[IODA] parse failed: {}", e.getMessage());
            return List.of();
        }
    }
}
