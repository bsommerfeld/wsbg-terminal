package de.bsommerfeld.wsbg.terminal.web.impl.sources.fool;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * The Motley Fool as a US news/analysis leg — keyless throughout. Two
 * chronological surfaces feed one pooled window (live-probed 2026-07-13 and
 * 2026-08-02):
 *
 * <ul>
 *   <li><b>News sitemap</b> ({@code www.fool.com/news-sitemap.xml}) — ~250
 *       articles over the last ~2 days, most carrying
 *       {@code <news:stock_tickers>} ({@code NYSE:WELL,NASDAQ:SBRA}) — the
 *       ticker-addressed join no other Fool surface offers — plus an article
 *       image. {@code robots.txt} lists it as the intended crawl surface.</li>
 *   <li><b>Earnings-call transcripts</b>
 *       ({@code www.fool.com/earnings-call-transcripts/}) — full transcripts as
 *       keyless articles, in NEITHER sitemap nor firehose, so the listing
 *       page is scraped: each card is an {@code <h5>} title ("PepsiCo (PEP) Q2
 *       2026 Earnings Call Transcript") right after its article link, the ticker
 *       rides in the title parens and the date in the URL path. The transcript
 *       page itself opens with a structured TAKEAWAYS block — downstream readers
 *       get a model-sized digest before the full Q&A.</li>
 * </ul>
 *
 * <p>(The foolwatch RSS firehose — the third chronological surface of the old
 * world — is a plain RSS feed and rides the curated feed catalog now; its
 * public {@code apikey=foolwatch-feed} is official, {@code fool.com/feeds/index.aspx}
 * 301s to exactly that URL.)
 *
 * <p>Both surfaces are fetched whole and merged into one pool (canonical-link
 * join, tracking query stripped), cached for 10 minutes as a politeness cache —
 * an inquiry may fan several keys in a burst, and Fool should see one request
 * round for it. {@link #newsFor} fans the resolved keys additively over that
 * pool: the symbol leg joins on the sitemap's ticker tags and the transcript
 * title tickers, the name leg is relevance-matched against title AND teaser
 * (precision over recall — the teaser counts because Fool's roundup-style
 * headlines name the companies only in the body text). English-language items —
 * so nothing downstream needs to care.
 *
 * <p>An outage keeps the previous pool alive for another TTL window instead of
 * caching an empty answer — partial news beats no news.
 *
 * <p>{@code robots.txt} (re-read 2026-08-02): the blanket {@code Disallow: /}
 * belongs to MauiBot and Bytespider only. The news sitemap, the transcript
 * listing and {@code /investing/YYYY/…} articles are explicitly listed or
 * free. Off limits and never touched: {@code /investing/stocks/},
 * {@code /premium-reports}, {@code /stock-ranker/}, {@code /api/historical/},
 * {@code /newsletters/*}.
 *
 * <p>Transport {@code BROWSER,DIRECT}: joker-first is the house standard for
 * public websites; fool.com carries no wall today, but the chain costs nothing
 * at this cadence and survives one growing.
 */
@Singleton
public class FoolNewsSource extends AbstractWebSource implements InstrumentSource {

    private static final Logger LOG = LoggerFactory.getLogger(FoolNewsSource.class);

    static final String SITEMAP_URL = "https://www.fool.com/news-sitemap.xml";
    static final String TRANSCRIPTS_URL = "https://www.fool.com/earnings-call-transcripts/";
    static final String PUBLISHER = "The Motley Fool";

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private static final String NS_SITEMAP = "http://www.sitemaps.org/schemas/sitemap/0.9";
    private static final String NS_NEWS = "http://www.google.com/schemas/sitemap-news/0.9";
    private static final String NS_IMAGE = "http://www.google.com/schemas/sitemap-image/1.1";

    /** Hardened StAX factory (XXE off — these are remote feeds), reused for every parse. */
    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    /** Newest first; items without a timestamp sort to the end. */
    private static final Comparator<Article> BY_RECENCY =
            Comparator.comparing(Article::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    private final Duration requestTimeout = Duration.ofSeconds(12);

    private volatile Cached pool;

    private record Cached(Instant fetchedAt, List<Article> items) {}

    @Inject
    public FoolNewsSource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "fool";
    }

    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.BROWSER, FetchUtil.DIRECT};
    }

    /**
     * Additive key fan over the chronological pool, de-duplicated, newest
     * first: the ticker leg joins on the sitemap's {@code <news:stock_tickers>}
     * tags and the transcript titles' parens (Fool tags bare US symbols, so
     * the venue suffix is stripped); the name leg is relevance-matched against
     * title AND teaser.
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        if (instrument == null || limit <= 0) return List.of();
        Map<String, Article> merged = new LinkedHashMap<>();

        instrument.ticker().ifPresent(ticker -> {
            String wanted = ticker.baseSymbol().trim().toUpperCase(Locale.ROOT);
            if (!wanted.isEmpty()) {
                for (Article it : pool()) {
                    if (it.relatedTickers().contains(wanted)) merged.putIfAbsent(it.uuid(), it);
                }
            }
        });

        String name = instrument.name();
        if (name != null && !name.isBlank()) {
            Set<String> words = TextMatch.significantWords(name);
            if (!words.isEmpty()) {
                for (Article it : pool()) {
                    if (TextMatch.matchesAny(haystack(it), words)) merged.putIfAbsent(it.uuid(), it);
                }
            }
        }

        return merged.values().stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /** Title plus teaser — roundup titles name the companies only in the body. */
    private static String haystack(Article it) {
        return it.summary() == null ? it.title() : it.title() + " " + it.summary();
    }

    /** The merged, cached article pool; refreshed at most once per TTL. */
    private List<Article> pool() {
        Cached c = pool;
        if (c != null && c.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return c.items();
        }
        synchronized (this) {
            c = pool;
            if (c != null && c.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
                return c.items();
            }
            List<Article> merged = appendNew(
                    fetchAndParse(SITEMAP_URL, FoolNewsSource::parseSitemap),
                    fetchAndParse(TRANSCRIPTS_URL, FoolNewsSource::parseTranscripts));
            if (merged.isEmpty() && c != null) {
                merged = c.items(); // outage: keep the stale pool over an empty one
            }
            pool = new Cached(Instant.now(), merged);
            return merged;
        }
    }

    private List<Article> fetchAndParse(String url,
            java.util.function.Function<String, List<Article>> parser) {
        try {
            WebResponse resp = get(url,
                    Map.of("Accept", "application/rss+xml, application/xml, text/xml, text/html"),
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

    /** Appends items whose canonical link the pool doesn't carry yet (transcripts vs articles never collide today — belt and suspenders). */
    static List<Article> appendNew(List<Article> base, List<Article> extra) {
        if (extra.isEmpty()) return base;
        Map<String, Article> byLink = new LinkedHashMap<>();
        for (Article it : base) byLink.putIfAbsent(cleanLink(it.link()), it);
        for (Article it : extra) byLink.putIfAbsent(cleanLink(it.link()), it);
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
     * {@code <news:stock_tickers>} become {@link Article#relatedTickers()}
     * (exchange prefix stripped), {@code <image:loc>} the article image.
     * Namespace-disambiguated — {@code news:title} vs {@code image:title} and
     * {@code loc} vs {@code image:loc} share local names. Garbage yields an
     * empty list, never an exception. Package-private for tests.
     */
    static List<Article> parseSitemap(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<Article> out = new ArrayList<>();
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
                            out.add(new Article(loc, title, PUBLISHER, loc,
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
    static List<Article> parseTranscripts(String html) {
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

        Map<String, Article> byLink = new LinkedHashMap<>();
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
            byLink.putIfAbsent(url, new Article(url, title, PUBLISHER, url,
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

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
