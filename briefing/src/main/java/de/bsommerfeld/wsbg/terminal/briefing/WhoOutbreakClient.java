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

/**
 * WHO Disease Outbreak News (DON) — the epidemic-signal leg, keyless OData
 * (live-probed 2026-07-14): {@code who.int/api/news/diseaseoutbreaknews} with
 * {@code $orderby=PublicationDateAndTime desc}, {@code $top=N},
 * {@code $format=json}. Each item carries {@code Title}, an ISO
 * {@code PublicationDateAndTime} (UTC), a plain-language {@code Summary} head,
 * and {@code DonId}/{@code ItemDefaultUrl} for the deep link. The heavy HTML
 * fields (Response/Assessment/Epidemiology) are ignored — the Summary head is
 * the report-sized substance.
 *
 * <p>Slow-moving signal: cache 6h. Best-effort empty on any failure.
 */
@Singleton
public class WhoOutbreakClient {

    private static final Logger LOG = LoggerFactory.getLogger(WhoOutbreakClient.class);

    private static final String BASE =
            "https://www.who.int/api/news/diseaseoutbreaknews"
            + "?$orderby=PublicationDateAndTime%20desc&$format=json&$top=";
    private static final String LINK_BASE = "https://www.who.int/emergencies/disease-outbreak-news/item";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    /** One outbreak bulletin: headline, plain-language summary head, publish instant, deep link. */
    public record Outbreak(String title, String summary, Instant publishedAt, String link) {
    }

    private record Cached(Instant at, List<Outbreak> items) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(15);
    private volatile Cached cache;

    /** Test/default: plain direct transport. */
    public WhoOutbreakClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam — the WHO API is wall-less. */
    @Inject
    public WhoOutbreakClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The latest {@code limit} outbreak bulletins, newest first. */
    public synchronized List<Outbreak> latest(int limit) {
        Cached hit = cache;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return cap(hit.items(), limit);
        }
        try {
            WebResponse resp = fetcher.fetch(BASE + Math.max(limit, 10),
                    Map.of("Accept", "application/json"), requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<Outbreak> items = parse(resp.body());
                if (!items.isEmpty()) {
                    cache = new Cached(Instant.now(), items);
                    return cap(items, limit);
                }
            } else {
                LOG.debug("[WHO] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[WHO] failed: {}", e.getMessage());
        }
        return hit != null ? cap(hit.items(), limit) : List.of();
    }

    /** Package-private for tests: WHO OData JSON → outbreaks, network-free. */
    static List<Outbreak> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Outbreak> out = new ArrayList<>();
        try {
            for (JsonNode n : JSON.readTree(json).path("value")) {
                String title = n.path("Title").asText("").strip();
                if (title.isEmpty()) continue;
                String url = n.path("ItemDefaultUrl").asText("");
                out.add(new Outbreak(title,
                        blankToNull(n.path("Summary").asText("")),
                        parseDate(n.path("PublicationDateAndTime").asText("")),
                        url.isBlank() ? null : LINK_BASE + url));
            }
        } catch (Exception e) {
            LOG.debug("[WHO] parse failed: {}", e.getMessage());
        }
        return out;
    }

    private static Instant parseDate(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso.endsWith("Z") || iso.contains("+") ? iso : iso + "Z")
                    .toInstant();
        } catch (Exception e) {
            try {
                return Instant.parse(iso.endsWith("Z") ? iso : iso + "Z");
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static List<Outbreak> cap(List<Outbreak> list, int limit) {
        return list.size() > limit ? List.copyOf(list.subList(0, limit)) : list;
    }
}
