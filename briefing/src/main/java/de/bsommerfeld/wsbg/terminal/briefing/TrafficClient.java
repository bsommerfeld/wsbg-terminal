package de.bsommerfeld.wsbg.terminal.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ground-traffic disruption leg — two keyless JSON sources (both
 * live-probed 2026-07-14). LOWEST priority of the fishing-net set.
 *
 * <ul>
 *   <li><b>Autobahn API</b>:
 *       {@code verkehr.autobahn.de/o/autobahn/<road>/services/<kind>} with
 *       {@code kind ∈ {closure, roadworks, warning}}. The response wraps its
 *       items under a key equal to {@code kind}; per item {@code title},
 *       {@code subtitle} (the direction), a {@code description[]} string array,
 *       and (warnings) a {@code startTimestamp} ISO instant. Fetched per
 *       requested road.</li>
 *   <li><b>MVG</b> (Munich transit): {@code mvg.de/api/bgw-pt/v3/messages} — a
 *       large disruption array (~390 KB, parsed defensively + capped). Per
 *       message {@code title}, HTML {@code description} (stripped),
 *       {@code type} ({@code INCIDENT}/{@code SCHEDULE_CHANGE}), epoch-milli
 *       {@code publication}, and the affected {@code lines[].label}.</li>
 * </ul>
 *
 * <p>Cache 12h per road/endpoint. Best-effort empty on any failure.
 */
@Singleton
public class TrafficClient {

    private static final Logger LOG = LoggerFactory.getLogger(TrafficClient.class);

    private static final String AUTOBAHN = "https://verkehr.autobahn.de/o/autobahn/";
    private static final String MVG = "https://www.mvg.de/api/bgw-pt/v3/messages";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CACHE_TTL = Duration.ofHours(12);
    /** MVG runaway backstop — the feed carries ~300 messages. */
    private static final int MVG_BACKSTOP = 200;

    /** Autobahn disruption kinds (each a distinct services endpoint). */
    public enum AutobahnKind {
        CLOSURE("closure"), ROADWORKS("roadworks"), WARNING("warning");

        private final String path;

        AutobahnKind(String path) {
            this.path = path;
        }
    }

    /** One motorway disruption: road, kind, headline, direction subtitle, joined description, start instant. */
    public record RoadEvent(String road, AutobahnKind kind, String title, String direction,
            String description, Instant startedAt) {
    }

    /** One transit disruption: title, plain-text description, type, publish instant, affected line labels. */
    public record TransitMessage(String title, String description, String type,
            Instant publishedAt, List<String> lines) {
    }

    private record CachedRoad(Instant at, List<RoadEvent> events) {
    }

    private record CachedTransit(Instant at, List<TransitMessage> messages) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(15);
    private final Map<String, CachedRoad> roadCache = new ConcurrentHashMap<>();
    private volatile CachedTransit transitCache;

    /** Test/default: plain direct transport. */
    public TrafficClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam — both APIs are wall-less. */
    @Inject
    public TrafficClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** One kind of disruption for one motorway (e.g. {@code "A1"}, {@link AutobahnKind#CLOSURE}). */
    public List<RoadEvent> autobahn(String road, AutobahnKind kind) {
        String key = road + ":" + kind.path;
        CachedRoad hit = roadCache.get(key);
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.events();
        }
        try {
            WebResponse resp = fetcher.fetch(AUTOBAHN + road + "/services/" + kind.path,
                    Map.of("Accept", "application/json"), requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<RoadEvent> events = parseAutobahn(road, kind, resp.body());
                roadCache.put(key, new CachedRoad(Instant.now(), events));
                return events;
            }
            LOG.debug("[Traffic] autobahn {} {} answered status {}", road, kind.path,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Traffic] autobahn {} {} failed: {}", road, kind.path, e.getMessage());
        }
        return hit != null ? hit.events() : List.of();
    }

    /** Closures + warnings across a road list (roadworks omitted — the report wants live disruption). */
    public List<RoadEvent> autobahnDisruptions(List<String> roads) {
        List<RoadEvent> out = new ArrayList<>();
        for (String road : roads) {
            out.addAll(autobahn(road, AutobahnKind.CLOSURE));
            out.addAll(autobahn(road, AutobahnKind.WARNING));
        }
        return out;
    }

    /** Current MVG (Munich transit) disruption messages. */
    public List<TransitMessage> mvgMessages() {
        CachedTransit hit = transitCache;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.messages();
        }
        try {
            WebResponse resp = fetcher.fetch(MVG,
                    Map.of("Accept", "application/json"), requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<TransitMessage> messages = parseMvg(resp.body());
                if (!messages.isEmpty()) {
                    transitCache = new CachedTransit(Instant.now(), messages);
                    return messages;
                }
            } else {
                LOG.debug("[Traffic] MVG answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Traffic] MVG failed: {}", e.getMessage());
        }
        return hit != null ? hit.messages() : List.of();
    }

    /** Package-private for tests: an Autobahn services JSON → road events, network-free. */
    static List<RoadEvent> parseAutobahn(String road, AutobahnKind kind, String json) {
        if (json == null || json.isBlank()) return List.of();
        List<RoadEvent> out = new ArrayList<>();
        try {
            JsonNode items = JSON.readTree(json).path(kind.path);
            for (JsonNode n : items) {
                String title = n.path("title").asText("").strip();
                if (title.isEmpty()) continue;
                StringBuilder desc = new StringBuilder();
                for (JsonNode line : n.path("description")) {
                    String s = line.asText("").strip();
                    if (s.isEmpty()) continue;
                    if (desc.length() > 0) desc.append(' ');
                    desc.append(s);
                }
                out.add(new RoadEvent(road, kind, title,
                        blankToNull(n.path("subtitle").asText("")),
                        desc.length() == 0 ? null : desc.toString(),
                        parseIso(n.path("startTimestamp").asText(""))));
            }
        } catch (Exception e) {
            LOG.debug("[Traffic] autobahn parse failed: {}", e.getMessage());
        }
        return out;
    }

    /** Package-private for tests: MVG messages JSON → transit messages (capped), network-free. */
    static List<TransitMessage> parseMvg(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<TransitMessage> out = new ArrayList<>();
        try {
            for (JsonNode n : JSON.readTree(json)) {
                String title = n.path("title").asText("").strip();
                if (title.isEmpty()) continue;
                List<String> lines = new ArrayList<>();
                for (JsonNode l : n.path("lines")) {
                    String label = l.path("label").asText("").strip();
                    if (!label.isEmpty() && !lines.contains(label)) lines.add(label);
                }
                out.add(new TransitMessage(title,
                        blankToNull(Rss.stripHtml(n.path("description").asText(""))),
                        blankToNull(n.path("type").asText("")),
                        parseEpochMillis(n.path("publication")),
                        List.copyOf(lines)));
                if (out.size() >= MVG_BACKSTOP) {
                    LOG.warn("[Traffic] MVG hit the {}-item runaway backstop", MVG_BACKSTOP);
                    break;
                }
            }
        } catch (Exception e) {
            LOG.debug("[Traffic] MVG parse failed: {}", e.getMessage());
        }
        return out;
    }

    private static Instant parseIso(String iso) {
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant parseEpochMillis(JsonNode n) {
        if (n == null || !n.isNumber()) return null;
        long ms = n.asLong(0);
        return ms > 0 ? Instant.ofEpochMilli(ms) : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
