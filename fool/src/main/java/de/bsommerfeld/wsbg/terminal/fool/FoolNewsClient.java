package de.bsommerfeld.wsbg.terminal.fool;

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

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Motley Fool as a US news/analysis leg — keyless throughout. Three
 * chronological surfaces feed the shared article pool (live-probed 2026-07-13):
 *
 * <ul>
 *   <li><b>News sitemap</b> ({@code www.fool.com/news-sitemap.xml}) — ~250
 *       articles over the last ~2 days, most carrying
 *       {@code <news:stock_tickers>} ({@code NYSE:WELL,NASDAQ:SBRA}) — the
 *       ticker-addressed join no other Fool surface offers — plus an article
 *       image. {@code robots.txt} lists it as the intended crawl surface.</li>
 *   <li><b>foolwatch RSS</b>
 *       ({@code api.fool.com/feeds/foolwatch?apikey=foolwatch-feed&format=rss2})
 *       — the editorial firehose (50 items) with a teaser {@code description}.
 *       The {@code apikey} is PUBLIC, not a leak: {@code fool.com/feeds/index.aspx}
 *       officially 301s to exactly this URL. Without it: 401.</li>
 *   <li><b>Earnings-call transcripts</b>
 *       ({@code www.fool.com/earnings-call-transcripts/}) — full transcripts as
 *       keyless articles, but in NEITHER of the two feeds above, so the listing
 *       page is scraped: each card is an {@code <h5>} title ("PepsiCo (PEP) Q2
 *       2026 Earnings Call Transcript") right after its article link, the ticker
 *       rides in the title parens and the date in the URL path. The transcript
 *       page itself opens with a structured TAKEAWAYS block — downstream readers
 *       (read-articles, a future KI-DD leg) get a model-sized digest before the
 *       full Q&A.</li>
 * </ul>
 *
 * <p>All three are fetched whole and merged into one pool (canonical-link
 * join, tracking query stripped), cached for 10 minutes — the sitemap leg
 * contributes tickers and image, the firehose leg the teaser. {@link #latest}
 * hands that pool out as the general stream.
 *
 * <p>Every chronological surface shares one blind spot: it answers "what did
 * Fool publish", never "what did Fool publish about THIS instrument" — a
 * two-day sitemap window simply drops everything older. Two per-symbol
 * surfaces close it (both live-probed 2026-08-02):
 *
 * <ul>
 *   <li><b>Quote pages</b> ({@code www.fool.com/quote/<exchange>/<symbol>/}) —
 *       Next.js server-rendered, carrying the ~20 most recent Fool articles on
 *       exactly this instrument in two places: a clean JSON-LD {@code ItemList}
 *       ({@code <script id="quote-page-related-schema">}, 10 entries with
 *       headline, date and image) and the escaped {@code self.__next_f} payload
 *       (~22 dated entries, wider but image-less). Both are read and joined —
 *       see {@link #parseQuoteArticles}. The same payload carries the
 *       instrument's own profile and fundamentals ({@link FoolQuote}).</li>
 *   <li><b>Quotes sitemap</b> ({@code www.fool.com/quotes-sitemap.xml}) — an
 *       index over {@code /quotes-sitemap/nyse.xml}, {@code /nasdaq.xml} and
 *       {@code /crypto.xml}, together ~14.700 symbol → quote-URL rows: the
 *       complete Fool ticker map ({@link #tickerMap()}). Big but near-static,
 *       hence a 12-hour TTL against the pool's 10 minutes.</li>
 * </ul>
 *
 * <p><b>Which exchange?</b> The quote path needs a venue segment the caller
 * doesn't know. {@link #quotePageFor} resolves it in three steps, cheapest
 * first: a per-symbol memo, then the ticker map <i>if it is already warm</i>
 * (authoritative, one lookup, no request), otherwise probing
 * {@code nasdaq → nyse → crypto} until one answers 200 — a wrong venue returns
 * a clean 404. The map is deliberately NOT force-loaded for a single symbol:
 * that would trade three ~700 KB page probes for four multi-megabyte sitemap
 * downloads. Callers that resolve many symbols warm it once via
 * {@link #tickerMap()} and every lookup afterwards is free.
 *
 * <p>{@link #newsFor} therefore answers from BOTH legs — the chronological pool
 * (sitemap ticker tags, transcript title tickers) and the instrument's quote
 * page — joined on the canonical link. {@link #newsForName} stays pool-only:
 * a name is not a path segment. Both are title-relevance matched by the
 * google-news precision rule (a significant word of the name must appear).
 * English-language items — same as the Yahoo leg, so nothing downstream needs
 * to care.
 *
 * <p>An outage keeps the previous pool alive for another TTL window instead of
 * caching an empty answer — partial news beats no news.
 *
 * <p>{@code robots.txt} (re-read 2026-08-02): the blanket {@code Disallow: /}
 * belongs to MauiBot and Bytespider only; {@code Disallow: /feeds} covers
 * {@code www.fool.com/feeds}, NOT the {@code api.fool.com} host this client
 * uses. Quote pages, {@code /investing/YYYY/…} articles and every sitemap read
 * here are explicitly listed or free. Off limits and never touched:
 * {@code /investing/stocks/}, {@code /premium-reports}, {@code /stock-ranker/},
 * {@code /api/historical/}, {@code /newsletters/*}.
 */
@Singleton
public class FoolNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(FoolNewsClient.class);

    static final String SITEMAP_URL = "https://www.fool.com/news-sitemap.xml";
    static final String FEED_URL =
            "https://api.fool.com/feeds/foolwatch?apikey=foolwatch-feed&format=rss2";
    static final String TRANSCRIPTS_URL = "https://www.fool.com/earnings-call-transcripts/";
    static final String QUOTES_SITEMAP_URL = "https://www.fool.com/quotes-sitemap.xml";
    static final String WAG_SITEMAP_URL = "https://www.fool.com/wag-sitemap.xml";
    static final String MONEY_SITEMAP_URL = "https://www.fool.com/money/news-sitemap.xml";
    static final String QUOTE_BASE = "https://www.fool.com/quote/";
    static final String PUBLISHER = "The Motley Fool";

    /** Probe order when no ticker map is warm — the two big venues first, crypto last. */
    static final List<String> EXCHANGES = List.of("nasdaq", "nyse", "crypto");

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /**
     * The ticker map and the evergreen index are large but near-static (the
     * legs' {@code lastmod} moves daily, their content barely) — a 12-hour
     * window keeps them out of the article pool's 10-minute rhythm.
     */
    private static final Duration MAP_TTL = Duration.ofHours(12);

    /** A resolved venue holds for a day; an unresolved symbol is retried on the pool's rhythm (a fresh listing must not stay invisible for 12 hours). */
    private static final Duration EXCHANGE_TTL = Duration.ofHours(24);

    /** Quote pages this client keeps around, before the whole map is dropped. */
    private static final int QUOTE_PAGE_CACHE_MAX = 256;

    private static final String NS_SITEMAP = "http://www.sitemaps.org/schemas/sitemap/0.9";
    private static final String NS_NEWS = "http://www.google.com/schemas/sitemap-news/0.9";
    private static final String NS_IMAGE = "http://www.google.com/schemas/sitemap-image/1.1";

    /** Generic words that must never carry the title-relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "stock", "stocks", "shares");

    /** Hardened StAX factory (XXE off — these are remote feeds), reused for every parse. */
    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    /** Newest first; items without a timestamp sort to the end. */
    private static final Comparator<RawNewsItem> BY_RECENCY =
            Comparator.comparing(RawNewsItem::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    private volatile Cached pool;
    private volatile CachedMap tickerMap;
    private volatile CachedList evergreen;
    private volatile Cached moneyPool;

    /** symbol → venue segment ({@code ""} = probed, not found anywhere). */
    private final Map<String, CachedText> exchangeMemo = new ConcurrentHashMap<>();
    /** symbol → the fetched quote page, so news and profile share one request. */
    private final Map<String, CachedPage> quotePages = new ConcurrentHashMap<>();

    private record Cached(Instant fetchedAt, List<RawNewsItem> items) {}

    private record CachedMap(Instant fetchedAt, Map<String, String> map) {}

    private record CachedList(Instant fetchedAt, List<String> items) {}

    private record CachedText(Instant fetchedAt, String value) {}

    private record CachedPage(Instant fetchedAt, String exchange, String html) {}

    /** One quote page, already resolved to its venue. {@code html} is {@code null} when the symbol has no Fool quote page. */
    record QuotePage(String symbol, String exchange, String html) {}

    private static boolean fresh(Instant fetchedAt, Duration ttl) {
        return fetchedAt != null && fetchedAt.plus(ttl).isAfter(Instant.now());
    }

    /** Test/default: plain direct transport. */
    public FoolNewsClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: rides the shared standard {@link WebFetcher} chain
     * (browser → direct) — JCEF-first is the house standard for public
     * websites; fool.com carries no wall today, but the chain costs nothing
     * (2 fetches per 10 min) and survives one growing.
     */
    @Inject
    public FoolNewsClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "fool";
    }

    /**
     * By ticker, from two legs joined on the canonical link: the chronological
     * pool (the sitemap's {@code <news:stock_tickers>} tags and the transcript
     * titles' parens) and the instrument's own quote page, which reaches weeks
     * back where the sitemap window is two days wide. The pool leads — it is
     * the leg that carries teasers and co-tickers; the quote page appends
     * whatever the pool has never seen.
     */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        if (symbol == null || symbol.isBlank() || limit <= 0) return List.of();
        String wanted = symbol.trim().toUpperCase(Locale.ROOT);
        List<RawNewsItem> fromPool = pool().stream()
                .filter(it -> it.relatedTickers().contains(wanted))
                .toList();
        return appendNew(fromPool, quoteNews(wanted, limit)).stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /**
     * By name, relevance-matched against title AND teaser over the whole
     * pool (precision over recall) — the teaser counts because Fool's
     * roundup-style headlines ("3 Growth Stocks to Buy Now") name the
     * companies only in the body text (mandate 2026-07-16: scan everything
     * a source hands over).
     */
    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();
        return pool().stream()
                .filter(it -> titleMatches(it.summary() == null
                        ? it.title() : it.title() + " " + it.summary(), words))
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /**
     * The general stream: everything Fool published across all three
     * chronological surfaces, newest first — the source's firehose without a
     * ticker or name question in front of it.
     *
     * <p>Currently unused, stands ready (mandate 2026-08-02: every source is
     * wired instrument-specific AND general).
     */
    public List<RawNewsItem> latest(int limit) {
        if (limit <= 0) return List.of();
        return pool().stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * The personal-finance stream ({@code www.fool.com/money/news-sitemap.xml}):
     * savings rates, cards, mortgages — the same news-sitemap grammar as the
     * market leg, so it parses with the same reader, but carrying no
     * {@code <news:stock_tickers>} at all. Held OUT of the market pool on
     * purpose: it answers no instrument question and would only dilute the
     * name matcher.
     *
     * <p>Currently unused, stands ready.
     */
    public List<RawNewsItem> moneyNews(int limit) {
        if (limit <= 0) return List.of();
        Cached c = moneyPool;
        if (c == null || !fresh(c.fetchedAt(), CACHE_TTL)) {
            List<RawNewsItem> items = fetchAndParse(MONEY_SITEMAP_URL, FoolNewsClient::parseSitemap);
            if (items.isEmpty() && c != null) items = c.items();
            moneyPool = c = new Cached(Instant.now(), items);
        }
        return c.items().stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * Fool's evergreen index ({@code www.fool.com/wag-sitemap.xml}, ~3.100
     * URLs): the {@code /terms/} glossary (~1.390 definition pages), evergreen
     * {@code /investing/} how-to guides (~1.200) and the {@code /research/}
     * data studies — undated reference material, which is why it lives beside
     * the news pool rather than in it. A {@code <loc>None</loc>} placeholder
     * heads the file and is dropped.
     *
     * <p>Currently unused, stands ready — the glossary is the obvious anchor
     * source for a term the model needs pinned.
     */
    public List<String> evergreenPages() {
        CachedList c = evergreen;
        if (c != null && fresh(c.fetchedAt(), MAP_TTL)) return c.items();
        synchronized (this) {
            c = evergreen;
            if (c != null && fresh(c.fetchedAt(), MAP_TTL)) return c.items();
            List<String> locs = fetchAndParseText(WAG_SITEMAP_URL, FoolNewsClient::parseSitemapLocs);
            if (locs.isEmpty() && c != null) locs = c.items();
            evergreen = new CachedList(Instant.now(), locs);
            return locs;
        }
    }

    /**
     * The complete Fool ticker map, {@code SYMBOL → quote-page URL}, read from
     * {@code quotes-sitemap.xml} and its three legs (NYSE, NASDAQ, crypto —
     * ~14.700 rows on 2026-08-02). Cached for {@code MAP_TTL}, twelve hours
     * against the article pool's ten minutes: this is megabytes of near-static
     * data, and paying for it on the news rhythm would be absurd.
     *
     * <p>Two uses beyond news: it is a universe (which symbols does Fool cover
     * at all) and the exact answer to the venue question {@link #quotePageFor}
     * otherwise has to probe for. Warming it once makes every later symbol
     * resolution free.
     *
     * <p>Currently unused, stands ready.
     */
    public Map<String, String> tickerMap() {
        CachedMap c = tickerMap;
        if (c != null && fresh(c.fetchedAt(), MAP_TTL)) return c.map();
        synchronized (this) {
            c = tickerMap;
            if (c != null && fresh(c.fetchedAt(), MAP_TTL)) return c.map();
            Map<String, String> map = new LinkedHashMap<>();
            for (String leg : fetchAndParseText(QUOTES_SITEMAP_URL, FoolNewsClient::parseSitemapLocs)) {
                for (String url : fetchAndParseText(leg, FoolNewsClient::parseSitemapLocs)) {
                    String sym = symbolOfQuoteUrl(url);
                    if (sym != null) map.putIfAbsent(sym, url);
                }
            }
            if (map.isEmpty() && c != null) return c.map(); // outage: keep the old map, don't cache emptiness
            tickerMap = new CachedMap(Instant.now(), Map.copyOf(map));
            return tickerMap.map();
        }
    }

    /**
     * The venue segment of a symbol's quote path ({@code nasdaq}, {@code nyse},
     * {@code crypto}), or {@code null} when Fool carries no quote page for it.
     * Resolution order and its cost are documented on the class.
     *
     * <p>Currently unused, stands ready — {@link #quoteNews} and
     * {@link #quote} go through {@link #quotePageFor}, which resolves and
     * fetches in one step.
     */
    public String exchangeFor(String symbol) {
        QuotePage page = quotePageFor(symbol);
        return page == null ? null : page.exchange();
    }

    /**
     * The instrument's own recent Fool coverage, straight off its quote page —
     * the per-symbol path neither the news sitemap nor the foolwatch firehose
     * offers (both are chronological). Every item is tagged with the queried
     * symbol, since the page IS the ticker join.
     */
    public List<RawNewsItem> quoteNews(String symbol, int limit) {
        if (limit <= 0) return List.of();
        QuotePage page = quotePageFor(symbol);
        if (page == null || page.html() == null) return List.of();
        return parseQuoteArticles(page.html(), page.symbol()).stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /**
     * The instrument's profile and fundamentals off the same quote page, or
     * {@code null} when Fool doesn't carry the symbol. Free of charge next to
     * {@link #quoteNews} — both read the one cached page.
     *
     * <p>Currently unused, stands ready.
     */
    public FoolQuote quote(String symbol) {
        QuotePage page = quotePageFor(symbol);
        if (page == null || page.html() == null) return null;
        return parseQuoteProfile(page.html());
    }

    /**
     * Resolves a symbol's venue and hands back its quote page, cached for the
     * pool's TTL so news and profile never pay twice. Venue resolution: memo →
     * warm ticker map → probe {@code nasdaq → nyse → crypto} (a wrong venue is
     * a clean 404). An unresolvable symbol is remembered as such for the pool
     * TTL, so a burst of queries probes once, not once per call.
     */
    QuotePage quotePageFor(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String sym = symbol.trim().toUpperCase(Locale.ROOT);
        if (!isQuotePathSymbol(sym)) return null;

        CachedPage cachedPage = quotePages.get(sym);
        if (cachedPage != null && fresh(cachedPage.fetchedAt(), CACHE_TTL)) {
            return new QuotePage(sym, cachedPage.exchange(), cachedPage.html());
        }

        CachedText memo = exchangeMemo.get(sym);
        if (memo != null && fresh(memo.fetchedAt(),
                memo.value().isEmpty() ? CACHE_TTL : EXCHANGE_TTL)) {
            if (memo.value().isEmpty()) return null;
            String html = fetchQuotePage(memo.value(), sym);
            return rememberPage(sym, memo.value(), html);
        }

        // The warm map is authoritative — one lookup, no request. Never force-loaded here.
        CachedMap map = tickerMap;
        if (map != null && fresh(map.fetchedAt(), MAP_TTL)) {
            String url = map.map().get(sym);
            if (url == null) {
                exchangeMemo.put(sym, new CachedText(Instant.now(), ""));
                return null;
            }
            String exchange = exchangeOfQuoteUrl(url);
            exchangeMemo.put(sym, new CachedText(Instant.now(), exchange));
            return rememberPage(sym, exchange, fetchQuotePage(exchange, sym));
        }

        for (String exchange : EXCHANGES) {
            String html = fetchQuotePage(exchange, sym);
            if (html != null) {
                exchangeMemo.put(sym, new CachedText(Instant.now(), exchange));
                return rememberPage(sym, exchange, html);
            }
        }
        exchangeMemo.put(sym, new CachedText(Instant.now(), ""));
        return null;
    }

    /**
     * True when a symbol can appear in a quote path at all. Fool's quote URLs
     * are plain path segments, so an index symbol ({@code ^TECDAX}, {@code ^N225})
     * doesn't 404 - it fails URL parsing ("Illegal character in path") on every
     * one of the three venue probes, once per caller. Fool carries no index
     * quote pages either way, so the honest answer is a no-op before the URL is
     * built. Package-private for tests.
     */
    static boolean isQuotePathSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        return symbol.chars().allMatch(c ->
                (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-');
    }

    private QuotePage rememberPage(String symbol, String exchange, String html) {
        if (quotePages.size() > QUOTE_PAGE_CACHE_MAX) quotePages.clear();
        quotePages.put(symbol, new CachedPage(Instant.now(), exchange, html));
        return new QuotePage(symbol, exchange, html);
    }

    /** The quote page body, or {@code null} on anything but a 200 (a wrong venue 404s). */
    private String fetchQuotePage(String exchange, String symbol) {
        String url = QUOTE_BASE + exchange + "/" + symbol.toLowerCase(Locale.ROOT) + "/";
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "text/html"),
                    requestTimeout);
            if (resp != null && resp.status() == 200 && resp.body() != null
                    && !resp.body().isBlank()) {
                return resp.body();
            }
        } catch (Exception e) {
            LOG.debug("Fool quote page {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /** The merged, cached article pool; refreshed at most once per TTL. */
    private List<RawNewsItem> pool() {
        Cached c = pool;
        if (c != null && c.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return c.items();
        }
        synchronized (this) {
            c = pool;
            if (c != null && c.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
                return c.items();
            }
            List<RawNewsItem> merged = appendNew(
                    merge(fetchAndParse(SITEMAP_URL, FoolNewsClient::parseSitemap),
                            fetchAndParse(FEED_URL, FoolNewsClient::parseFeed)),
                    fetchAndParse(TRANSCRIPTS_URL, FoolNewsClient::parseTranscripts));
            if (merged.isEmpty() && c != null) {
                merged = c.items(); // outage: keep the stale pool over an empty one
            }
            pool = new Cached(Instant.now(), merged);
            return merged;
        }
    }

    private List<RawNewsItem> fetchAndParse(String url,
                                            java.util.function.Function<String, List<RawNewsItem>> parser) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                return parser.apply(resp.body());
            }
            LOG.debug("Fool feed {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("Fool feed {} failed: {}", url, e.getMessage());
        }
        return List.of();
    }

    /** {@link #fetchAndParse} for the surfaces that yield plain URL lists rather than articles. */
    private List<String> fetchAndParseText(String url,
                                           java.util.function.Function<String, List<String>> parser) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                return parser.apply(resp.body());
            }
            LOG.debug("Fool sitemap {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("Fool sitemap {} failed: {}", url, e.getMessage());
        }
        return List.of();
    }

    /**
     * Joins the two feeds on the canonical article link (tracking query
     * stripped): the sitemap item leads (tickers + image), a firehose twin
     * contributes its teaser; a firehose-only item stands alone. Package-private
     * for tests.
     */
    static List<RawNewsItem> merge(List<RawNewsItem> sitemap, List<RawNewsItem> feed) {
        Map<String, RawNewsItem> byLink = new LinkedHashMap<>();
        for (RawNewsItem it : sitemap) {
            byLink.putIfAbsent(cleanLink(it.link()), it);
        }
        for (RawNewsItem it : feed) {
            String key = cleanLink(it.link());
            RawNewsItem twin = byLink.get(key);
            if (twin == null) {
                byLink.put(key, it);
            } else if (twin.summary() == null || twin.summary().isBlank()) {
                byLink.put(key, new RawNewsItem(
                        twin.uuid(), twin.title(), twin.publisher(), twin.link(),
                        twin.publishedAt() != null ? twin.publishedAt() : it.publishedAt(),
                        twin.relatedTickers(), null, it.summary(), false, twin.imageUrl()));
            }
        }
        return List.copyOf(byLink.values());
    }

    /** Appends items whose canonical link the pool doesn't carry yet (transcripts vs articles never collide today — belt and suspenders). */
    static List<RawNewsItem> appendNew(List<RawNewsItem> base, List<RawNewsItem> extra) {
        if (extra.isEmpty()) return base;
        Map<String, RawNewsItem> byLink = new LinkedHashMap<>();
        for (RawNewsItem it : base) byLink.putIfAbsent(cleanLink(it.link()), it);
        for (RawNewsItem it : extra) byLink.putIfAbsent(cleanLink(it.link()), it);
        return List.copyOf(byLink.values());
    }

    /** The article URL without its tracking query ({@code ?source=…}) — the merge/identity key. */
    static String cleanLink(String link) {
        if (link == null) return "";
        int q = link.indexOf('?');
        return q >= 0 ? link.substring(0, q) : link;
    }

    /**
     * Parses the news sitemap into items: {@code <loc>} is link and identity,
     * {@code <news:title>}/{@code <news:publication_date>} carry the headline,
     * {@code <news:stock_tickers>} become {@link RawNewsItem#relatedTickers()}
     * (exchange prefix stripped), {@code <image:loc>} the article image.
     * Namespace-disambiguated — {@code news:title} vs {@code image:title} and
     * {@code loc} vs {@code image:loc} share local names. Garbage yields an
     * empty list, never an exception. Package-private for tests.
     */
    static List<RawNewsItem> parseSitemap(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inUrl = false;
            String loc = null, title = null, date = null, tickers = null, image = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        String ns = r.getNamespaceURI();
                        if ("url".equals(ln) && NS_SITEMAP.equals(ns)) {
                            inUrl = true;
                            loc = title = date = tickers = image = null;
                        } else if (inUrl) {
                            if ("loc".equals(ln) && NS_SITEMAP.equals(ns)) loc = textOf(r);
                            else if ("loc".equals(ln) && NS_IMAGE.equals(ns)) image = textOf(r);
                            else if (NS_NEWS.equals(ns)) {
                                switch (ln) {
                                    case "title" -> title = textOf(r);
                                    case "publication_date" -> date = textOf(r);
                                    case "stock_tickers" -> tickers = textOf(r);
                                    default -> { /* publication/name/language — ignored */ }
                                }
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT
                            && "url".equals(r.getLocalName())) {
                        inUrl = false;
                        if (loc != null && !loc.isEmpty() && title != null && !title.isEmpty()) {
                            out.add(new RawNewsItem(loc, title, PUBLISHER, loc,
                                    parseIsoDate(date), parseTickers(tickers),
                                    null, null, false, image));
                        }
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.warn("Unparseable Fool news sitemap: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    /**
     * Parses the foolwatch RSS into items: {@code <guid>} is the canonical
     * article URL (the {@code <link>} carries a tracking query) and serves as
     * both identity and link; {@code <description>} is the teaser. The heavy
     * {@code <content:encoded>} body and the category tags are deliberately
     * skipped — {@link RawNewsItem} has no body field. Package-private for tests.
     */
    static List<RawNewsItem> parseFeed(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null, link = null, guid = null, pubDate = null, description = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = guid = pubDate = description = null;
                        } else if (inItem) {
                            switch (ln) {
                                case "title" -> title = textOf(r);
                                case "link" -> link = textOf(r);
                                case "guid" -> guid = textOf(r);
                                case "pubDate" -> pubDate = textOf(r);
                                case "description" -> description = textOf(r);
                                default -> { /* content:encoded, author, category — skipped */ }
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT
                            && "item".equals(r.getLocalName())) {
                        inItem = false;
                        String canonical = guid != null && !guid.isEmpty()
                                ? guid : cleanLink(link);
                        if (title != null && !title.isEmpty()
                                && canonical != null && !canonical.isEmpty()) {
                            out.add(new RawNewsItem(canonical, title, PUBLISHER, canonical,
                                    parseRfc1123Date(pubDate), List.of(),
                                    null, description, false, null));
                        }
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.warn("Unparseable foolwatch feed: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    private static final Pattern TRANSCRIPT_LINK = Pattern.compile(
            "href=\"(/earnings/call-transcripts/(\\d{4})/(\\d{2})/(\\d{2})/[^\"]+)\"");
    private static final Pattern H5_TITLE = Pattern.compile("<h5[^>]*>([^<]+)</h5>");
    private static final Pattern TITLE_TICKER = Pattern.compile("\\(([A-Z][A-Z0-9.\\-]{0,9})\\)");

    /** Max chars between a card's link and its {@code <h5>} title (live: ~90-100). */
    private static final int TRANSCRIPT_PAIR_WINDOW = 500;

    /**
     * Parses the transcripts listing page: each card carries the article link
     * twice (image + title anchor) with the {@code <h5>} title directly after
     * the second one — every title is paired with the nearest preceding
     * transcript link. Ticker from the title parens, date from the URL path
     * (UTC midnight — the listing shows no time). Package-private for tests.
     */
    static List<RawNewsItem> parseTranscripts(String html) {
        if (html == null || html.isBlank()) return List.of();
        record LinkAt(int end, String path, Instant date) {}
        List<LinkAt> links = new ArrayList<>();
        java.util.regex.Matcher lm = TRANSCRIPT_LINK.matcher(html);
        while (lm.find()) {
            Instant date;
            try {
                date = java.time.LocalDate.of(
                                Integer.parseInt(lm.group(2)),
                                Integer.parseInt(lm.group(3)),
                                Integer.parseInt(lm.group(4)))
                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            } catch (Exception e) {
                date = null;
            }
            links.add(new LinkAt(lm.end(), lm.group(1), date));
        }
        if (links.isEmpty()) return List.of();

        Map<String, RawNewsItem> byLink = new LinkedHashMap<>();
        java.util.regex.Matcher tm = H5_TITLE.matcher(html);
        while (tm.find()) {
            String title = tm.group(1).trim();
            LinkAt best = null;
            for (LinkAt l : links) {
                if (l.end() <= tm.start()) best = l;
                else break;
            }
            if (best == null || tm.start() - best.end() > TRANSCRIPT_PAIR_WINDOW) continue;
            title = unescapeHtml(title);
            String url = "https://www.fool.com" + best.path();
            java.util.regex.Matcher tick = TITLE_TICKER.matcher(title);
            List<String> tickers = tick.find()
                    ? List.of(tick.group(1).toUpperCase(Locale.ROOT)) : List.of();
            byLink.putIfAbsent(url, new RawNewsItem(url, title, PUBLISHER, url,
                    best.date(), tickers, null, null, false, null));
        }
        return List.copyOf(byLink.values());
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern QUOTE_URL = Pattern.compile(
            "^https://www\\.fool\\.com/quote/([a-z0-9-]+)/([^/]+)/?$");

    private static final Pattern RELATED_SCHEMA = Pattern.compile(
            "<script[^>]*id=\"quote-page-related-schema\"[^>]*>(.*?)</script>", Pattern.DOTALL);

    /**
     * One dated article inside the escaped Next.js payload: {@code publish_at},
     * {@code path} and {@code headline} sit in one flat object in that order.
     * {@code [^{}]*?} is the guard that keeps the three fields inside ONE object
     * — an object boundary breaks the match instead of pairing article A's date
     * with article B's link. The path shape is the date filter: the same rail
     * also lists undated evergreen guides ({@code /investing/stock-market/…}),
     * which are reference material, not news.
     */
    private static final Pattern PAYLOAD_ARTICLE = Pattern.compile(
            "\"publish_at\":\"([^\"]+)\""
                    + "[^{}]*?\"path\":\"(/[a-z0-9-]+/\\d{4}/\\d{2}/\\d{2}/[^\"/]+/)\""
                    + "[^{}]*?\"headline\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * Every {@code <loc>} of a sitemap or sitemap index, in document order.
     * Fool's own files mix both grammars — {@code quotes-sitemap.xml} is an
     * index yet uses {@code <urlset>/<url>} rather than
     * {@code <sitemapindex>/<sitemap>} — so this reads {@code <loc>} regardless
     * of its parent. The literal {@code None} placeholder heading
     * {@code wag-sitemap.xml} is dropped. Garbage yields an empty list, never
     * an exception. Package-private for tests.
     */
    static List<String> parseSitemapLocs(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            try {
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT
                            && "loc".equals(r.getLocalName())
                            && NS_SITEMAP.equals(r.getNamespaceURI())) {
                        String loc = textOf(r);
                        if (loc != null && !loc.isEmpty() && !"None".equals(loc)) out.add(loc);
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.warn("Unparseable Fool sitemap: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(out);
    }

    /** {@code …/quote/nasdaq/nvda/} → {@code NVDA}; anything else → {@code null}. Package-private for tests. */
    static String symbolOfQuoteUrl(String url) {
        if (url == null) return null;
        Matcher m = QUOTE_URL.matcher(url.trim());
        return m.matches() ? m.group(2).toUpperCase(Locale.ROOT) : null;
    }

    /** {@code …/quote/nasdaq/nvda/} → {@code nasdaq}; anything else → {@code null}. */
    static String exchangeOfQuoteUrl(String url) {
        if (url == null) return null;
        Matcher m = QUOTE_URL.matcher(url.trim());
        return m.matches() ? m.group(1) : null;
    }

    /**
     * The instrument's recent Fool coverage off its quote page, from both
     * surfaces the page carries:
     *
     * <ul>
     *   <li>the JSON-LD {@code ItemList} — 10 entries, each with headline,
     *       {@code datePublished} and an article image;</li>
     *   <li>the escaped {@code self.__next_f} payload — ~22 dated entries,
     *       roughly twice the reach but without images.</li>
     * </ul>
     *
     * <p>The rich leg leads, the wide leg appends what it doesn't already
     * carry (canonical-link join, same rule as the pool). Every item is tagged
     * with {@code symbol}: the page is the ticker join, so the tag is a fact,
     * not a guess. Garbage yields an empty list, never an exception.
     * Package-private for tests.
     */
    static List<RawNewsItem> parseQuoteArticles(String html, String symbol) {
        if (html == null || html.isBlank()) return List.of();
        List<String> tickers = symbol == null || symbol.isBlank()
                ? List.of() : List.of(symbol.trim().toUpperCase(Locale.ROOT));

        List<RawNewsItem> rich = new ArrayList<>();
        Matcher schema = RELATED_SCHEMA.matcher(html);
        if (schema.find()) {
            try {
                for (JsonNode a : JSON.readTree(schema.group(1)).path("itemListElement")) {
                    String url = cleanLink(a.path("url").asText(null));
                    String headline = a.path("headline").asText(null);
                    if (url == null || url.isEmpty() || headline == null || headline.isEmpty()) {
                        continue;
                    }
                    rich.add(new RawNewsItem(url, headline, PUBLISHER, url,
                            parseIsoDate(a.path("datePublished").asText(null)), tickers,
                            null, null, false, a.path("image").asText(null)));
                }
            } catch (Exception e) {
                LOG.debug("Unparseable Fool quote-page schema: {}", e.getMessage());
            }
        }

        List<RawNewsItem> wide = new ArrayList<>();
        Matcher m = PAYLOAD_ARTICLE.matcher(unescapeJsString(html));
        while (m.find()) {
            String url = "https://www.fool.com" + m.group(2);
            String headline = unescapeJson(m.group(3));
            if (headline.isEmpty()) continue;
            wide.add(new RawNewsItem(url, headline, PUBLISHER, url,
                    parseIsoDate(m.group(1)), tickers, null, null, false, null));
        }
        return appendNew(List.copyOf(rich), List.copyOf(wide));
    }

    /**
     * The instrument's own profile and fundamentals off the quote page: the
     * Next.js payload carries them as a {@code "ticker"} object (identity,
     * sector, business prose) immediately followed by a {@code "quote"} object
     * (price, market cap, multiples). Both are read by balanced-brace slicing
     * out of the unescaped payload — the surrounding document is a JS string,
     * not JSON, so there is nothing to hand a parser whole.
     *
     * <p>Deliberately NOT read: the {@code currentPrice} figures scattered over
     * the same page. Those belong to the site-wide ticker bar (indices plus a
     * fixed set of mega caps) and are byte-identical on every quote page — the
     * trap the research note fell into. Returns {@code null} when the page
     * carries no instrument block. Package-private for tests.
     */
    static FoolQuote parseQuoteProfile(String html) {
        if (html == null || html.isBlank()) return null;
        String payload = unescapeJsString(html);
        JsonNode ticker = objectAfter(payload, "\"ticker\":", 0);
        if (ticker == null || ticker.path("symbol").asText("").isEmpty()) return null;
        JsonNode quote = objectAfter(payload, "\"quote\":", payload.indexOf("\"ticker\":"));
        if (quote == null) quote = JSON.createObjectNode();

        return new FoolQuote(
                ticker.path("symbol").asText(null),
                ticker.path("exchange").asText(null),
                text(ticker, "name"),
                text(ticker, "sector"),
                text(ticker, "industry"),
                text(ticker, "description"),
                text(quote, "CurrencyCode"),
                number(quote.path("CurrentPrice"), "Amount"),
                number(quote.path("PercentChange"), "Value"),
                number(quote.path("MarketCap"), "Amount"),
                number(quote, "PriceToEarningsRatio"),
                number(quote.path("EarningsPerShare"), "Amount"),
                number(quote.path("DividendYield"), "Value"),
                number(quote.path("FiftyTwoWeekRange").path("Minimum"), "Amount"),
                number(quote.path("FiftyTwoWeekRange").path("Maximum"), "Amount"),
                quote.path("Volume").isNumber() ? quote.path("Volume").asLong() : null);
    }

    /**
     * Parses the JSON object that starts at the first {@code &#123;} after
     * {@code key} (searched from {@code from}), found by brace balance — the
     * only way to cut one object out of a payload that is a JS string
     * containing JSON containing more JSON. {@code null} when the key is
     * absent or the braces don't close.
     */
    private static JsonNode objectAfter(String payload, String key, int from) {
        int at = payload.indexOf(key, Math.max(from, 0));
        if (at < 0) return null;
        int start = payload.indexOf('{', at + key.length());
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                try {
                    return JSON.readTree(payload.substring(start, i + 1));
                } catch (Exception e) {
                    LOG.debug("Unparseable Fool quote object at {}: {}", key, e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    private static Double number(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.asDouble() : null;
    }

    /**
     * Undoes ONE level of JavaScript string escaping — the Next.js payload is
     * JSON embedded in a JS string literal, so its quotes arrive as
     * {@code \"}. A single left-to-right pass, not a blind replace: a headline
     * that itself contains a quote arrives triple-escaped ({@code \\\"}), and
     * only a pass that consumes the {@code \\} pair FIRST — collapsing it to
     * one backslash — leaves valid JSON behind instead of a shredded string.
     * Package-private for tests.
     */
    static String unescapeJsString(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'n' -> out.append('\n');
                    default -> out.append('\\').append(n);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** JSON string escapes inside an already-unescaped payload value (headlines carry quotes and dashes). */
    private static String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"").replace("\\/", "/").replace("\\n", " ")
                .replace("\\\\", "\\").trim();
    }

    /** {@code "NYSE:WELL,NASDAQ:SBRA"} → {@code [WELL, SBRA]} — exchange prefix stripped. */
    static List<String> parseTickers(String stockTickers) {
        if (stockTickers == null || stockTickers.isBlank()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String raw : stockTickers.split(",")) {
            String t = raw.trim();
            int colon = t.lastIndexOf(':');
            if (colon >= 0) t = t.substring(colon + 1).trim();
            if (!t.isEmpty()) out.add(t.toUpperCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    /** ISO offset date ({@code 2026-07-13T02:04:00+00:00}) → {@link Instant}; unparseable → null. */
    static Instant parseIsoDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s.trim()).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** RFC-1123 pubDate ({@code Sun, 12 Jul 2026 19:00:00 -0400}) → {@link Instant}; unparseable → null. */
    static Instant parseRfc1123Date(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
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

    /** Significant (length ≥ 3, non-generic) words of the queried name. */
    static Set<String> significantWords(String name) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    /** The handful of entities Fool's card titles actually carry — this is a title decoder, not an HTML parser. */
    private static String unescapeHtml(String s) {
        return s.replace("&amp;", "&").replace("&#x27;", "'").replace("&#39;", "'")
                .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">");
    }

    /** Reads the element's text content, trimmed. Safe for text-only elements. */
    private static String textOf(XMLStreamReader r) throws Exception {
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
