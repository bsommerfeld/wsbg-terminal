package de.bsommerfeld.wsbg.terminal.hackernews;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tech-salience signal via Hacker News' keyless Algolia search API
 * ({@code hn.algolia.com/api/v1/search}, officially documented, live-probed
 * 2026-07-16): when a company surfaces on Hacker News, that is an EVENT of its
 * own — the nerd public has noticed — and the points/comment counts are the
 * weight of that attention, so they ride in {@link RawNewsItem#summary()}
 * rather than being flattened away.
 *
 * <p>NAME-addressed only: the query is the significant words of the company
 * name; {@link #newsFor} and {@link #newsForIsin} stay no-ops (HN knows
 * neither tickers nor ISINs). Algolia matches loosely (URL text counts as a
 * match too), so every hit additionally passes the house precision filter
 * against the TITLE — a wrong-company story in the brief is worse than a
 * missing one.
 *
 * <p><b>Recency, not archive:</b> only the last ~90 days are queried via
 * {@code numericFilters=created_at_i><cutoff>} — fresh salience evidence, not
 * 2015 nostalgia threads. <b>Pinned quirk (live 2026-07-16):</b> the {@code >}
 * in numericFilters MUST be percent-encoded ({@code %3E}); sent literally, the
 * front-end answers 400 with an HTML error page, not JSON.
 *
 * <p>Hit shape (pinned live 2026-07-16): {@code hits[]} with {@code objectID}
 * (stable id), {@code title}, {@code url} (ABSENT/null for Ask HN &amp; other
 * self posts — the item then links to its own HN thread
 * {@code news.ycombinator.com/item?id=<objectID>}), {@code points},
 * {@code num_comments}, {@code created_at} (ISO-8601), {@code author}.
 */
@Singleton
public class HackerNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(HackerNewsClient.class);

    private static final String SEARCH_URL = "https://hn.algolia.com/api/v1/search";
    private static final String HN_ITEM_URL = "https://news.ycombinator.com/item?id=";
    private static final String PUBLISHER = "Hacker News";

    /** Only the last ~90 days count as salience evidence — no archive dredging. */
    private static final Duration RECENCY_WINDOW = Duration.ofDays(90);
    /** Fetched wider than any sane limit: the precision filter trims loose Algolia matches. */
    private static final int HITS_PER_PAGE = 30;

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final int CACHE_MAX_ENTRIES = 64;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Generic words that must never carry the title-relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien");

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(10);

    /** Per-query TTL cache, bounded LRU — a DD run asks the same names in bursts. */
    private final Map<String, CachedQuery> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedQuery> eldest) {
            return size() > CACHE_MAX_ENTRIES;
        }
    };

    private record CachedQuery(Instant fetchedAt, List<RawNewsItem> items) {}

    /** Test/default: plain direct transport. */
    public HackerNewsClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@code @DirectFirst} seam — the Algolia API is keyless
     * JSON with no wall (live-probed 2026-07-16, plain client 200), so this
     * client declares "fine on plain HTTP" like the other no-wall sources.
     */
    @Inject
    public HackerNewsClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "hackernews";
    }

    /** No-op: HN knows no ticker symbols — companies only surface by name. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /** No-op: HN knows no ISINs — companies only surface by name. */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        return List.of();
    }

    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();
        String query = String.join(" ", words);
        return cap(cachedSearch(query, words), limit);
    }

    /**
     * The per-query cache seam: a fresh entry answers without a fetch; a failed
     * or garbage answer is never cached (the next call retries).
     */
    private List<RawNewsItem> cachedSearch(String query, Set<String> words) {
        synchronized (cache) {
            CachedQuery hit = cache.get(query);
            if (hit != null && hit.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
                return hit.items();
            }
        }
        List<RawNewsItem> items = search(query, words);
        if (items != null) {
            synchronized (cache) {
                cache.put(query, new CachedQuery(Instant.now(), items));
            }
            return items;
        }
        return List.of();
    }

    /**
     * One live Algolia search, precision-filtered. Returns {@code null} on a
     * transport/parse failure (never cached), an empty list on a clean
     * no-match answer (cached like any result).
     */
    private List<RawNewsItem> search(String query, Set<String> words) {
        long cutoff = Instant.now().minus(RECENCY_WINDOW).getEpochSecond();
        // URLEncoder percent-encodes the '>' in numericFilters — sent literally
        // the front-end 400s with an HTML page (pinned live 2026-07-16).
        String url = SEARCH_URL
                + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&tags=story"
                + "&hitsPerPage=" + HITS_PER_PAGE
                + "&numericFilters=" + URLEncoder.encode(
                        "created_at_i>" + cutoff, StandardCharsets.UTF_8);
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp == null || resp.status() != 200) {
                LOG.debug("HN Algolia search answered status {} for '{}'",
                        resp == null ? "null" : resp.status(), query);
                return null;
            }
            return parse(resp.body(), words);
        } catch (Exception e) {
            LOG.debug("HN Algolia search failed for '{}': {}", query, e.getMessage());
            return null;
        }
    }

    /**
     * Algolia {@code hits[]} → {@link RawNewsItem}s, title-precision-filtered.
     * Garbage (HTML error pages, torn JSON, null) yields {@code null} so a bad
     * body is never cached; a well-formed answer with no matching hits yields
     * an empty list. Package-private for tests.
     */
    static List<RawNewsItem> parse(String body, Set<String> nameWords) {
        if (body == null || body.isBlank()) return null;
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            LOG.debug("HN Algolia answer is not JSON: {}", e.getMessage());
            return null;
        }
        JsonNode hits = root.path("hits");
        if (!hits.isArray()) return null;
        List<RawNewsItem> out = new ArrayList<>();
        for (JsonNode hit : hits) {
            String id = hit.path("objectID").asText("").trim();
            String title = hit.path("title").asText("").trim();
            if (id.isEmpty() || title.isEmpty()) continue;
            // Precision over recall: Algolia also matches on the story URL —
            // only a title that names the company counts.
            if (!titleMatches(title, nameWords)) continue;
            String url = hit.path("url").asText("").trim();
            // Self posts (Ask HN etc.) carry no url — the HN thread IS the item.
            String link = url.isEmpty() ? HN_ITEM_URL + id : url;
            int points = hit.path("points").asInt(0);
            int comments = hit.path("num_comments").asInt(0);
            out.add(new RawNewsItem(
                    id,
                    title,
                    PUBLISHER,
                    link,
                    parseCreatedAt(hit.path("created_at").asText(null)),
                    List.of(),
                    null,
                    // The salience signal belongs in the line the model reads.
                    points + " Punkte, " + comments + " Kommentare auf Hacker News",
                    false));
        }
        return out;
    }

    /** ISO-8601 {@code created_at} → {@link Instant}; unparseable → null, never a guess. */
    static Instant parseCreatedAt(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) return null;
        try {
            return Instant.parse(createdAt.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static List<RawNewsItem> cap(List<RawNewsItem> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /** True when the title carries at least one significant word of the queried name. */
    static boolean titleMatches(String title, Set<String> nameWords) {
        if (nameWords.isEmpty()) return false;
        String t = normalize(title);
        for (String w : nameWords) {
            if (t.matches(".*\\b" + Pattern.quote(w) + "\\b.*")) return true;
        }
        return false;
    }

    /** Significant (length ≥ 3, non-generic) words of the queried name, umlaut-normalised. */
    static Set<String> significantWords(String name) {
        if (name == null || name.isBlank()) return Set.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }
}
