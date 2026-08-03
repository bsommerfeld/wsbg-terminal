package de.bsommerfeld.wsbg.terminal.tradersunion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.core.util.HtmlText;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traders Union's editorial surface - the news leg, live-probed 2026-08-02.
 *
 * <p><b>URLs come from sitemaps only.</b> The site publishes an AI discovery
 * manifest ({@code /mcp.json}) that explicitly sets
 * {@code url_generation_allowed: false} - "use only URLs discovered through
 * canonical tags, hreflang, sitemaps". Every url this client ever fetches is
 * either one of the four sitemap entry points below or a {@code <loc>} read out
 * of them. Nothing is assembled from an id, a slug or a segment name, and no
 * public method here takes anything but a DISCOVERED url. The manifest's other
 * standing obligation - cite the source page - is why every item keeps its
 * {@link RawNewsItem#link()}.
 *
 * <p><b>Two sitemaps, two jobs.</b>
 * <ul>
 *   <li>{@link #HEADLINE_SITEMAP} - the Google-News sitemap. ~150 urls over a
 *       two-day window, but each one carries {@code news:title} and
 *       {@code news:publication_date}, so a full headline pool costs ONE fetch.
 *       This is what {@link #latest(int)} and {@link #section(String, int)}
 *       serve from.</li>
 *   <li>{@link #INDEX_SITEMAP} - the wide news sitemap: ~1.500 urls over eight
 *       days, {@code loc} + {@code lastmod} only, no titles. It is the URL
 *       INDEX: {@link #index()} exposes it, and the name fan matches against
 *       the (English) slug before spending a page fetch on a hit.</li>
 * </ul>
 *
 * <p><b>The gate is the path segment, not the author.</b> Roughly two thirds of
 * the German news corpus is price-tick copy generated off a move. The authors do
 * NOT separate it (the same bylines write both, 30 pieces sampled) - the URL
 * segment does, and it is language-stable in a way the localized
 * {@code articleSection} is not. Measured over all 1.498 German news urls:
 * {@code /news/stocks/} 94 %, {@code /news/companies/} 94 %,
 * {@code /news/cryptocurrency-news/} 87 %, {@code /news/commodities/} 78 %
 * against {@code /news/editors-picks/} 0 %, {@code /news/central-banks/} 0 %,
 * {@code /news/institutions/} 0 %, {@code /news/currencies/} 0 %,
 * {@code /news/geopolitics-trade/} 20 %, {@code /news/financial-news/} 16 %
 * (see {@link #SEGMENT_TICK_RATE}).
 *
 * <p><b>Tick pieces are MARKED, never dropped.</b> They are not empty - they
 * quote GAAP results, buyback volume at an average price, indicator readings.
 * So they ride the wire under the {@link #PUBLISHER_TICK} publisher while
 * everything else rides under {@link #PUBLISHER}, and a gate further up decides
 * what to do with them. The client curates nothing.
 *
 * <p><b>Broker PR is flagged.</b> {@code /news/brokers-news/} (222 urls) is paid
 * broker placement and gets {@link RawNewsItem#sponsored()}.
 *
 * <p><b>German is machine-translated English.</b> Every German piece carries
 * "Dieser Artikel wurde aus dem Original übersetzt" and links its English
 * original - there is no German-language original here. {@link Article} keeps
 * that visible via {@link Article#machineTranslated()}.
 *
 * <p><b>500 tolerance.</b> Single pages 500 sporadically while their neighbours
 * answer 200. Every fetch here degrades to an empty result; one sick page never
 * kills a sweep.
 *
 * <p><b>Name-addressed only.</b> The news pages carry no {@code data-symbol} and
 * no {@code data-ticker-id} - those attributes live on the INSTRUMENT pages
 * ({@link TradersUnionMoversClient}), not on articles. So {@link #newsFor(String, int)}
 * stays a no-op: the only symbol signal on an article is a ticker in parentheses
 * inside the lead, which {@link Article#relatedTickers()} extracts AFTER the
 * body was fetched - far too late, and far too weak, to address a query with.
 *
 * <p><b>Broker reviews and rankings are a SEPARATE SHELF.</b>
 * {@link #brokerReview(String)} and {@link #ranking(String)} return their own
 * records and never a {@link RawNewsItem} - a rating with an advertiser
 * relationship behind it must not be able to walk into the press review. Both
 * currently have no caller; they stand ready.
 */
@Singleton
public class TradersUnionNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(TradersUnionNewsClient.class);

    /** Editorial pieces. */
    public static final String PUBLISHER = "Traders Union";
    /** Price-tick copy generated off a move - full of numbers, editorially thin. */
    public static final String PUBLISHER_TICK = "Traders Union (Kursbewegung)";

    static final String HOST = "https://tradersunion.com";
    /** Google-News sitemap: ~150 recent urls WITH title and publication date. */
    static final String HEADLINE_SITEMAP = HOST + "/sitemaps/news-sitemap-de.xml";
    /** Wide news sitemap: ~1.500 urls over eight days, loc + lastmod only. */
    static final String INDEX_SITEMAP = HOST + "/sitemap_news_de.xml";
    /** Broker review index - the separate shelf, never the press review. */
    static final String BROKER_SITEMAP = HOST + "/sitemap_brokers_de.xml";
    /** Broker ranking index - likewise. */
    static final String RANKING_SITEMAP = HOST + "/sitemap_ranking_de.xml";

    /**
     * The news path segments and their MEASURED share of price-tick copy, in
     * percent (all 1.498 German news urls, 2026-08-02). A segment absent from
     * this map was never measured - the slug pattern alone then decides.
     */
    public static final Map<String, Integer> SEGMENT_TICK_RATE = Map.ofEntries(
            Map.entry("stocks", 94),
            Map.entry("companies", 94),
            Map.entry("cryptocurrency-news", 87),
            Map.entry("commodities", 78),
            Map.entry("geopolitics-trade", 20),
            Map.entry("financial-news", 16),
            Map.entry("brokers-news", 3),
            Map.entry("market-voices", 1),
            Map.entry("editors-picks", 0),
            Map.entry("central-banks", 0),
            Map.entry("institutions", 0),
            Map.entry("currencies", 0));

    /** Segments whose base rate makes the segment itself the tick verdict. */
    static final Set<String> TICK_SEGMENTS =
            Set.of("stocks", "companies", "cryptocurrency-news", "commodities");

    /** Paid broker placement - a whole segment of it. */
    static final String SPONSORED_SEGMENT = "brokers-news";

    /**
     * The second, per-article tick signal: the slug verbs a generated move piece
     * always uses. Measured against the full corpus alongside the segment split.
     */
    static final Pattern TICK_SLUG = Pattern.compile(
            "(percent|-jumps-|-surges-|-drops-|-rises-|-slides-|-climbs-|-falls-|-gains-"
                    + "|-sinks-|-plunges-|-soars-|-dips-|-slips-|-rallies-|-tumbles-|-usd\\d)");

    /** {@code /de/news/<segment>/show/<id>-<slug>/} - the only news url shape. */
    private static final Pattern NEWS_PATH =
            Pattern.compile("/news/([a-z0-9-]+)/show/(\\d+)-([a-z0-9-]*)/?");

    private static final Pattern LD_JSON = Pattern.compile(
            "<script[^>]*type=\"application/ld\\+json\"[^>]*>(.*?)</script>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile(
            "<(script|style)\\b[^>]*>.*?</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final String CONTENT_DIV = "<div class=\"news-single__content\">";
    /** A ticker in parentheses inside the lead - the only symbol an article prints. */
    private static final Pattern LEAD_TICKER = Pattern.compile("\\(([A-Z]{1,5})\\)");
    private static final int LEAD_WINDOW = 400;

    /** Generic words that must never carry the title-relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien",
            "stock", "stocks", "shares");

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    /** Page fetches the name fan may spend on index hits that miss the pool. */
    private static final int MAX_ARTICLE_FETCHES = 6;

    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Newest first; items without a timestamp sort to the end. */
    private static final Comparator<RawNewsItem> BY_RECENCY =
            Comparator.comparing(RawNewsItem::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    /**
     * One url out of a news sitemap, with everything the url itself already
     * tells us. No page was fetched to build this.
     *
     * @param url                  the discovered, absolute article url
     * @param segment              the path segment ({@code stocks},
     *                             {@code central-banks}, …), or {@code null}
     * @param articleId            the numeric id in the slug, the stable identity
     * @param slug                 the (English) slug, the name-match haystack
     * @param lastModified         {@code lastmod} / {@code news:publication_date}
     * @param title                {@code news:title} where the sitemap carries
     *                             one, otherwise {@code null}
     * @param priceMoveGenerated   {@code true} when segment or slug marks this as
     *                             price-tick copy - a MARK, never a reason to drop
     * @param sponsored            {@code true} for paid broker placement
     */
    public record NewsUrl(String url, String segment, String articleId, String slug,
                          Instant lastModified, String title,
                          boolean priceMoveGenerated, boolean sponsored) {

        /** The measured tick share of this url's segment, or -1 when unmeasured. */
        public int segmentTickRate() {
            return SEGMENT_TICK_RATE.getOrDefault(segment, -1);
        }

        /** As a wire item; {@code null} when the sitemap carried no title. */
        public RawNewsItem asNewsItem() {
            if (title == null || title.isBlank()) return null;
            return new RawNewsItem(articleId != null ? articleId : url, title,
                    priceMoveGenerated ? PUBLISHER_TICK : PUBLISHER, url,
                    lastModified, List.of(), null, null, sponsored, null);
        }
    }

    /**
     * A fully read article: ~4.300 characters of body text out of
     * {@code div.news-single__content}, plus the JSON-LD identity around it.
     *
     * @param machineTranslated {@code true} when the piece carries the
     *                          "aus dem Original übersetzt" note - which every
     *                          German piece does
     * @param relatedTickers    tickers printed in parentheses in the LEAD, the
     *                          only instrument signal an article page carries
     */
    public record Article(String url, String title, String teaser, String fullText,
                          Instant publishedAt, String author, String imageUrl,
                          String segment, boolean priceMoveGenerated, boolean sponsored,
                          List<String> relatedTickers, boolean machineTranslated) {

        /** As a wire item; the teaser rides {@link RawNewsItem#summary()}. */
        public RawNewsItem asNewsItem() {
            return new RawNewsItem(url, title,
                    priceMoveGenerated ? PUBLISHER_TICK : PUBLISHER, url,
                    publishedAt, relatedTickers, null, teaser, sponsored, imageUrl);
        }
    }

    /**
     * One broker review off the {@code Review} JSON-LD. A rating, an advertiser
     * relationship and a rank - a shelf of its own, never press. Currently no
     * caller; it stands ready.
     *
     * @param rating     {@code reviewRating.ratingValue}, or {@code -1}
     * @param bestRating the scale's top ({@code 9.99} live), or {@code -1}
     */
    public record BrokerReview(String url, String broker, String headline,
                               double rating, double bestRating, String author,
                               Instant publishedAt, Instant modifiedAt, String body) {

        /** The rating normalised to 0-10, or {@code -1} when either side is absent. */
        public double ratingOutOfTen() {
            return rating < 0 || bestRating <= 0 ? -1 : rating / bestRating * 10d;
        }
    }

    /** One place in a broker ranking ({@code ItemList} JSON-LD). Same shelf. */
    public record RankingEntry(String listName, int position, String broker, String url) {}

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(15);

    private volatile Cached<RawNewsItem> pool;
    private volatile Cached<NewsUrl> index;

    private record Cached<T>(Instant fetchedAt, List<T> items) {
        boolean fresh() {
            return fetchedAt.plus(CACHE_TTL).isAfter(Instant.now());
        }
    }

    /** Test/default: plain direct transport. */
    public TradersUnionNewsClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: rides the shared standard {@link WebFetcher} chain
     * (browser-first). Cloudflare fronts the host and 403s a naked HTTP/2
     * fingerprint, so the browser leg is what keeps this source answering.
     */
    @Inject
    public TradersUnionNewsClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "tradersunion";
    }

    /**
     * Dossier leg. Taken in under the fishing-net rule and sorted at the
     * shelf: /news/stocks/ is 94% price-tick-generated and the German copy is
     * machine-translated. The model may weigh that in a dossier; the wire
     * must not report a tick as a catalyst (2026-08-03).
     */
    @Override
    public boolean dossierOnly() {
        return true;
    }

    // ---------------------------------------------------------------
    // General stream - no instrument involved
    // ---------------------------------------------------------------

    /**
     * The freshest headlines across ALL segments, newest first - the source's
     * plain news stream, one fetch, no instrument involved. Tick copy is present
     * and marked via {@link #PUBLISHER_TICK}; broker PR is present and flagged
     * {@code sponsored}. Nothing is curated away here.
     */
    public List<RawNewsItem> latest(int limit) {
        if (limit <= 0) return List.of();
        return pool().stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * The freshest headlines of ONE path segment ({@code central-banks},
     * {@code editors-picks}, {@code financial-news}, {@code stocks},
     * {@code companies}, … - see {@link #SEGMENT_TICK_RATE}), newest first.
     * Served off the same cached pool as {@link #latest(int)}, so a segment call
     * costs nothing extra.
     */
    public List<RawNewsItem> section(String segment, int limit) {
        if (segment == null || segment.isBlank() || limit <= 0) return List.of();
        String want = segment.trim().toLowerCase(Locale.ROOT);
        return pool().stream()
                .filter(it -> want.equals(segmentOf(it.link())))
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /**
     * The WIDE url index: every German news url of the last eight days with its
     * segment, its id and its tick mark, but no title (the wide sitemap carries
     * none). The reach layer behind {@link #latest(int)} - hand a
     * {@link NewsUrl#url()} to {@link #article(String)} to read one.
     */
    public List<NewsUrl> index() {
        Cached<NewsUrl> c = index;
        if (c != null && c.fresh()) return c.items();
        synchronized (this) {
            c = index;
            if (c != null && c.fresh()) return c.items();
            String xml = fetchText(INDEX_SITEMAP);
            List<NewsUrl> parsed = parseSitemap(xml);
            if (parsed.isEmpty() && c != null) parsed = c.items(); // outage: keep it stale
            index = new Cached<>(Instant.now(), parsed);
            return parsed;
        }
    }

    /** The wide index restricted to one path segment, newest first. */
    public List<NewsUrl> sectionUrls(String segment, int limit) {
        if (segment == null || segment.isBlank() || limit <= 0) return List.of();
        String want = segment.trim().toLowerCase(Locale.ROOT);
        return index().stream()
                .filter(u -> want.equals(u.segment()))
                .sorted(Comparator.comparing(NewsUrl::lastModified,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
    }

    /**
     * Reads ONE article in full. {@code discoveredUrl} must come from a sitemap
     * ({@link #index()}, {@link #latest(int)}) - never from string assembly.
     *
     * @return the article, or {@code null} when the page failed (a 500 on a
     *         single piece is routine here)
     */
    public Article article(String discoveredUrl) {
        if (discoveredUrl == null || discoveredUrl.isBlank()) return null;
        String url = absolute(discoveredUrl);
        String html = fetchText(url);
        return html == null ? null : parseArticle(url, html);
    }

    // ---------------------------------------------------------------
    // Instrument-addressed fans
    // ---------------------------------------------------------------

    /**
     * No-op. Article pages carry no {@code data-symbol} and no ticker id - those
     * live on the instrument pages ({@link TradersUnionMoversClient}). The only
     * symbol on an article is a ticker in parentheses inside the body, readable
     * solely AFTER a full page fetch, so answering a symbol query would mean
     * downloading the whole corpus per lookup. The name fan carries this source.
     */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /**
     * By name: the cached headline pool (German titles, precision-matched)
     * merged with the wide url index, whose ENGLISH slugs still carry the
     * company name after translation. Index hits that the pool does not already
     * hold are read in full - at most {@link #MAX_ARTICLE_FETCHES} of them, so
     * one name never turns into a crawl.
     */
    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        List<RawNewsItem> fromPool = pool().stream()
                .filter(it -> matches(it.title(), words))
                .toList();
        List<RawNewsItem> fromIndex = readMatchingArticles(index(), words, null, null);
        return mergeByLink(fromPool, fromIndex).stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /**
     * ARCHIVE window over the wide index. The window is real but SHORT: the
     * German news sitemap spans eight days, so a request reaching further back
     * simply answers what the index still holds - older pieces stay 200 on the
     * site but are no longer enumerable without generating urls, which the AI
     * manifest forbids.
     */
    @Override
    public List<RawNewsItem> newsForNameWindow(String companyName, String isin,
                                               String fromIsoDate, String toIsoDateExclusive,
                                               int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Instant from = startOfDay(fromIsoDate);
        Instant toExcl = startOfDay(toIsoDateExclusive);
        if (from == null || toExcl == null) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        List<RawNewsItem> fromPool = pool().stream()
                .filter(it -> matches(it.title(), words))
                .filter(it -> inWindow(it.publishedAt(), from, toExcl))
                .toList();
        List<RawNewsItem> fromIndex = readMatchingArticles(index(), words, from, toExcl);
        return mergeByLink(fromPool, fromIndex).stream()
                .filter(it -> inWindow(it.publishedAt(), from, toExcl))
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    // ---------------------------------------------------------------
    // Broker shelf - reviews and rankings, never press
    // ---------------------------------------------------------------

    /** The broker review urls out of the broker sitemap. */
    public List<String> brokerReviewUrls(int limit) {
        return sitemapLocations(BROKER_SITEMAP, limit);
    }

    /** The broker ranking urls out of the ranking sitemap. */
    public List<String> rankingUrls(int limit) {
        return sitemapLocations(RANKING_SITEMAP, limit);
    }

    /**
     * Reads one broker review page ({@code Review} JSON-LD with
     * {@code reviewRating}). {@code discoveredUrl} must come from
     * {@link #brokerReviewUrls(int)}. Currently no caller - it stands ready as
     * its own shelf.
     *
     * @return the review, or {@code null} on failure
     */
    public BrokerReview brokerReview(String discoveredUrl) {
        if (discoveredUrl == null || discoveredUrl.isBlank()) return null;
        String url = absolute(discoveredUrl);
        String html = fetchText(url);
        return html == null ? null : parseBrokerReview(url, html);
    }

    /** The first {@code limit} broker reviews, read page by page. */
    public List<BrokerReview> brokerReviews(int limit) {
        if (limit <= 0) return List.of();
        List<BrokerReview> out = new ArrayList<>();
        for (String url : brokerReviewUrls(limit)) {
            BrokerReview r = brokerReview(url);
            if (r != null) out.add(r);
        }
        return List.copyOf(out);
    }

    /**
     * Reads one broker ranking page ({@code ItemList} JSON-LD), position order.
     * {@code discoveredUrl} must come from {@link #rankingUrls(int)}. Currently
     * no caller - same shelf as {@link #brokerReview(String)}.
     */
    public List<RankingEntry> ranking(String discoveredUrl) {
        if (discoveredUrl == null || discoveredUrl.isBlank()) return List.of();
        String html = fetchText(absolute(discoveredUrl));
        return html == null ? List.of() : parseRanking(html);
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    /** The cached headline pool off the Google-News sitemap. */
    private List<RawNewsItem> pool() {
        Cached<RawNewsItem> c = pool;
        if (c != null && c.fresh()) return c.items();
        synchronized (this) {
            c = pool;
            if (c != null && c.fresh()) return c.items();
            List<RawNewsItem> items = new ArrayList<>();
            for (NewsUrl u : parseSitemap(fetchText(HEADLINE_SITEMAP))) {
                RawNewsItem it = u.asNewsItem();
                if (it != null) items.add(it);
            }
            List<RawNewsItem> deduped = mergeByLink(items, List.of());
            if (deduped.isEmpty() && c != null) deduped = c.items(); // outage: keep it stale
            pool = new Cached<>(Instant.now(), deduped);
            return deduped;
        }
    }

    /**
     * Slug-matches the index, optionally inside a window, and reads at most
     * {@link #MAX_ARTICLE_FETCHES} hits in full.
     */
    private List<RawNewsItem> readMatchingArticles(List<NewsUrl> urls, Set<String> words,
                                                   Instant from, Instant toExcl) {
        List<RawNewsItem> out = new ArrayList<>();
        for (NewsUrl u : urls) {
            if (out.size() >= MAX_ARTICLE_FETCHES) break;
            if (from != null && !inWindow(u.lastModified(), from, toExcl)) continue;
            if (!matches(u.slug(), words)) continue;
            Article a = article(u.url());
            if (a != null) out.add(a.asNewsItem());
        }
        return out;
    }

    /** GET as text; any non-200 (403 wall, 500 sick page) answers {@code null}. */
    private String fetchText(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent,
                            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                            "Accept-Language", "de-DE,de;q=0.9,en;q=0.8"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("[TradersUnion] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[TradersUnion] {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /** Every {@code <loc>} of a sitemap, in document order. */
    private List<String> sitemapLocations(String sitemapUrl, int limit) {
        if (limit <= 0) return List.of();
        String xml = fetchText(sitemapUrl);
        return parseLocations(xml).stream().limit(limit).toList();
    }

    // ---- parsers (network-free, package-private for tests) ----

    /**
     * Parses BOTH news sitemap shapes into {@link NewsUrl}s: the wide one
     * ({@code loc} + {@code lastmod}) and the Google-News one ({@code loc} +
     * {@code news:publication_date} + {@code news:title}).
     */
    static List<NewsUrl> parseSitemap(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<NewsUrl> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            try {
                boolean inUrl = false;
                String loc = null, lastmod = null, pubDate = null, title = null;
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("url".equals(ln)) {
                            inUrl = true;
                            loc = lastmod = pubDate = title = null;
                        } else if (inUrl) {
                            switch (ln) {
                                case "loc" -> {
                                    if (isSitemapNamespace(r.getNamespaceURI())) loc = textOf(r);
                                }
                                case "lastmod" -> lastmod = textOf(r);
                                case "publication_date" -> pubDate = textOf(r);
                                case "title" -> title = textOf(r);
                                default -> { /* publication, name, language */ }
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT
                            && "url".equals(r.getLocalName())) {
                        inUrl = false;
                        if (loc == null || loc.isBlank()) continue;
                        NewsUrl u = newsUrl(loc, parseDate(pubDate != null ? pubDate : lastmod),
                                title);
                        if (u != null) out.add(u);
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.debug("[TradersUnion] unparseable sitemap: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * Every page {@code <loc>} of any sitemap (index or urlset), in document
     * order. Namespace-checked on purpose: the broker sitemap nests an
     * {@code <image:image><image:loc>} beside every page url, and a local-name
     * match would hand back logo jpegs as if they were broker pages.
     */
    static List<String> parseLocations(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            try {
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT
                            && "loc".equals(r.getLocalName())
                            && isSitemapNamespace(r.getNamespaceURI())) {
                        String v = textOf(r);
                        if (v != null && !v.isBlank()) out.add(v.trim());
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.debug("[TradersUnion] unparseable sitemap: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * One news url → its segment, id, slug and gate marks. Returns {@code null}
     * for anything that is not a news article url.
     */
    static NewsUrl newsUrl(String loc, Instant when, String title) {
        String url = absolute(loc);
        Matcher m = NEWS_PATH.matcher(url);
        if (!m.find()) return null;
        String segment = m.group(1);
        String articleId = m.group(2);
        String slug = m.group(3);
        return new NewsUrl(url, segment, articleId, slug, when, blankToNull(title),
                isPriceMoveGenerated(segment, slug), SPONSORED_SEGMENT.equals(segment));
    }

    /**
     * The gate. The path segment decides first - it is the language-stable key
     * and the one the 94-against-0 measurement runs on; the slug pattern is the
     * per-article second signal that also catches a move piece parked in an
     * otherwise editorial segment. A hit is a MARK, never a reason to drop.
     */
    static boolean isPriceMoveGenerated(String segment, String slug) {
        if (segment != null && TICK_SEGMENTS.contains(segment)) return true;
        return slug != null && TICK_SLUG.matcher(slug).find();
    }

    /** The path segment of a news url, or {@code null}. */
    static String segmentOf(String url) {
        if (url == null) return null;
        Matcher m = NEWS_PATH.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Article page → {@link Article}: JSON-LD {@code NewsArticle} for the
     * identity, {@code div.news-single__content} for the ~4.300 characters of
     * body. Package-private for tests.
     */
    static Article parseArticle(String url, String html) {
        if (html == null || html.isBlank()) return null;
        JsonNode article = findLdNode(html, "NewsArticle");
        JsonNode image = findLdNode(html, "ImageObject");
        JsonNode person = findLdNode(html, "Person");

        String title = article == null ? null : text(article, "headline");
        String body = contentText(html);
        if (title == null && (body == null || body.isBlank())) return null;

        Matcher m = NEWS_PATH.matcher(url);
        String segment = m.find() ? m.group(1) : null;
        String slug = segment == null ? null : m.group(3);

        return new Article(
                url,
                title,
                article == null ? null : text(article, "description"),
                body,
                article == null ? null : parseDate(text(article, "datePublished")),
                person == null ? null : text(person, "name"),
                image == null ? null : text(image, "url"),
                segment,
                isPriceMoveGenerated(segment, slug),
                SPONSORED_SEGMENT.equals(segment),
                leadTickers(body),
                body != null && body.contains("aus dem Original übersetzt"));
    }

    /** Broker page → {@link BrokerReview} off the {@code Review} JSON-LD. */
    static BrokerReview parseBrokerReview(String url, String html) {
        JsonNode review = findLdNode(html, "Review");
        if (review == null) return null;
        JsonNode rating = review.path("reviewRating");
        JsonNode reviewed = review.path("itemReviewed");
        JsonNode author = review.path("author");
        return new BrokerReview(
                url,
                text(reviewed, "name"),
                text(review, "headline"),
                asDouble(rating.path("ratingValue")),
                asDouble(rating.path("bestRating")),
                text(author, "name"),
                parseDate(text(review, "datePublished")),
                parseDate(text(review, "dateModified")),
                text(review, "reviewBody"));
    }

    /** Ranking page → its {@code ItemList} places, position order. */
    static List<RankingEntry> parseRanking(String html) {
        JsonNode list = findLdNode(html, "ItemList");
        if (list == null) return List.of();
        String name = text(list, "name");
        List<RankingEntry> out = new ArrayList<>();
        for (JsonNode n : list.path("itemListElement")) {
            String broker = text(n, "name");
            if (broker == null) continue;
            out.add(new RankingEntry(name, (int) asDouble(n.path("position")),
                    broker, text(n, "url")));
        }
        out.sort(Comparator.comparingInt(RankingEntry::position));
        return List.copyOf(out);
    }

    /**
     * The body text of {@code div.news-single__content} - a depth-counting scan
     * (the div nests figures, highlight boxes and the JSON-LD script), then
     * scripts out, tags out, entities decoded, whitespace collapsed.
     */
    static String contentText(String html) {
        if (html == null) return null;
        int start = html.indexOf(CONTENT_DIV);
        if (start < 0) return null;
        int from = start + CONTENT_DIV.length();
        int depth = 1;
        int end = -1;
        Matcher m = Pattern.compile("<(/?)div\\b").matcher(html);
        int at = from;
        while (m.find(at)) {
            depth += m.group(1).isEmpty() ? 1 : -1;
            at = m.end();
            if (depth == 0) {
                end = m.start();
                break;
            }
        }
        String inner = end > from ? html.substring(from, end) : html.substring(from);
        inner = SCRIPT_OR_STYLE.matcher(inner).replaceAll(" ");
        inner = TAG.matcher(inner).replaceAll(" ");
        inner = HtmlText.unescapeBasic(inner).replace("&nbsp;", " ").replace("&#39;", "'");
        String text = inner.replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? null : text;
    }

    /** Tickers printed in parentheses inside the article's lead. */
    static List<String> leadTickers(String body) {
        if (body == null || body.isBlank()) return List.of();
        Matcher m = LEAD_TICKER.matcher(body.substring(0, Math.min(LEAD_WINDOW, body.length())));
        Set<String> out = new LinkedHashSet<>();
        while (m.find()) out.add(m.group(1));
        return List.copyOf(out);
    }

    /**
     * The first JSON-LD node of the wanted {@code @type}, walking {@code @graph}
     * containers. Traders Union ships several blocks per page.
     */
    static JsonNode findLdNode(String html, String type) {
        if (html == null) return null;
        Matcher m = LD_JSON.matcher(html);
        while (m.find()) {
            try {
                JsonNode root = JSON.readTree(m.group(1));
                JsonNode hit = findTyped(root, type);
                if (hit != null) return hit;
            } catch (Exception e) {
                LOG.debug("[TradersUnion] unparseable JSON-LD block: {}", e.getMessage());
            }
        }
        return null;
    }

    private static JsonNode findTyped(JsonNode node, String type) {
        if (node == null || node.isMissingNode()) return null;
        if (node.isArray()) {
            for (JsonNode n : node) {
                JsonNode hit = findTyped(n, type);
                if (hit != null) return hit;
            }
            return null;
        }
        if (!node.isObject()) return null;
        if (type.equals(node.path("@type").asText(""))) return node;
        return findTyped(node.get("@graph"), type);
    }

    // ---- small shared helpers ----

    /** Dedupes by article url, first occurrence wins. Package-private for tests. */
    static List<RawNewsItem> mergeByLink(List<RawNewsItem> first, List<RawNewsItem> second) {
        Map<String, RawNewsItem> byLink = new LinkedHashMap<>();
        for (RawNewsItem it : first) byLink.putIfAbsent(cleanLink(it.link()), it);
        for (RawNewsItem it : second) byLink.putIfAbsent(cleanLink(it.link()), it);
        return List.copyOf(byLink.values());
    }

    /** The url without its query - the identity/merge key. */
    static String cleanLink(String link) {
        if (link == null) return "";
        int q = link.indexOf('?');
        return q >= 0 ? link.substring(0, q) : link;
    }

    /** Site-relative link → absolute; anything already absolute passes through. */
    static String absolute(String link) {
        if (link == null || link.isBlank()) return "";
        String v = link.trim();
        if (v.startsWith("http://") || v.startsWith("https://")) return v;
        return v.startsWith("/") ? HOST + v : HOST + "/" + v;
    }

    /** True when the text carries at least one significant word of the queried name. */
    static boolean matches(String text, Set<String> nameWords) {
        if (text == null || nameWords.isEmpty()) return false;
        String t = normalize(text);
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

    /** ISO-8601 with or without offset → {@link Instant}; unparseable → null. */
    static Instant parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (Exception e) {
            try {
                return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (Exception ignored) {
                return null;
            }
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

    private static boolean inWindow(Instant at, Instant from, Instant toExcl) {
        return at != null && !at.isBefore(from) && at.isBefore(toExcl);
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static double asDouble(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return -1;
        if (v.isNumber()) return v.asDouble(-1);
        try {
            return Double.parseDouble(v.asText("").trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** The sitemaps.org namespace - anything else named {@code loc} is not a page url. */
    private static boolean isSitemapNamespace(String uri) {
        return uri == null || uri.isEmpty()
                || uri.startsWith("http://www.sitemaps.org/schemas/sitemap/");
    }

    private static String textOf(XMLStreamReader r) throws Exception {
        String t = r.getElementText();
        return t != null ? t.trim() : null;
    }

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
