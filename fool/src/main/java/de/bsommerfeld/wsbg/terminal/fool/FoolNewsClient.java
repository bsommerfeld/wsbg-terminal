package de.bsommerfeld.wsbg.terminal.fool;

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
import java.util.regex.Pattern;

/**
 * The Motley Fool as a US news/analysis leg — keyless via two public feeds
 * (both live-probed 2026-07-13):
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
 * <p>Both feeds are fetched whole and merged into one pool (canonical-link
 * join, tracking query stripped), cached for 10 minutes — the sitemap leg
 * contributes tickers and image, the firehose leg the teaser. {@link #newsFor}
 * answers by ticker from the sitemap tags; {@link #newsForName} by
 * title-relevance match (the google-news precision rule: a significant word of
 * the name must appear in the title). English-language items — same as the
 * Yahoo leg, so nothing downstream needs to care.
 *
 * <p>An outage keeps the previous pool alive for another TTL window instead of
 * caching an empty answer — partial news beats no news.
 */
@Singleton
public class FoolNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(FoolNewsClient.class);

    static final String SITEMAP_URL = "https://www.fool.com/news-sitemap.xml";
    static final String FEED_URL =
            "https://api.fool.com/feeds/foolwatch?apikey=foolwatch-feed&format=rss2";
    static final String TRANSCRIPTS_URL = "https://www.fool.com/earnings-call-transcripts/";
    static final String PUBLISHER = "The Motley Fool";

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

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

    private record Cached(Instant fetchedAt, List<RawNewsItem> items) {}

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

    /** By ticker, from the sitemap's {@code <news:stock_tickers>} tags. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        if (symbol == null || symbol.isBlank() || limit <= 0) return List.of();
        String wanted = symbol.trim().toUpperCase(Locale.ROOT);
        return pool().stream()
                .filter(it -> it.relatedTickers().contains(wanted))
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
