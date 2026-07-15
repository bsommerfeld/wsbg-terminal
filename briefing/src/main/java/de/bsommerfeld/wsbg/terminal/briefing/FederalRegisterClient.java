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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Federal Register documents API (live-probed 2026-07-14): keyless JSON on
 * {@code federalregister.gov/api/v1/documents.json}, filtered by
 * {@code conditions[...]} query params (brackets MUST be percent-encoded —
 * raw brackets are illegal in a URI). Two views:
 * <ul>
 *   <li>presidential documents — {@code conditions[type][]=PRESDOCU} (EOs,
 *       proclamations, determinations as officially printed; {@code abstract}
 *       is usually null here)</li>
 *   <li>significant rules — {@code conditions[type][]=RULE} +
 *       {@code conditions[significant]=1} (final rules deemed significant
 *       under EO 12866, WITH abstracts)</li>
 * </ul>
 * Result shape pinned: {@code {count, results:[{title, type,
 * publication_date "YYYY-MM-DD", abstract|null, html_url}]}},
 * {@code order=newest}.
 */
@Singleton
public class FederalRegisterClient {

    private static final Logger LOG = LoggerFactory.getLogger(FederalRegisterClient.class);

    private static final String BASE = "https://www.federalregister.gov/api/v1/documents.json";
    private static final String FIELDS = "&fields%5B%5D=title&fields%5B%5D=type"
            + "&fields%5B%5D=publication_date&fields%5B%5D=abstract&fields%5B%5D=html_url";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    /** The API caps per_page well above this; 50 covers any briefing use. */
    private static final int MAX_PER_PAGE = 50;

    /** One Federal Register document; {@code abstractText} null when the register carries none. */
    public record Doc(String title, String type, LocalDate publicationDate,
            String abstractText, String url) {
    }

    private record Cached(Instant at, List<Doc> docs) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(15);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public FederalRegisterClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public FederalRegisterClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The newest presidential documents (EOs, proclamations, determinations). Empty on any failure. */
    public List<Doc> presidentialDocuments(int limit) {
        int n = Math.min(Math.max(limit, 1), MAX_PER_PAGE);
        return cap(fetch(BASE + "?conditions%5Btype%5D%5B%5D=PRESDOCU&order=newest&per_page=" + n
                + FIELDS), limit);
    }

    /** The newest significant final rules (EO 12866). Empty on any failure. */
    public List<Doc> significantRules(int limit) {
        int n = Math.min(Math.max(limit, 1), MAX_PER_PAGE);
        return cap(fetch(BASE + "?conditions%5Btype%5D%5B%5D=RULE&conditions%5Bsignificant%5D=1"
                + "&order=newest&per_page=" + n + FIELDS), limit);
    }

    private List<Doc> fetch(String url) {
        Cached hit = cache.get(url);
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.docs();
        }
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("Accept", "application/json"), requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<Doc> docs = parseDocuments(resp.body());
                if (!docs.isEmpty()) {
                    cache.put(url, new Cached(Instant.now(), docs));
                }
                return docs;
            }
            LOG.debug("[FedReg] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[FedReg] fetch {} failed: {}", url, e.getMessage());
        }
        // An outage keeps the stale pool instead of caching empty.
        return hit != null ? hit.docs() : List.of();
    }

    /** Package-private for tests: documents JSON → docs, network-free. */
    static List<Doc> parseDocuments(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Doc> out = new ArrayList<>();
        try {
            for (JsonNode n : JSON.readTree(json).path("results")) {
                String title = n.path("title").asText("").strip();
                if (title.isEmpty()) continue;
                out.add(new Doc(title,
                        blankToNull(n.path("type").asText("")),
                        parseDate(n.path("publication_date").asText("")),
                        n.hasNonNull("abstract") ? n.get("abstract").asText().strip() : null,
                        blankToNull(n.path("html_url").asText(""))));
            }
        } catch (Exception e) {
            LOG.debug("[FedReg] parse failed: {}", e.getMessage());
        }
        return out;
    }

    private static LocalDate parseDate(String iso) {
        try {
            return LocalDate.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static List<Doc> cap(List<Doc> list, int limit) {
        return list.size() > limit ? List.copyOf(list.subList(0, limit)) : list;
    }
}
