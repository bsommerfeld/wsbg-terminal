package de.bsommerfeld.wsbg.terminal.kapitalmarktexperten;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * kapitalmarktexperten.de - the most OPEN API of the whole 2026-08 source wave
 * and, at the same time, the one whose content must never be trusted at face
 * value. Both halves of that sentence shape this client.
 *
 * <p><b>The surface</b> (probed live 2026-08-02): a plain WordPress REST API at
 * {@code /wp-json/wp/v2/}. No paywall, no bot wall, no key, no rate limit,
 * {@code robots.txt} with an empty {@code Disallow:}. {@code /posts} answers
 * {@code X-WP-Total: 19460} and pages 195 deep, {@code _fields} slims a
 * 100-post page to 27 KB in 0,54 s, {@code ?after=}/{@code ?before=} filter
 * server-side (which is what makes the archive leg cheap), and
 * {@code content.rendered} carries the COMPLETE article body (2.500-4.100
 * characters) - no second fetch needed to read a piece. Sitemaps reach back to
 * 2010.
 *
 * <p><b>The catch:</b> roughly 60 articles a day, around the clock, under four
 * rotating author names - machine-generated single-stock copy written second
 * hand out of exactly the wires this terminal already taps directly (2024: 218
 * articles, 2026 YTD: 12.884). Per the house fischernetz principle the source
 * therefore comes IN, but <b>not onto the wire</b>: it is an ARCHIVE / due
 * diligence source with an origin marking.
 *
 * <ul>
 *   <li>{@link #newsForNameWindow} is the MAIN door - the date window is what
 *       {@code after}/{@code before} were made for, and the long tail of German
 *       small/micro caps is where this site is genuinely additive.</li>
 *   <li>{@link #newsForName} answers too, but every item it emits carries
 *       {@link #PUBLISHER} - literally {@code "Kapitalmarktexperten
 *       (KI-generiert)"}. That marking is not decoration: it is the hook a
 *       fact check further up the stack keys off, so such a line can never
 *       pass as a first-hand report. Same pattern as the CNBC Pro marker -
 *       the transport-level truth about a body travels in the publisher field,
 *       where every downstream consumer already looks.</li>
 *   <li>{@link #socialSentiment()} stays {@code false}: these are articles,
 *       not forum posts. Their cadence is not a room's attention, it is a
 *       generator's schedule.</li>
 * </ul>
 *
 * <p><b>The killer feature: {@code isin} is a TOP-LEVEL property on every
 * post</b> (e.g. {@code US0378331005}), so this source can answer an ISIN
 * EXACTLY instead of fuzzily. One trap, probed and confirmed:
 * {@code ?search=<ISIN>} returns 0 hits - the ISIN is <b>not</b> full-text
 * indexed, it is only usable as a FIELD. So both ISIN paths query by something
 * else and then cut on the field client-side:
 *
 * <ol>
 *   <li>{@link #newsForIsin} filters the shared recent POOL (see below) on the
 *       {@code isin} field - exact, zero extra fetches, but only as deep as the
 *       pool reaches (~{@value #POOL_PAGES} × {@value #PAGE_SIZE} posts ≈ five
 *       days at the site's cadence). That is the door for "what did this shop
 *       write about this instrument lately".</li>
 *   <li>{@link #newsForNameWindow} takes the ISIN as an additional sharpener:
 *       inside the window a post whose {@code isin} equals the queried one is
 *       kept unconditionally (no title guessing needed), everything else still
 *       has to pass the title-precision cut. Additive, never subtractive - an
 *       article that names the company without carrying the ISIN is not lost.</li>
 * </ol>
 *
 * <p><b>Two ways in, and when each wins.</b> 4.616 of the site's tags are
 * company names ({@code siemens} = 92 posts, {@code siemens-energy} = 178):
 *
 * <ul>
 *   <li><b>Tag route</b> ({@code tags?search=<name>} → {@code posts?tags=<id>})
 *       - PRECISE. The site itself decided the article is about that company,
 *       so no passing mention can slip in. Costs one extra (cached) lookup and
 *       fails only when the company has no tag. This is the preferred route
 *       for the archive window.</li>
 *   <li><b>Search route</b> ({@code posts?search=<name>}) - BROAD. The server
 *       matches full text, so it over-returns badly: "Siemens Energy" answers
 *       247 posts including Vulcan Energy pieces (probed). It therefore always
 *       runs behind the house TITLE-precision filter. It is the fallback for
 *       untagged companies and the door for name spellings the tag index
 *       doesn't carry.</li>
 * </ul>
 *
 * <p><b>The general (instrument-free) stream.</b> Beside the instrument fans
 * the whole newsroom surface is reachable, wired now so a later caller can be
 * mobilised without touching this class again: {@link #latest} (the
 * chronological firehose, served from a TTL pool), {@link #byTag} and
 * {@link #byCategory} (the topical streams - "Analystenstimmen" 8.657 posts,
 * "Earnings" 4.747, 74 categories in total), plus {@link #tags} and
 * {@link #categories}, which expose the term indexes themselves. The 4.616
 * company-name tags are a valuable index in their own right - a ready-made
 * roster of what a German retail-facing generator considers a coverable
 * instrument. Items from these methods carry the same generated marking as
 * every other item.
 *
 * <p><b>Cost discipline:</b> the pool behind {@link #latest} and
 * {@link #newsForIsin} is fetched at most once per {@link #POOL_TTL} and is
 * requested with a SLIM field set (no bodies). No instrument query ever
 * triggers an additional fetch beyond its own, and the resolved tag ids are
 * memoised for the process lifetime.
 *
 * <p><b>Deliberately NOT used:</b> {@code /feed/} answers 200 but carries only
 * 10 items spanning 2,6 hours - at 60 posts a day that drops most of the
 * stream on the floor, so the REST firehose replaces it. The per-category
 * feeds answer <b>410 Gone</b> outright. Neither is worth a fetch;
 * {@link #latest} and {@link #byCategory} cover both jobs properly.
 */
@Singleton
public class KapitalmarktexpertenClient implements NewsSource {

    private static final Logger LOG =
            LoggerFactory.getLogger(KapitalmarktexpertenClient.class);

    /** Short, stable source id. */
    static final String SOURCE = "kapitalmarktexperten";

    /**
     * The ONE publisher string this source ever emits. The origin marking is
     * carried in-band deliberately: everything here is machine-generated
     * second-hand copy, and a consumer that only ever reads {@code publisher}
     * must still be able to see that before it prints the line.
     */
    public static final String PUBLISHER = "Kapitalmarktexperten (KI-generiert)";

    static final String BASE = "https://www.kapitalmarktexperten.de/wp-json/wp/v2/";

    /**
     * Full post projection incl. the article body - used by the instrument
     * fans, where the body is the point (a DD reader gets the full text without
     * a second fetch).
     */
    static final String FIELDS_FULL = "id,date_gmt,link,title,isin,tags,excerpt,content";
    /**
     * Slim projection without bodies - used by the pool and the general
     * streams, where 100 posts must stay a 27 KB request.
     */
    static final String FIELDS_SLIM = "id,date_gmt,link,title,isin,tags,excerpt";

    /** Server-side maximum for {@code per_page}. */
    static final int PAGE_SIZE = 100;
    /** Pages the shared recent pool reaches back (~5 days at ~60 posts/day). */
    static final int POOL_PAGES = 3;
    /** Pages walked at most when filling an archive window. */
    static final int MAX_WINDOW_PAGES = 5;
    /** Fetch depth per name query before the title-precision cut bites. */
    static final int SEARCH_FETCH_SIZE = 50;
    /** How long the recent pool stays warm. */
    static final Duration POOL_TTL = Duration.ofMinutes(10);
    /** Cap for the lead text derived from {@code content.rendered}. */
    static final int SUMMARY_MAX = 400;

    /** Generic words that must never carry a title match on their own. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien",
            "stock", "stocks", "shares");

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");
    private static final Pattern HEX_ENTITY = Pattern.compile("&#[xX]([0-9a-fA-F]+);");
    /** A well-formed ISIN: 2 country letters, 9 alphanumerics, 1 check digit. */
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Newest first; undated items sort to the end. */
    private static final Comparator<RawNewsItem> BY_RECENCY =
            Comparator.comparing(RawNewsItem::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Resolved company-name → tag id, {@code -1} for "this site has no tag". */
    private final Map<String, Integer> tagIdCache = new ConcurrentHashMap<>();

    private volatile Pool pool;

    private record Pool(Instant fetchedAt, List<RawNewsItem> items) {}

    /**
     * One term of a WordPress taxonomy (tag or category) with the number of
     * posts filed under it - the shape both {@link #tags} and
     * {@link #categories} answer in.
     */
    public record Term(int id, String name, String slug, int count) {}

    /** Test/default: plain direct transport (the endpoint carries no wall). */
    public KapitalmarktexpertenClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@link DirectFirst} binding - an open, keyless JSON API
     * with no wall of any kind is exactly the case that annotation describes.
     */
    @Inject
    public KapitalmarktexpertenClient(@DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return SOURCE;
    }

    /** Articles, not forum posts - this source never rides the sentiment fan. */
    @Override
    public boolean socialSentiment() {
        return false;
    }

    /**
     * No-op: the site tags no ticker symbols. It carries ISINs (see
     * {@link #newsForIsin}) and company-name tags; feeding a bare symbol into
     * the full-text search would answer every passing mention of a two- or
     * three-letter word.
     */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /**
     * By name: tag route first (the site's own "this piece is about X"), search
     * route behind the title-precision filter as the fallback for untagged
     * companies. Every item is marked as generated second-hand copy.
     */
    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        List<RawNewsItem> out = new ArrayList<>();
        int tagId = resolveTagId(companyName);
        if (tagId > 0) {
            out.addAll(fetchPosts("tags=" + tagId + "&per_page=" + Math.min(limit, PAGE_SIZE)
                    + "&_fields=" + FIELDS_FULL));
        }
        if (out.size() < limit) {
            for (RawNewsItem it : fetchPosts("search=" + enc(companyName)
                    + "&per_page=" + SEARCH_FETCH_SIZE + "&_fields=" + FIELDS_FULL)) {
                if (titleMatches(it.title(), words)) out.add(it);
            }
        }
        return dedupe(out).stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * ARCHIVE window - the main door of this source. {@code after}/{@code
     * before} narrow server-side, the exact {@code [from, to)} cut runs on
     * {@code date_gmt} client-side (the server window is site-local time).
     *
     * <p>Route choice: the tag route when the company has a tag (precise, no
     * title guessing), the search route otherwise. When an {@code isin} is
     * given it acts as an additional ACCEPT on the search route - an exact
     * field hit is kept even if the headline abbreviates the name away.
     */
    @Override
    public List<RawNewsItem> newsForNameWindow(String companyName, String isin,
                                               String fromIsoDate, String toIsoDateExclusive,
                                               int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Instant from = startOfDay(fromIsoDate);
        Instant toExcl = startOfDay(toIsoDateExclusive);
        if (from == null || toExcl == null || !from.isBefore(toExcl)) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        int tagId = resolveTagId(companyName);
        String selector = tagId > 0
                ? "tags=" + tagId
                : "search=" + enc(companyName);
        String window = "&after=" + fromIsoDate.strip() + "T00:00:00"
                + "&before=" + toIsoDateExclusive.strip() + "T00:00:00";

        List<RawNewsItem> out = new ArrayList<>();
        for (int page = 1; page <= MAX_WINDOW_PAGES && out.size() < limit; page++) {
            List<RawNewsItem> batch = fetchPosts(selector + window
                    + "&per_page=" + PAGE_SIZE + "&page=" + page
                    + "&_fields=" + FIELDS_FULL);
            if (batch.isEmpty()) break;
            for (RawNewsItem it : batch) {
                Instant at = it.publishedAt();
                if (at == null || at.isBefore(from) || !at.isBefore(toExcl)) continue;
                boolean exactIsin = isin != null && !isin.isBlank()
                        && isin.strip().equalsIgnoreCase(it.isin());
                if (tagId <= 0 && !exactIsin && !titleMatches(it.title(), words)) continue;
                out.add(it);
            }
            if (batch.size() < PAGE_SIZE) break;
        }
        List<RawNewsItem> result = dedupe(out).stream()
                .sorted(BY_RECENCY).limit(limit).toList();
        if (!result.isEmpty()) {
            LOG.info("[{}] '{}' → {} generated piece(s) in {}..{} (via {})", SOURCE,
                    companyName, result.size(), fromIsoDate, toIsoDateExclusive,
                    tagId > 0 ? "tag " + tagId : "search");
        }
        return result;
    }

    /**
     * By ISIN, EXACTLY - the site's {@code isin} field is compared verbatim, no
     * fuzzy name matching involved. Answered from the shared recent pool, so
     * this costs no fetch of its own beyond the pool refresh; it therefore
     * reaches as far back as the pool does (~5 days). For deeper history the
     * ISIN belongs into {@link #newsForNameWindow} alongside the name.
     *
     * <p>Note the probed trap this method exists to route around:
     * {@code ?search=<ISIN>} answers 0 hits - the field is not indexed for
     * full-text search.
     */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        if (isin == null || limit <= 0) return List.of();
        String wanted = isin.strip().toUpperCase(Locale.ROOT);
        if (!ISIN.matcher(wanted).matches()) return List.of();
        return pool().stream()
                .filter(it -> wanted.equalsIgnoreCase(it.isin()))
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    // ---------------------------------------------------------------
    // General (instrument-free) newsroom surface
    // ---------------------------------------------------------------

    /**
     * The chronological firehose - the site's whole output, newest first,
     * without any instrument filter. Served from the shared TTL pool, so
     * repeated calls inside {@link #POOL_TTL} are free. Replaces the useless
     * {@code /feed/} (10 items / 2,6 h).
     */
    public List<RawNewsItem> latest(int limit) {
        if (limit <= 0) return List.of();
        return pool().stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * The stream of one TAG, newest first ({@code posts?tags=<id>}). With 4.616
     * tags, most of them company names, this is the site's own topical index -
     * see {@link #tags} for resolving a name or a keyword to an id.
     */
    public List<RawNewsItem> byTag(int tagId, int limit) {
        if (tagId <= 0 || limit <= 0) return List.of();
        return fetchPosts("tags=" + tagId + "&per_page=" + Math.min(limit, PAGE_SIZE)
                + "&_fields=" + FIELDS_SLIM).stream()
                .sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * The stream of one CATEGORY, newest first ({@code posts?categories=<id>}) -
     * the editorial rubrics (Analystenstimmen, Earnings, Übernahmen,
     * Marktberichte …). This is the working replacement for the per-category
     * RSS feeds, which answer 410 Gone.
     */
    public List<RawNewsItem> byCategory(int categoryId, int limit) {
        if (categoryId <= 0 || limit <= 0) return List.of();
        return fetchPosts("categories=" + categoryId + "&per_page="
                + Math.min(limit, PAGE_SIZE) + "&_fields=" + FIELDS_SLIM).stream()
                .sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * The TAG index, most-used first, optionally narrowed by a search term.
     * 4.616 terms, the bulk of them company names with a post count attached -
     * an instrument roster in its own right, and the lookup behind the precise
     * tag route.
     *
     * @param search substring to narrow by, or {@code null}/blank for the head
     *               of the whole index
     */
    public List<Term> tags(String search, int limit) {
        if (limit <= 0) return List.of();
        String q = search == null || search.isBlank() ? "" : "search=" + enc(search) + "&";
        return fetchTerms("tags?" + q + "per_page=" + Math.min(limit, PAGE_SIZE)
                + "&orderby=count&order=desc&_fields=id,name,slug,count");
    }

    /** The CATEGORY index (74 rubrics), most-used first. */
    public List<Term> categories(int limit) {
        if (limit <= 0) return List.of();
        return fetchTerms("categories?per_page=" + Math.min(limit, PAGE_SIZE)
                + "&orderby=count&order=desc&_fields=id,name,slug,count");
    }

    // ---------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------

    /** The recent pool, refreshed at most once per {@link #POOL_TTL}. */
    private List<RawNewsItem> pool() {
        Pool p = pool;
        if (p != null && p.fetchedAt().plus(POOL_TTL).isAfter(Instant.now())) {
            return p.items();
        }
        synchronized (this) {
            p = pool;
            if (p != null && p.fetchedAt().plus(POOL_TTL).isAfter(Instant.now())) {
                return p.items();
            }
            List<RawNewsItem> merged = new ArrayList<>();
            for (int page = 1; page <= POOL_PAGES; page++) {
                List<RawNewsItem> batch = fetchPosts("per_page=" + PAGE_SIZE
                        + "&page=" + page + "&_fields=" + FIELDS_SLIM);
                merged.addAll(batch);
                if (batch.size() < PAGE_SIZE) break;
            }
            List<RawNewsItem> deduped = dedupe(merged);
            if (deduped.isEmpty() && p != null) {
                deduped = p.items(); // outage: a stale pool beats an empty one
            }
            pool = new Pool(Instant.now(), deduped);
            return deduped;
        }
    }

    /**
     * Company name → tag id, memoised (including the misses). Exact name match
     * wins; otherwise the most specific tag whose own words are all contained
     * in the queried name - so "Siemens" never resolves to "Siemens Energy",
     * while "Siemens Energy AG" does.
     */
    int resolveTagId(String companyName) {
        String key = normalize(companyName).strip();
        Integer cached = tagIdCache.get(key);
        if (cached != null) return cached;
        int resolved = pickTag(tags(companyName, 10), companyName);
        tagIdCache.put(key, resolved);
        return resolved;
    }

    /** The best tag for a queried name, or {@code -1}. Package-private for tests. */
    static int pickTag(List<Term> candidates, String companyName) {
        if (candidates == null || candidates.isEmpty()) return -1;
        String wanted = normalize(companyName).strip();
        Set<String> queryWords = significantWords(companyName);
        Term best = null;
        int bestWords = 0;
        for (Term t : candidates) {
            if (t.name() == null) continue;
            if (normalize(t.name()).strip().equals(wanted)) return t.id();
            Set<String> tagWords = significantWords(t.name());
            if (tagWords.isEmpty() || !queryWords.containsAll(tagWords)) continue;
            if (best == null || tagWords.size() > bestWords
                    || (tagWords.size() == bestWords && t.count() > best.count())) {
                best = t;
                bestWords = tagWords.size();
            }
        }
        return best == null ? -1 : best.id();
    }

    /** One {@code /posts} request; any failure answers empty, never throws. */
    private List<RawNewsItem> fetchPosts(String query) {
        String body = get("posts?" + query);
        return body == null ? List.of() : parsePosts(body);
    }

    /** One taxonomy request; any failure answers empty, never throws. */
    private List<Term> fetchTerms(String path) {
        String body = get(path);
        return body == null ? List.of() : parseTerms(body);
    }

    private String get(String path) {
        String url = BASE + path;
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp == null || resp.status() != 200 || resp.body() == null) {
                LOG.debug("[{}] {} answered status {}", SOURCE, path,
                        resp == null ? "null" : resp.status());
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            LOG.debug("[{}] {} failed: {}", SOURCE, path, e.getMessage());
            return null;
        }
    }

    /**
     * The {@code wp/v2/posts} array → items, unfiltered (precision and window
     * cuts belong to the fans). {@code title.rendered} and the body arrive as
     * rendered HTML, so tags are stripped and entities decoded here.
     * {@code isin} is the site's top-level field and travels straight into
     * {@link RawNewsItem#isin()}. A non-array answer (WP error object, HTML
     * shell) or garbage yields empty, never throws. Package-private for tests.
     */
    static List<RawNewsItem> parsePosts(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isArray()) return List.of();
            for (JsonNode post : root) {
                String title = stripHtml(post.path("title").path("rendered").asText(null));
                String link = post.path("link").asText(null);
                if (title == null || title.isBlank() || link == null || link.isBlank()) continue;
                String id = post.path("id").asText("");
                out.add(new RawNewsItem(
                        id.isEmpty() ? link : SOURCE + "-" + id,
                        title,
                        PUBLISHER,
                        link,
                        parseStamp(post.path("date_gmt").asText(null)),
                        List.of(),
                        blankToNull(post.path("isin").asText(null)),
                        leadText(post),
                        false));
            }
        } catch (Exception e) {
            LOG.debug("[{}] post parse failed: {}", SOURCE, e.getMessage());
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * A taxonomy array ({@code /tags}, {@code /categories}) → terms. Garbage
     * yields empty. Package-private for tests.
     */
    static List<Term> parseTerms(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Term> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isArray()) return List.of();
            for (JsonNode t : root) {
                String name = stripHtml(t.path("name").asText(null));
                if (name == null || name.isBlank()) continue;
                out.add(new Term(t.path("id").asInt(), name,
                        t.path("slug").asText(null), t.path("count").asInt()));
            }
        } catch (Exception e) {
            LOG.debug("[{}] term parse failed: {}", SOURCE, e.getMessage());
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * The teaser: the article body's opening, capped at {@link #SUMMARY_MAX}
     * on a sentence boundary where possible - preferred over the WP excerpt,
     * which is hard-truncated mid-word with an ellipsis entity. Falls back to
     * the excerpt when the body wasn't requested (slim projection).
     */
    static String leadText(JsonNode post) {
        String body = stripHtml(post.path("content").path("rendered").asText(null));
        if (body == null || body.isBlank()) {
            return blankToNull(stripHtml(post.path("excerpt").path("rendered").asText(null)));
        }
        if (body.length() <= SUMMARY_MAX) return body;
        String cut = body.substring(0, SUMMARY_MAX);
        int stop = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf("? "));
        return stop > SUMMARY_MAX / 2 ? cut.substring(0, stop + 1).strip() : cut.strip() + "…";
    }

    /** Dedupes by item id, first occurrence wins. Package-private for tests. */
    static List<RawNewsItem> dedupe(List<RawNewsItem> items) {
        Map<String, RawNewsItem> byId = new LinkedHashMap<>();
        for (RawNewsItem it : items) byId.putIfAbsent(it.uuid(), it);
        return List.copyOf(byId.values());
    }

    /** {@code date_gmt} ("2026-08-02T20:12:05", zoneless UTC) → Instant; bad input → null. */
    static Instant parseStamp(String dateGmt) {
        if (dateGmt == null || dateGmt.isBlank()) return null;
        try {
            return LocalDateTime.parse(dateGmt.strip()).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    /** ISO date → UTC start of day; unparseable → null. */
    static Instant startOfDay(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return null;
        try {
            return LocalDate.parse(isoDate.strip()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the title carries at least one significant word of the queried name. */
    static boolean titleMatches(String title, Set<String> nameWords) {
        if (title == null || nameWords.isEmpty()) return false;
        String t = normalize(title);
        for (String w : nameWords) {
            if (t.matches(".*\\b" + Pattern.quote(w) + "\\b.*")) return true;
        }
        return false;
    }

    /** Significant (length ≥ 3, non-generic) words of a name, umlaut-normalised. */
    static Set<String> significantWords(String name) {
        Set<String> out = new LinkedHashSet<>();
        if (name == null) return out;
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    /** Rendered HTML → plain text: tags out, entities decoded, whitespace collapsed. */
    static String stripHtml(String html) {
        if (html == null) return null;
        return decodeEntities(html.replaceAll("<[^>]+>", " "))
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * HTML entities → text: numeric/hex first, then the named set WordPress
     * actually emits in {@code rendered} fields, {@code &amp;} last.
     */
    static String decodeEntities(String s) {
        if (s == null) return null;
        String out = NUMERIC_ENTITY.matcher(s).replaceAll(
                (MatchResult m) -> new String(Character.toChars(Integer.parseInt(m.group(1)))));
        out = HEX_ENTITY.matcher(out).replaceAll(
                (MatchResult m) -> new String(Character.toChars(Integer.parseInt(m.group(1), 16))));
        return out
                .replace("&hellip;", "…").replace("&ndash;", "–").replace("&mdash;", "—")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#039;", "'").replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s.strip(), StandardCharsets.UTF_8);
    }
}
