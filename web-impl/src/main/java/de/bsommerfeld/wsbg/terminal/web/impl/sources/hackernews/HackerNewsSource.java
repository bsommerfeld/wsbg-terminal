package de.bsommerfeld.wsbg.terminal.web.impl.sources.hackernews;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.impl.text.TextMatch;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import de.bsommerfeld.wsbg.terminal.web.source.SearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tech-salience signal via Hacker News' keyless Algolia search API
 * ({@code hn.algolia.com/api/v1/search}, officially documented, live-probed
 * 2026-07-16): when a company surfaces on Hacker News, that is an EVENT of its
 * own — the nerd public has noticed — and the points/comment counts are the
 * weight of that attention, so they ride in {@link Article#summary()} rather
 * than being flattened away.
 *
 * <p>Both doors of the new world in one source: as an
 * {@link InstrumentSource} it is NAME-addressed only — the query is the
 * significant words of the company name; HN knows neither tickers nor ISINs,
 * so those keys are ignored. Algolia matches loosely (URL text counts as a
 * match too), so every hit additionally passes the house precision filter
 * against the TITLE — a wrong-company story in the brief is worse than a
 * missing one. As a {@link SearchEngine} it carries the free research query
 * verbatim and UNFILTERED — the caller phrased it.
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
public class HackerNewsSource extends AbstractWebSource
        implements InstrumentSource, SearchEngine {

    private static final Logger LOG = LoggerFactory.getLogger(HackerNewsSource.class);

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

    private final Duration requestTimeout = Duration.ofSeconds(10);

    /** Per-query TTL politeness cache, bounded LRU — a DD run asks the same names in bursts. */
    private final Map<String, CachedQuery> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedQuery> eldest) {
            return size() > CACHE_MAX_ENTRIES;
        }
    };

    private record CachedQuery(Instant fetchedAt, List<Article> items) {}

    @Inject
    public HackerNewsSource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "hackernews";
    }

    /**
     * Direct first: the Algolia API is keyless JSON with no wall (live-probed
     * 2026-07-16, plain client 200) — the joker stays the fallback.
     */
    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /** NAME-addressed only: HN knows neither tickers nor ISINs. */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        String companyName = instrument.name();
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = TextMatch.significantWords(companyName);
        if (words.isEmpty()) return List.of();
        String query = String.join(" ", words);
        return cap(cachedSearch(query, words), limit);
    }

    /** Free research query, verbatim, unfiltered — the caller phrased it. */
    @Override
    public List<Article> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        return cap(cachedSearch(query.trim(), Set.of()), limit);
    }

    /**
     * The per-query cache seam: a fresh entry answers without a fetch; a failed
     * or garbage answer is never cached (the next call retries).
     */
    private List<Article> cachedSearch(String query, Set<String> words) {
        synchronized (cache) {
            CachedQuery hit = cache.get(query);
            if (hit != null && hit.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
                return hit.items();
            }
        }
        List<Article> items = liveSearch(query, words);
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
    private List<Article> liveSearch(String query, Set<String> words) {
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
            WebResponse resp = get(url,
                    Map.of("Accept", "application/json"),
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
     * Algolia {@code hits[]} → {@link Article}s, title-precision-filtered.
     * Empty {@code nameWords} = NO filter requested (the free research query
     * is the caller's own phrasing). Garbage (HTML error pages, torn JSON,
     * null) yields {@code null} so a bad body is never cached; a well-formed
     * answer with no matching hits yields an empty list. Package-private for
     * tests.
     */
    static List<Article> parse(String body, Set<String> nameWords) {
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
        List<Article> out = new ArrayList<>();
        for (JsonNode hit : hits) {
            String id = hit.path("objectID").asText("").trim();
            String title = hit.path("title").asText("").trim();
            if (id.isEmpty() || title.isEmpty()) continue;
            // Precision over recall: Algolia also matches on the story URL —
            // the delivered TEXT must name the company: the title, or for
            // self-posts (Ask HN) the post body Algolia ships as story_text
            // (mandate 2026-07-16: scan everything a source hands over). A
            // bare URL match still never counts.
            String storyText = hit.path("story_text").asText("")
                    .replaceAll("<[^>]+>", " ");
            if (!nameWords.isEmpty()
                    && !titleMatches(title, nameWords)
                    && !titleMatches(storyText, nameWords)) continue;
            String url = hit.path("url").asText("").trim();
            // Self posts (Ask HN etc.) carry no url — the HN thread IS the item.
            String link = url.isEmpty() ? HN_ITEM_URL + id : url;
            int points = hit.path("points").asInt(0);
            int comments = hit.path("num_comments").asInt(0);
            out.add(new Article(
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

    private static List<Article> cap(List<Article> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /** True when the title carries at least one significant word of the queried name. */
    static boolean titleMatches(String title, Set<String> nameWords) {
        return TextMatch.matchesAny(title, nameWords);
    }
}
