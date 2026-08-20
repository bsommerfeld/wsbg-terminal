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
 * The German motorway network's own open API (live-probed 2026-08-03, keyless):
 * {@code verkehr.autobahn.de} is run by Autobahn GmbH des Bundes and answers
 * the live incidents and the active blockages of every Bundesautobahn, each
 * with real coordinates. This is the "Unfälle?" layer for Germany - and a
 * blocked A3 is a supply-chain fact, not a motorist's inconvenience.
 *
 * <p>No curated road list: the API names its own roads
 * ({@code /o/autobahn/}) and the client sweeps exactly that list. Politeness
 * arithmetic: ~130 roads, two endpoints each, behind a
 * {@value #CACHE_TTL_MINUTES}-minute cache - roughly four requests a minute
 * sustained against an API built for exactly this traffic. A failing road
 * costs its own stretch, never the sweep.
 */
@Singleton
public class AutobahnClient {

    private static final Logger LOG = LoggerFactory.getLogger(AutobahnClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BASE = "https://verkehr.autobahn.de/o/autobahn/";
    private static final long CACHE_TTL_MINUTES = 45;
    private static final int MAX_INCIDENTS = 220;

    /** Direct-first (joker mandate 2026-07-14): the federal API is built for this traffic. */
    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    /**
     * One motorway incident: {@code kind} is WARNING (a live disruption) or
     * CLOSURE (a stretch actually blocked), {@code blocked} the API's own
     * "nothing gets through here" flag.
     */
    public record RoadIncident(String kind, String road, String title, String direction,
            boolean blocked, String startIso, double lat, double lon) {
    }

    private record Cached(Instant at, List<RoadIncident> incidents) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);
    private volatile Cached cached;

    @Inject
    public AutobahnClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The current picture across the whole network; stale kept on failure. */
    public List<RoadIncident> incidents() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(Duration.ofMinutes(CACHE_TTL_MINUTES)))) {
            return hit.incidents();
        }
        try {
            List<String> roads = roads();
            if (!roads.isEmpty()) {
                List<RoadIncident> out = new ArrayList<>();
                // The WHOLE network is swept before anything is dropped:
                // breaking out at the cap would silently mean "the A1 and the
                // A2", because the road list is alphabetical (live-measured
                // 2026-08-03: 227 incidents, every one of them from A1..A3).
                for (String road : roads) {
                    out.addAll(sweep(road, "warning", "WARNING"));
                    out.addAll(sweep(road, "closure", "CLOSURE"));
                }
                // Rank before capping: a blocked stretch outranks a lane
                // narrowing wherever in the country it happens to be.
                out.sort((a, b) -> {
                    if (a.blocked() != b.blocked()) return a.blocked() ? -1 : 1;
                    return kindRank(a) - kindRank(b);
                });
                if (out.size() > MAX_INCIDENTS) {
                    LOG.debug("[AUTOBAHN] {} incidents found, showing the {} loudest",
                            out.size(), MAX_INCIDENTS);
                    out = new ArrayList<>(out.subList(0, MAX_INCIDENTS));
                }
                // An empty autobahn network is a valid (night-time) answer.
                cached = new Cached(Instant.now(), List.copyOf(out));
                return cached.incidents();
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[AUTOBAHN] sweep failed: {}", e.getMessage());
        }
        return hit == null ? List.of() : hit.incidents();
    }

    /** A live disruption beats a running roadworks site when the cap bites. */
    private static int kindRank(RoadIncident incident) {
        return "WARNING".equals(incident.kind()) ? 0 : 1;
    }

    /** The API's own road list - the house never maintains one. */
    private List<String> roads() throws Exception {
        WebResponse resp = fetcher.fetch(BASE,
                Map.of("Accept", "application/json"), requestTimeout, MODES);
        if (resp == null || resp.status() != 200) {
            LOG.debug("[AUTOBAHN] road list answered status {}",
                    resp == null ? "null" : resp.status());
            return List.of();
        }
        return parseRoads(resp.body());
    }

    /** Package-visible for the fixture test. */
    static List<String> parseRoads(String body) {
        try {
            JsonNode roads = JSON.readTree(body).path("roads");
            if (!roads.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode r : roads) {
                String name = r.asText("").strip();
                if (!name.isEmpty()) out.add(name);
            }
            return List.copyOf(out);
        } catch (Exception e) {
            LOG.debug("[AUTOBAHN] road list parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RoadIncident> sweep(String road, String service, String kind) {
        try {
            String url = BASE + road.strip().replace(" ", "%20") + "/services/" + service;
            WebResponse resp = fetcher.fetch(url,
                    Map.of("Accept", "application/json"), requestTimeout, MODES);
            if (resp == null || resp.status() != 200) return List.of();
            return parse(resp.body(), service, kind, road);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[AUTOBAHN] {} {} failed: {}", road, service, e.getMessage());
            return List.of();
        }
    }

    /**
     * Package-visible for the fixture test. Roadworks announced for next month
     * are a calendar entry, not a current event - only what is running NOW rides.
     */
    List<RoadIncident> parse(String body, String service, String kind, String road) {
        try {
            JsonNode items = JSON.readTree(body).path(service);
            if (!items.isArray()) return List.of();
            List<RoadIncident> out = new ArrayList<>();
            for (JsonNode i : items) {
                if (i.path("future").asBoolean(false)) continue;
                JsonNode at = i.path("coordinate");
                Double lat = numOrNull(at, "lat");
                Double lon = numOrNull(at, "long");
                if (lat == null || lon == null) continue;
                out.add(new RoadIncident(kind, road,
                        textOrNull(i, "title"), textOrNull(i, "subtitle"),
                        i.path("isBlocked").asBoolean(false),
                        textOrNull(i, "startTimestamp"), lat, lon));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            LOG.debug("[AUTOBAHN] {} parse failed: {}", road, e.getMessage());
            return List.of();
        }
    }

    /** The coordinates arrive as JSON strings, not numbers. */
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

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText().strip() : null;
    }
}
