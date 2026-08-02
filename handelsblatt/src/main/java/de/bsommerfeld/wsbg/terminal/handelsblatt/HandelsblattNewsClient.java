package de.bsommerfeld.wsbg.terminal.handelsblatt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handelsblatt AND WirtschaftsWoche - the German business-press leg, read
 * through the publisher's own headless CMS API (probed 2026-08-02). Both
 * mastheads run the identical API on two hosts, so they are two LEGS of one
 * client rather than two modules; see {@link HandelsblattBrand}.
 *
 * <p>Everything below is keyless: the {@code content.www.*} hosts answer
 * without a {@code User-Agent}, without a cookie and without a {@code Referer},
 * and serve no {@code robots.txt} at all.
 *
 * <h2>The surfaces</h2>
 * <ul>
 *   <li><b>Ticker</b> {@code /api/ticker/} - the 100 newest pieces of a
 *       masthead with their {@code contentAccessCategory}. The best standing
 *       poll there is, and the pooled firehose behind {@link #latest(int)} and
 *       every name query (one TTL-cached fetch per brand, never per query).</li>
 *   <li><b>Topic pages</b> {@code /api/content/eager/?url=/themen/<slug>} -
 *       1.411 curated topics at Handelsblatt, 937 at WirtschaftsWoche, companies
 *       AND people, 50 teasers per page and roughly 300 pieces / 15 months deep.
 *       An ENTITY match on the topic slug beats any full-text search, so a name
 *       query walks this door first ({@link #topicSlugs(HandelsblattBrand)},
 *       {@link #topicTeasers(HandelsblattBrand, String, int, int)}).</li>
 *   <li><b>Search</b> {@code /api/search/?searchTerm=…&page=n} - 16 hits per
 *       page ("Siemens" answers {@code count: 590}). ⚠️ The index is a ROLLING
 *       12-MONTH WINDOW and {@code fromDate} is silently ignored: the last page
 *       of a 590-hit query ends at exactly today minus twelve months. The
 *       archive fan therefore refuses windows that lie entirely behind that
 *       horizon instead of burning 37 pages on nothing.</li>
 *   <li><b>Section RSS</b> {@code feeds.cms.*} - a cheap per-section stream
 *       ({@link #section(String, int)}) carrying
 *       {@code <media:category label="accessCategory">}. The {@code dpa} feed is
 *       100 items and 100 % free.</li>
 *   <li><b>Article</b> {@code /api/content/eager/} + {@code lazy/story/} -
 *       classification and body, see below.</li>
 * </ul>
 *
 * <h2>The wall - a deliberate house rule</h2>
 * {@code meta.classification} is {@code FREE}, {@code METERED} or
 * {@code PREMIUM}. Technically {@code lazy/story} hands out the COMPLETE text of
 * a {@code METERED} piece as ordinary {@code isPaywalled: false} paragraphs -
 * the metering happens in the client. <b>This house does not take it.</b>
 * {@link #article(String)} reads a body only for {@code FREE}; {@code METERED}
 * is treated exactly like {@code PREMIUM}: headline, kicker, lead text and the
 * editorial ISIN tags yes, body no. That is a decision of the project owner,
 * not an oversight - do not "fix" it.
 *
 * <p>Gated items keep their headline on every teaser surface but are published
 * under the brand's {@link HandelsblattBrand#gatedPublisher()} byline, so a
 * downstream full-text reader can skip the body fetch and keep the signal.
 *
 * <h2>The ISIN bonus</h2>
 * Article bodies carry ISINs the newsroom LINKED itself
 * ({@code <a href="/boerse/isin/GB0009895292" class="vhb-stock-icon">}) - an
 * editorial instrument tag, not a fuzzy match. {@link #extractIsins(String)}
 * lifts them; non-ISIN-shaped internal ids ({@code /boerse/isin/S11435}, an
 * unlisted or synthetic entity) are dropped.
 *
 * <p><b>Standing but unused endpoints</b> (reachable, documented, not wired):
 * {@code market.www.handelsblatt.com/api/isin/<ISIN>} lives in
 * {@link HandelsblattMarketClient} (price, sector, EPS, dividend, 1W-5Y
 * performance) and is NOT a {@link NewsSource};
 * {@code /api/channelizer/} answers 401 and needs a key we do not have.
 */
@Singleton
public class HandelsblattNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(HandelsblattNewsClient.class);

    static final String TICKER_PATH = "/api/ticker/";
    static final String SEARCH_PATH = "/api/search/?searchTerm=%s&page=%d";
    static final String EAGER_PATH = "/api/content/eager/?url=%s";
    static final String LAZY_STORY_PATH = "/api/content/lazy/story/?url=%s";
    static final String TOPICS_SITEMAP_PATH = "/sitemapExternal/sitemaps_topics.xml";

    /** Only this classification may be read as a full body. See the class javadoc. */
    static final String CLASSIFICATION_FREE = "FREE";

    /** {@code contentAccessCategory} of a piece that carries no wall. */
    static final String ACCESS_FREE = "NONE";

    /**
     * The section feeds of both mastheads, verified live 2026-08-02
     * ({@code <brand-key>/<section>} → label). Keys are what
     * {@link #section(String, int)} takes; the map is public through
     * {@link #feeds()} so a caller can mobilise any of them without touching
     * this class. {@code handelsblatt/dpa} is the agency wire - 100 items, all
     * free.
     */
    static final Map<String, String> FEEDS = Map.ofEntries(
            Map.entry("handelsblatt/schlagzeilen", "Handelsblatt Schlagzeilen"),
            Map.entry("handelsblatt/dpa", "Handelsblatt dpa-Wire"),
            Map.entry("handelsblatt/finanzen", "Handelsblatt Finanzen"),
            Map.entry("handelsblatt/unternehmen", "Handelsblatt Unternehmen"),
            Map.entry("handelsblatt/wirtschaft", "Handelsblatt Wirtschaft"),
            Map.entry("handelsblatt/politik", "Handelsblatt Politik"),
            Map.entry("handelsblatt/technologie", "Handelsblatt Technologie"),
            Map.entry("handelsblatt/energie", "Handelsblatt Energie"),
            Map.entry("wiwo/schlagzeilen", "WirtschaftsWoche Schlagzeilen"),
            Map.entry("wiwo/unternehmen", "WirtschaftsWoche Unternehmen"),
            Map.entry("wiwo/finanzen", "WirtschaftsWoche Finanzen"),
            Map.entry("wiwo/politik", "WirtschaftsWoche Politik"),
            Map.entry("wiwo/technologie", "WirtschaftsWoche Technologie"),
            Map.entry("wiwo/erfolg", "WirtschaftsWoche Erfolg"));

    /** Teaser types that point at a player, not at an article. */
    private static final Set<String> NON_ARTICLE_TEASERS = Set.of("podcastteaser", "videoteaser");

    private static final Duration POOL_TTL = Duration.ofMinutes(10);
    /** The topic catalogue changes by the week, not by the minute. */
    private static final Duration TOPICS_TTL = Duration.ofHours(12);

    /** The search index reaches exactly one year back - the API ignores {@code fromDate}. */
    static final Duration SEARCH_HORIZON = Duration.ofDays(366);

    /** Pages an archive walk may spend per brand (16 hits/search page, 50/topic page). */
    private static final int MAX_ARCHIVE_PAGES = 12;

    /** Generic words that must never carry the title-relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "der", "die", "das", "und", "inc", "incorporated", "corp",
            "corporation", "co", "company", "ag", "kgaa", "gmbh", "se", "plc", "ltd",
            "limited", "nv", "sa", "spa", "holding", "holdings", "group", "gruppe",
            "konzern", "international", "stock", "stocks", "shares", "aktie", "aktien");

    private static final Pattern ISIN_LINK =
            Pattern.compile("/boerse/isin/([A-Za-z0-9]+)");
    private static final Pattern ISIN_SHAPE =
            Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Newest first; items without a timestamp sort to the end. */
    private static final Comparator<RawNewsItem> BY_RECENCY =
            Comparator.comparing(RawNewsItem::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    /** {@code Sun, 02 Aug 2026 14:49:58 GMT} - the RSS leg. */
    private static final DateTimeFormatter RSS_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    private volatile Cached pool;
    private final Map<HandelsblattBrand, CachedSlugs> topicCache =
            new EnumMap<>(HandelsblattBrand.class);

    private record Cached(Instant fetchedAt, List<RawNewsItem> items) {}

    private record CachedSlugs(Instant fetchedAt, Set<String> slugs) {}

    /** Test/default: plain direct transport - the API needs no browser session. */
    public HandelsblattNewsClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: rides the shared standard {@link WebFetcher} chain. No wall
     * was seen on any of the probed surfaces, but the chain costs nothing at
     * this cadence and survives one growing.
     */
    @Inject
    public HandelsblattNewsClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "handelsblatt";
    }

    // ---------------------------------------------------------------- fans

    /**
     * No-op: neither masthead tags ticker symbols anywhere. Feeding a bare
     * symbol into a German full-text search answers every passing mention of a
     * short word, so the name and topic doors carry this source.
     */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /**
     * No-op on the news surface: the teaser feeds carry no ISIN. The editorial
     * ISIN tags live INSIDE article bodies and are lifted per article by
     * {@link #article(String)}; the instrument master data behind an ISIN is
     * {@link HandelsblattMarketClient}'s job.
     */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        return List.of();
    }

    /**
     * By NAME, three doors merged freshest-first: the TTL-cached ticker pool of
     * both mastheads (costs no fetch), the curated TOPIC page when the name maps
     * onto a topic slug (the precise entity door), and one live search page per
     * masthead for the long tail. Everything is cut against the house
     * title-precision filter afterwards.
     */
    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        List<RawNewsItem> merged = new ArrayList<>(pool().stream()
                .filter(it -> titleMatches(haystack(it), words))
                .toList());

        for (HandelsblattBrand brand : HandelsblattBrand.values()) {
            String slug = topicSlugFor(brand, companyName);
            if (slug != null) merged.addAll(topicTeasers(brand, slug, 1, limit));
            merged.addAll(searchPage(brand, companyName, 1).stream()
                    .filter(it -> titleMatches(haystack(it), words))
                    .toList());
        }
        return mergeByLink(merged, List.of()).stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /**
     * ARCHIVE window. The topic page is walked first where a slug exists (50
     * teasers per page, strictly chronological, ~15 months deep); the search
     * leg fills in for names without a topic.
     *
     * <p>⚠️ Both legs are bounded by the publisher's rolling
     * {@link #SEARCH_HORIZON} of twelve months - {@code fromDate} is ignored by
     * the API, so a window that ends BEFORE the horizon is answered with an
     * empty list instead of dozens of pointless fetches. A window that straddles
     * the horizon is served for its reachable part.
     */
    @Override
    public List<RawNewsItem> newsForNameWindow(String companyName, String isin,
                                               String fromIsoDate, String toIsoDateExclusive,
                                               int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Instant from = startOfDay(fromIsoDate);
        Instant toExcl = startOfDay(toIsoDateExclusive);
        if (from == null || toExcl == null || !from.isBefore(toExcl)) return List.of();
        Instant horizon = Instant.now().minus(SEARCH_HORIZON);
        if (!toExcl.isAfter(horizon)) return List.of(); // entirely behind the wall of time
        if (from.isBefore(horizon)) from = horizon;     // serve the reachable part

        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        List<RawNewsItem> out = new ArrayList<>();
        for (HandelsblattBrand brand : HandelsblattBrand.values()) {
            String slug = topicSlugFor(brand, companyName);
            Set<String> seen = new LinkedHashSet<>();
            for (int page = 1; page <= MAX_ARCHIVE_PAGES; page++) {
                List<RawNewsItem> batch = slug != null
                        ? topicTeasers(brand, slug, page, Integer.MAX_VALUE)
                        : searchPage(brand, companyName, page);
                if (batch.isEmpty()) break;
                boolean fresh = false;
                for (RawNewsItem it : batch) fresh |= seen.add(cleanLink(it.link()));
                if (!fresh) break; // the pager ran out and started repeating itself
                boolean pagedPast = false;
                for (RawNewsItem it : batch) {
                    Instant at = it.publishedAt();
                    if (at == null) continue;
                    if (at.isBefore(from)) {
                        pagedPast = true;
                        continue;
                    }
                    if (!at.isBefore(toExcl)) continue; // still newer than the window
                    if (titleMatches(haystack(it), words)) out.add(it);
                }
                if (pagedPast) break;
            }
        }
        return mergeByLink(out, List.of()).stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    // ------------------------------------------------- general news stream

    /**
     * The GENERAL stream, instrument-free: the merged {@code /api/ticker/} poll
     * of both mastheads, newest first. Same TTL-cached pool the name fan uses,
     * so calling it costs no extra fetch.
     */
    public List<RawNewsItem> latest(int limit) {
        if (limit <= 0) return List.of();
        return pool().stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * One section RSS feed by its key from {@link #feeds()}, e.g.
     * {@code handelsblatt/dpa} or {@code wiwo/finanzen}. Unknown keys of the
     * shape {@code <brand>/<section>} are fetched anyway - the publisher runs
     * more sections than this map names, and a caller must not have to edit
     * this class to reach one.
     */
    public List<RawNewsItem> section(String feedKey, int limit) {
        if (feedKey == null || feedKey.isBlank() || limit <= 0) return List.of();
        int slash = feedKey.indexOf('/');
        if (slash <= 0 || slash == feedKey.length() - 1) return List.of();
        HandelsblattBrand brand = brandByKey(feedKey.substring(0, slash));
        if (brand == null) return List.of();
        String sectionKey = feedKey.substring(slash + 1).trim();
        if (sectionKey.isEmpty()) return List.of();

        String body = get(brand.feedUrl(sectionKey), "application/rss+xml, application/xml");
        return parseFeed(body, brand).stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /** The section feeds this source knows, {@code brand/section} → label. */
    public Map<String, String> feeds() {
        return FEEDS;
    }

    /** The mastheads this source serves - both run the identical API. */
    public List<HandelsblattBrand> brands() {
        return List.of(HandelsblattBrand.values());
    }

    /**
     * The masthead's curated TOPIC slugs from
     * {@code sitemapExternal/sitemaps_topics.xml} - 1.411 at Handelsblatt, 937
     * at WirtschaftsWoche, companies and people alike (probed 2026-08-02).
     * Cached for {@link #TOPICS_TTL}; the catalogue is the precise entity index
     * of this publisher and is public on purpose.
     */
    public Set<String> topicSlugs(HandelsblattBrand brand) {
        if (brand == null) return Set.of();
        synchronized (topicCache) {
            CachedSlugs c = topicCache.get(brand);
            if (c != null && c.fetchedAt().plus(TOPICS_TTL).isAfter(Instant.now())) {
                return c.slugs();
            }
            Set<String> slugs = parseTopicSitemap(
                    get(brand.siteBase() + TOPICS_SITEMAP_PATH, "application/xml"));
            if (slugs.isEmpty() && c != null) slugs = c.slugs(); // outage: keep the catalogue
            topicCache.put(brand, new CachedSlugs(Instant.now(), slugs));
            return slugs;
        }
    }

    /**
     * The teasers of one topic page, {@code page} 1-based (50 per page, roughly
     * six pages / 15 months deep). The precise instrument door: a hit here was
     * assigned by the newsroom, not guessed by a matcher.
     */
    public List<RawNewsItem> topicTeasers(HandelsblattBrand brand, String slug, int page, int limit) {
        if (brand == null || slug == null || slug.isBlank() || limit <= 0) return List.of();
        String path = "/themen/" + slug.trim() + (page > 1 ? "?page=" + page : "");
        String body = get(brand.apiBase() + String.format(EAGER_PATH, encode(path)),
                "application/json");
        List<RawNewsItem> items = parseTeaserPage(body, brand);
        return limit == Integer.MAX_VALUE ? items : items.stream().limit(limit).toList();
    }

    /**
     * One raw SEARCH page (16 hits), unfiltered - the thematic door for queries
     * that are not a company name. Remember the rolling twelve-month index.
     */
    public List<RawNewsItem> search(HandelsblattBrand brand, String query, int page, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        List<RawNewsItem> items = searchPage(brand, query, Math.max(1, page));
        return items.stream().limit(limit).toList();
    }

    // ------------------------------------------------------------ article

    /**
     * Reads one article by its permalink or CMS path.
     *
     * <p>One {@code eager} fetch always happens - it carries
     * {@code meta.classification}, the headline, the lead text and the editorial
     * ISIN links. The second fetch ({@code lazy/story}) happens ONLY for
     * {@code FREE}. {@code METERED} is deliberately treated like {@code PREMIUM}
     * even though the endpoint would hand the text over: that text is behind a
     * client-side meter, and walking around a meter is not this house's
     * business. See the class javadoc - this is intentional, not a bug.
     *
     * @return the article, or {@code null} when the piece could not be read
     */
    public HandelsblattArticle article(String linkOrPath) {
        if (linkOrPath == null || linkOrPath.isBlank()) return null;
        HandelsblattBrand brand = brandOf(linkOrPath);
        String path = pathOf(linkOrPath);
        String eager = get(brand.apiBase() + String.format(EAGER_PATH, encode(path)),
                "application/json");
        if (eager == null) return null;
        try {
            JsonNode root = MAPPER.readTree(eager);
            JsonNode meta = root.path("meta");
            String classification = text(meta, "classification");
            String access = text(meta, "contentAccessCategory");
            JsonNode header = root.path("header");
            String headline = text(header, "headline");
            String lead = stripHtml(text(header, "leadText"));
            List<String> isins = extractIsins(elementsHtml(root.path("elements")));

            String text = null;
            if (CLASSIFICATION_FREE.equalsIgnoreCase(classification)) {
                String lazy = get(brand.apiBase() + String.format(LAZY_STORY_PATH, encode(path)),
                        "application/json");
                if (lazy != null) {
                    JsonNode elements = MAPPER.readTree(lazy).path("elements");
                    text = blankToNull(stripHtml(elementsHtml(elements)));
                    List<String> bodyIsins = extractIsins(elementsHtml(elements));
                    if (!bodyIsins.isEmpty()) {
                        Set<String> all = new LinkedHashSet<>(isins);
                        all.addAll(bodyIsins);
                        isins = List.copyOf(all);
                    }
                }
            }
            return new HandelsblattArticle(brand.siteBase() + path, classification, access,
                    headline, lead, isins, text);
        } catch (Exception e) {
            LOG.debug("Unreadable Handelsblatt article {}: {}", path, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------- innards

    /** The merged, cached ticker pool of both mastheads; refreshed once per TTL. */
    private List<RawNewsItem> pool() {
        Cached c = pool;
        if (c != null && c.fetchedAt().plus(POOL_TTL).isAfter(Instant.now())) return c.items();
        synchronized (this) {
            c = pool;
            if (c != null && c.fetchedAt().plus(POOL_TTL).isAfter(Instant.now())) return c.items();
            List<RawNewsItem> merged = new ArrayList<>();
            for (HandelsblattBrand brand : HandelsblattBrand.values()) {
                merged.addAll(parseTicker(
                        get(brand.apiBase() + TICKER_PATH, "application/json"), brand));
            }
            List<RawNewsItem> deduped = mergeByLink(merged, List.of());
            if (deduped.isEmpty() && c != null) {
                deduped = c.items(); // outage: a stale pool beats an empty one
            }
            pool = new Cached(Instant.now(), deduped);
            return deduped;
        }
    }

    private List<RawNewsItem> searchPage(HandelsblattBrand brand, String query, int page) {
        String url = brand.apiBase() + String.format(SEARCH_PATH,
                URLEncoder.encode(query, StandardCharsets.UTF_8), Math.max(1, page));
        return parseTeaserPage(get(url, "application/json"), brand);
    }

    /** The topic slug for a company name, or {@code null} when the masthead has none. */
    private String topicSlugFor(HandelsblattBrand brand, String companyName) {
        String slug = slugify(companyName);
        if (slug.isEmpty()) return null;
        return topicSlugs(brand).contains(slug) ? slug : null;
    }

    private String get(String url, String accept) {
        try {
            WebResponse resp = fetcher.fetch(url, Map.of("Accept", accept), requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("Handelsblatt {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("Handelsblatt {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    private static HandelsblattBrand brandByKey(String key) {
        for (HandelsblattBrand b : HandelsblattBrand.values()) {
            if (b.key().equalsIgnoreCase(key.trim())) return b;
        }
        return null;
    }

    /** Which masthead a link belongs to; Handelsblatt is the default. */
    static HandelsblattBrand brandOf(String link) {
        return link != null && link.contains("wiwo.de")
                ? HandelsblattBrand.WIWO : HandelsblattBrand.HANDELSBLATT;
    }

    /** The CMS path of a permalink ({@code https://www.wiwo.de/a/b.html} → {@code /a/b.html}). */
    static String pathOf(String link) {
        if (link == null) return "";
        String v = link.trim();
        int scheme = v.indexOf("://");
        if (scheme >= 0) {
            int slash = v.indexOf('/', scheme + 3);
            v = slash < 0 ? "/" : v.substring(slash);
        }
        return v.startsWith("/") ? v : "/" + v;
    }

    // ------------------------------------------------------------- parsers

    /**
     * The {@code /api/ticker/} payload - a bare ARRAY of 100 teasers.
     * Package-private for tests.
     */
    static List<RawNewsItem> parseTicker(String json, HandelsblattBrand brand) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return teasers(MAPPER.readTree(json), brand);
        } catch (Exception e) {
            LOG.warn("Unparseable Handelsblatt ticker: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * A page carrying a {@code teasers[]} array - both {@code /api/search/} and
     * the {@code eager} topic page use the identical teaser shape.
     * Package-private for tests.
     */
    static List<RawNewsItem> parseTeaserPage(String json, HandelsblattBrand brand) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return teasers(MAPPER.readTree(json).path("teasers"), brand);
        } catch (Exception e) {
            LOG.warn("Unparseable Handelsblatt teaser page: {}", e.getMessage());
            return List.of();
        }
    }

    /** {@code pagination.totalPages} of a search / topic page, or 0. */
    static int totalPages(String json) {
        if (json == null || json.isBlank()) return 0;
        try {
            return MAPPER.readTree(json).path("pagination").path("totalPages").asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static List<RawNewsItem> teasers(JsonNode array, HandelsblattBrand brand) {
        if (array == null || !array.isArray()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        for (JsonNode t : array) {
            String headline = text(t, "headline");
            String href = text(t.path("url"), "href");
            if (headline == null || href == null) continue;
            String type = text(t, "type");
            if (type != null && NON_ARTICLE_TEASERS.contains(type.toLowerCase(Locale.ROOT))) {
                continue; // a player has no article text
            }
            String access = textOr(t, "contentAccessCategory", ACCESS_FREE);
            String id = text(t, "id");
            String kicker = text(t, "kicker");
            String lead = stripHtml(text(t, "leadText"));
            String summary = kicker == null ? lead
                    : (lead == null ? kicker : kicker + " - " + lead);
            out.add(new RawNewsItem(
                    id != null ? id : brand.siteBase() + href,
                    headline,
                    isGated(access) ? brand.gatedPublisher() : brand.publisher(),
                    brand.siteBase() + href,
                    parseIsoInstant(text(t.path("dates"), "published")),
                    List.of(),
                    null,
                    summary,
                    false,
                    imageUrl(t.path("image"))));
        }
        return out;
    }

    /**
     * A section RSS feed. {@code <media:category label="accessCategory">} carries
     * the wall marker ({@code NONE} / {@code H_PLUS} /
     * {@code H_PLUS_PREMIUM_BUSINESS} / {@code WIWO_PLUS}).
     * Package-private for tests.
     */
    static List<RawNewsItem> parseFeed(String xml, HandelsblattBrand brand) {
        if (xml == null || xml.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null, link = null, guid = null, pubDate = null,
                    description = null, access = null, image = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = guid = pubDate = description = access = image = null;
                        } else if (inItem) {
                            switch (ln) {
                                case "title" -> title = elementText(r);
                                case "link" -> link = elementText(r);
                                case "guid" -> guid = elementText(r);
                                case "pubDate" -> pubDate = elementText(r);
                                case "description" -> description = elementText(r);
                                case "enclosure" -> image = attr(r, "url");
                                case "category" -> {
                                    boolean isAccess =
                                            "accessCategory".equals(attr(r, "label"));
                                    String value = elementText(r);
                                    if (isAccess) access = value;
                                }
                                default -> { /* creator, credit, content:encoded - not needed */ }
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT
                            && "item".equals(r.getLocalName())) {
                        inItem = false;
                        if (title == null || title.isEmpty() || link == null || link.isEmpty()) {
                            continue;
                        }
                        out.add(new RawNewsItem(
                                guid != null && !guid.isEmpty() ? guid : link,
                                title,
                                isGated(access) ? brand.gatedPublisher() : brand.publisher(),
                                link,
                                parseRssDate(pubDate),
                                List.of(),
                                null,
                                blankToNull(stripHtml(description)),
                                false,
                                blankToNull(image)));
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.warn("Unparseable Handelsblatt feed: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    /**
     * The topic sitemap → the bare slugs ({@code /themen/siemens-energy} →
     * {@code siemens-energy}). Package-private for tests.
     */
    static Set<String> parseTopicSitemap(String xml) {
        if (xml == null || xml.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            try {
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT
                            && "loc".equals(r.getLocalName())) {
                        String loc = elementText(r);
                        int marker = loc == null ? -1 : loc.indexOf("/themen/");
                        if (marker >= 0) {
                            String slug = loc.substring(marker + "/themen/".length());
                            int q = slug.indexOf('?');
                            if (q >= 0) slug = slug.substring(0, q);
                            slug = slug.replaceAll("/+$", "");
                            if (!slug.isEmpty()) out.add(slug.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.warn("Unparseable Handelsblatt topic sitemap: {}", e.getMessage());
            return Set.of();
        }
        return out;
    }

    /**
     * The ISINs the NEWSROOM linked in a piece
     * ({@code href="/boerse/isin/GB0009895292"}). Internal ids of the same shape
     * ({@code /boerse/isin/S11435} - unlisted or synthetic entities) fail the
     * ISIN shape check and are dropped. Package-private for tests.
     */
    static List<String> extractIsins(String html) {
        if (html == null || html.isEmpty()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        Matcher m = ISIN_LINK.matcher(html);
        while (m.find()) {
            String candidate = m.group(1).toUpperCase(Locale.ROOT);
            if (ISIN_SHAPE.matcher(candidate).matches()) out.add(candidate);
        }
        return List.copyOf(out);
    }

    /** Concatenated {@code content} of all storyline elements that carry one. */
    private static String elementsHtml(JsonNode elements) {
        if (elements == null || !elements.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode el : elements) {
            String content = text(el, "content");
            if (content != null) sb.append(content).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- helpers

    /** True for every access category other than {@code NONE}. */
    static boolean isGated(String accessCategory) {
        return accessCategory != null && !accessCategory.isBlank()
                && !ACCESS_FREE.equalsIgnoreCase(accessCategory.trim());
    }

    /**
     * The renderable image URL. The teaser's own {@code url.href} is a
     * deliberate decoy ({@code …/do/not/use/this/url/…}); the real one is built
     * from the image id on the CDN base, exactly as the RSS enclosures do.
     */
    static String imageUrl(JsonNode image) {
        if (image == null || image.isMissingNode()) return null;
        String id = text(image, "id");
        String base = text(image.path("baseUrl"), "href");
        if (id == null || base == null) return null;
        String name = textOr(image, "urlFriendlyTitle", "image");
        String ext = textOr(image, "fileExtension", "jpg");
        return base + "/" + id + "/cover/900/506/0/0/0/0/0.5/0.5/" + name + "." + ext;
    }

    /** Dedupes by article URL, first occurrence wins. Package-private for tests. */
    static List<RawNewsItem> mergeByLink(List<RawNewsItem> first, List<RawNewsItem> second) {
        Map<String, RawNewsItem> byLink = new LinkedHashMap<>();
        for (RawNewsItem it : first) byLink.putIfAbsent(cleanLink(it.link()), it);
        for (RawNewsItem it : second) byLink.putIfAbsent(cleanLink(it.link()), it);
        return List.copyOf(byLink.values());
    }

    /** The article URL without its tracking query - the identity/merge key. */
    static String cleanLink(String link) {
        if (link == null) return "";
        int q = link.indexOf('?');
        return q >= 0 ? link.substring(0, q) : link;
    }

    /** Headline plus kicker plus lead - the kicker often carries the company. */
    private static String haystack(RawNewsItem it) {
        return it.summary() == null ? it.title() : it.title() + " " + it.summary();
    }

    /** {@code 2026-08-02T19:26:43.706Z} → {@link Instant}; unparseable → null. */
    static Instant parseIsoInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code Sun, 02 Aug 2026 14:49:58 GMT} → {@link Instant}; unparseable → null. */
    static Instant parseRssDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return ZonedDateTime.parse(s.trim(), RSS_DATE).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** ISO date → UTC start of day; unparseable → null. */
    static Instant startOfDay(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return null;
        try {
            return LocalDate.parse(isoDate.trim()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the text carries at least one significant word of the queried name. */
    static boolean titleMatches(String text, Set<String> nameWords) {
        if (text == null || nameWords.isEmpty()) return false;
        String t = normalize(text);
        for (String w : nameWords) {
            if (t.matches("(?s).*\\b" + Pattern.quote(w) + "\\b.*")) return true;
        }
        return false;
    }

    /** Significant (length ≥ 3, non-generic) words of the queried name. */
    static Set<String> significantWords(String name) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    /** {@code Siemens Energy AG} → {@code siemens-energy} - the topic-slug shape. */
    static String slugify(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.isEmpty() || NAME_STOP.contains(w)) continue;
            if (sb.length() > 0) sb.append('-');
            sb.append(w);
        }
        return sb.toString();
    }

    /** HTML → plain text, entities decoded, whitespace collapsed. */
    static String stripHtml(String html) {
        if (html == null) return null;
        String s = HTML_TAG.matcher(html.replace("</p>", "</p>\n")).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
        return s.replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*", "\n").trim();
    }

    private static String encode(String path) {
        return URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String textOr(JsonNode n, String field, String fallback) {
        String v = text(n, field);
        return v == null ? fallback : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String attr(XMLStreamReader r, String name) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (name.equals(r.getAttributeLocalName(i))) return r.getAttributeValue(i);
        }
        return null;
    }

    private static String elementText(XMLStreamReader r) throws Exception {
        String t = r.getElementText();
        return t != null ? t.trim() : null;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
