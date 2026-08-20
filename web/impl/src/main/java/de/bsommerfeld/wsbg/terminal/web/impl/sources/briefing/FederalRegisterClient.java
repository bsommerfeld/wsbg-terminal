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
 *
 * <p>A third view was added 2026-08-03, and it is the one that looks FORWARD:
 * {@code conditions[effective_date][gte]} lists rules by the day they start to
 * bite, {@code conditions[comment_date][gte]} by the last day anyone can
 * object. Both are unbounded into the future (effective dates run years out),
 * so the register doubles as a dated regulatory docket. Deliberately NOT
 * queried per company — a name search answers with consortium notices from
 * years back, i.e. noise; which dated line matters for a subject is the
 * model's call downstream.
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
    private static final String DATE_FIELDS = "&fields%5B%5D=title&fields%5B%5D=type"
            + "&fields%5B%5D=effective_on&fields%5B%5D=comments_close_on"
            + "&fields%5B%5D=agency_names&fields%5B%5D=html_url";

    /**
     * One scheduled regulatory date. {@code deadline} separates the two kinds:
     * a comment window closing (the last day to object) reads differently from
     * a rule switching on.
     */
    public record DatedAction(LocalDate date, boolean deadline, String type,
            String title, String agency, String url) {
    }

    /** One Federal Register document; {@code abstractText} null when the register carries none. */
    public record Doc(String title, String type, LocalDate publicationDate,
            String abstractText, String url) {
    }

    private record Cached(Instant at, List<Doc> docs) {
    }

    private record CachedDates(Instant at, List<DatedAction> actions) {
    }

    /** Direct-first since the 2026-07-14 joker mandate; the browser is the rescue. */
    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(15);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, CachedDates> datedCache = new ConcurrentHashMap<>();

    @Inject
    public FederalRegisterClient(WebFetcher fetcher) {
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

    /**
     * The dated docket from {@code from} on, earliest first: rules taking
     * effect, then comment windows closing. Empty on any failure.
     */
    public List<DatedAction> upcomingDates(LocalDate from, int perLeg) {
        if (from == null) return List.of();
        int n = Math.min(Math.max(perLeg, 1), MAX_PER_PAGE);
        List<DatedAction> out = new ArrayList<>();
        out.addAll(fetchDated(BASE + "?conditions%5Beffective_date%5D%5Bgte%5D=" + from
                + "&conditions%5Btype%5D%5B%5D=RULE&order=oldest&per_page=" + n + DATE_FIELDS,
                false));
        out.addAll(fetchDated(BASE + "?conditions%5Bcomment_date%5D%5Bgte%5D=" + from
                + "&order=oldest&per_page=" + n + DATE_FIELDS, true));
        out.removeIf(a -> a.date() == null || a.date().isBefore(from));
        out.sort(java.util.Comparator.comparing(DatedAction::date));
        return out;
    }

    private List<DatedAction> fetchDated(String url, boolean deadline) {
        CachedDates hit = datedCache.get(url);
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.actions();
        }
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("Accept", "application/json"), requestTimeout, MODES);
            if (resp != null && resp.status() == 200) {
                List<DatedAction> actions = parseDated(resp.body(), deadline);
                if (!actions.isEmpty()) {
                    datedCache.put(url, new CachedDates(Instant.now(), actions));
                }
                return actions;
            }
            LOG.debug("[FedReg] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[FedReg] fetch {} failed: {}", url, e.getMessage());
        }
        return hit != null ? hit.actions() : List.of();
    }

    /** Package-private for tests: documents JSON → dated actions, network-free. */
    static List<DatedAction> parseDated(String json, boolean deadline) {
        if (json == null || json.isBlank()) return List.of();
        List<DatedAction> out = new ArrayList<>();
        try {
            for (JsonNode n : JSON.readTree(json).path("results")) {
                String title = n.path("title").asText("").strip();
                if (title.isEmpty()) continue;
                LocalDate date = parseDate(n.path(
                        deadline ? "comments_close_on" : "effective_on").asText(""));
                if (date == null) continue;
                JsonNode agencies = n.path("agency_names");
                out.add(new DatedAction(date, deadline,
                        blankToNull(n.path("type").asText("")), title,
                        agencies.isArray() && !agencies.isEmpty()
                                ? agencies.get(0).asText("").strip() : null,
                        blankToNull(n.path("html_url").asText(""))));
            }
        } catch (Exception e) {
            LOG.debug("[FedReg] dated parse failed: {}", e.getMessage());
        }
        return out;
    }

    private List<Doc> fetch(String url) {
        Cached hit = cache.get(url);
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.docs();
        }
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("Accept", "application/json"), requestTimeout, MODES);
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
