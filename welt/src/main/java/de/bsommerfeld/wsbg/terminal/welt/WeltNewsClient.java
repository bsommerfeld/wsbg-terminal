package de.bsommerfeld.wsbg.terminal.welt;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * WELT (Axel Springer) as the German general-press leg - keyless on every
 * surface, no cookie, no token (probed 2026-08-02). Two legs with different
 * jobs, plus a third door that is public but not yet mobilised:
 *
 * <ul>
 *   <li><b>Section feeds</b> ({@code www.welt.de/feeds/<key>.rss}) - the live
 *       firehose. 20-30 items per feed with a teaser {@code description}, a
 *       stable {@code guid}, WELT's own {@code welt:premium} paywall flag,
 *       {@code welt:subType} and {@code dc:source} (the agency chain, e.g.
 *       {@code dpa/ceb}). Only the five feeds in {@link #SECTION_FEEDS} are
 *       polled - the sibling feeds {@code wirtschaft/finanzen},
 *       {@code wirtschaft/energie} and {@code wirtschaft/bilanz} answer 200 but
 *       have been FROZEN since 2019, and {@code wirtschaft/boerse},
 *       {@code geld/boerse}, {@code gruenderszene}, {@code wissen} and
 *       {@code /feeds/videos.rss} are 404. A dead feed costs a fetch and
 *       returns nothing.</li>
 *   <li><b>Search API</b> ({@code www.welt.de/api/search/<term>?offset=…}) -
 *       the instrument-addressed door AND the multi-year archive. 50 items per
 *       page with {@code totalResults}, reaching back to at least 2007.</li>
 *   <li><b>{@link #search(String, int, String, String, int)}</b> - the raw
 *       search door with WELT's own {@code restrictBy} time window and
 *       {@code section} filter. Public and deliberately unused today: it is the
 *       seam for "what did WELT write about &lt;topic&gt; in &lt;window&gt;"
 *       queries that no caller issues yet.</li>
 * </ul>
 *
 * <h3>Gotchas (all verified live 2026-08-02)</h3>
 * <ul>
 *   <li><b>Akamai wants the FULL Chrome header set.</b> A bare {@code User-Agent}
 *       answers 403, and so does a set that carries {@code sec-ch-ua} but drops
 *       the {@code Sec-Fetch-Dest/Mode/Site} group - and so does the otherwise
 *       complete set with {@code Accept-Encoding} missing, which is exactly what
 *       a JDK HTTP client sends by default. {@link #searchHeaders()} sends
 *       everything and keeps {@code sec-ch-ua} consistent with the chosen Chrome
 *       {@code User-Agent} - an inconsistent set is a fingerprint again. The RSS
 *       feeds are open on a plain UA.</li>
 *   <li><b>{@code offset} caps at 190.</b> 195 and above answer HTTP 400, so a
 *       query reaches ~240 hits and no further; {@link #MAX_OFFSET} caps hard.</li>
 *   <li><b>EMPTY query parameters are rejected.</b> {@code restrictBy=} or
 *       {@code section=} with no value answers 400 - an omitted parameter is
 *       the only way to say "no filter". {@code restrictBy} is a closed enum
 *       ({@link #RESTRICT_BY}), {@code section} likewise ({@link #SEARCH_SECTIONS},
 *       note it has no {@code finanzen} member).</li>
 *   <li><b>The search is stem-based fuzzy AND date-padded.</b> "Rheinmetall"
 *       answers 25.571 hits and its first ~19 results are simply today's
 *       articles ("Rente mit 63", "Wacken"); "Bayern" comes back for "Bayer",
 *       "Rheinpegel" for "Rheinmetall". A term that exists nowhere still
 *       answers 200 with 200 unrelated results. Roughly 30 of 50 top hits
 *       actually carry the term, so the house TITLE-precision cut against
 *       headline AND {@code intro} is MANDATORY, not cosmetic.</li>
 *   <li><b>Results are not chronological.</b> {@code sort=date} is accepted and
 *       ignored, so an archive window cannot page "until it falls out of the
 *       window" - {@link #newsForNameWindow} walks every reachable page and
 *       filters, narrowing the corpus through {@code restrictBy} where the
 *       window allows.</li>
 * </ul>
 *
 * <h3>Paywall</h3>
 * WELT Plus bodies are a 186-character teaser, so a downstream full-text reader
 * would burn a fetch on nothing. Both legs mark them BEFORE the fetch, over two
 * independent signals: the {@code welt:premium} element / {@code premium} field,
 * and the URL shape ({@code /plus<id>/} for gated, {@code /article<id>/} for
 * free). A gated item keeps its headline and teaser but is published under a
 * {@link #PUBLISHER_GATED}-prefixed publisher, testable via {@link #isGated} -
 * the headline is the part that carries the signal anyway. Where WELT names the
 * agency chain in {@code dc:source}, it is appended to the publisher
 * ({@code "WELT (dpa/ceb)"}) so the wire origin survives into the loom.
 *
 * <p><b>Video entries are dropped</b> ({@code welt:subType: video}) - the page
 * is a player, not an article. <b>Advertorials</b> ({@code subType: advertorial})
 * survive but are flagged {@code sponsored}.
 *
 * <p>Name-addressed only. WELT tags no tickers, and while the search endpoint
 * answers 200 for an ISIN it returns the same ~200-item "no real hits" filler as
 * a nonsense term (probed with {@code DE0007236101}: 200 results, none about the
 * instrument), so {@link #newsFor} and {@link #newsForIsin} stay no-ops rather
 * than pumping noise into a dossier.
 */
@Singleton
public class WeltNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(WeltNewsClient.class);

    static final String PUBLISHER = "WELT";
    /** Publisher PREFIX for WELT Plus pieces - a body fetch on these yields a 186-char stub. */
    static final String PUBLISHER_GATED = "WELT Plus";

    static final String FEED_URL = "https://www.welt.de/feeds/%s.rss";
    static final String SEARCH_URL = "https://www.welt.de/api/search/%s?offset=%d&type=article";

    /**
     * The wired feeds, verified live 2026-08-02 (path key → label). A section
     * key is the path under {@code /feeds/}, so {@code section/wirtschaft}
     * addresses {@code /feeds/section/wirtschaft.rss}. Public so a general
     * news-stream caller can enumerate what this source offers without knowing
     * WELT's URL shapes.
     *
     * <p>Only these five are polled. The other populated sections
     * ({@code section/politik/deutschland}, {@code section/wissenschaft},
     * {@code section/sport}, {@code section/vermischtes}, {@code section/kultur},
     * {@code section/regionales}, {@code section/debatte},
     * {@code section/geschichte}, {@code section/iconist}, {@code section/reise},
     * {@code section/mediathek}, {@code section/sonderthemen},
     * {@code section/wirtschaft/businessinsider}) are reachable through
     * {@link #section(String, int)} on demand - they carry no market signal
     * often enough to earn a slot in the poll.
     */
    public static final Map<String, String> SECTION_FEEDS = Map.of(
            "section/wirtschaft", "Wirtschaft",
            "section/finanzen", "Finanzen",
            "latest", "Neueste",
            "topnews", "Topnews",
            "section/politik", "Politik");

    /**
     * The {@code restrictBy} time windows the search API accepts (probed:
     * {@code h1} answers 400). Public because {@link #search} takes one.
     */
    public static final Set<String> RESTRICT_BY = Set.of("d1", "w1", "m1", "y1", "y2", "y5");

    /**
     * The {@code section} enum the search API accepts - WELT prints the list
     * verbatim in its 400 body. Note there is NO {@code finanzen} member: money
     * stories live under {@code wirtschaft} on the search leg even though they
     * have their own RSS feed.
     */
    public static final Set<String> SEARCH_SECTIONS = Set.of(
            "debatte", "geschichte", "iconist", "kultur", "mediathek", "politik",
            "regional", "reise", "sonderthemen", "sport", "vermischtes", "wirtschaft", "wissen");

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /** {@code offset=195} and above answer HTTP 400 - this is a hard wall. */
    static final int MAX_OFFSET = 190;
    static final int PAGE_SIZE = 50;

    /** Reachable search offsets, in walk order. 190 overlaps 150 - the merge dedupes. */
    static final int[] ARCHIVE_OFFSETS = {0, 50, 100, 150, 190};

    /** Generic words that must never carry the title-relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "kgaa", "gmbh", "holding", "holdings", "group", "gruppe",
            "international", "aktie", "aktien", "stock", "stocks", "shares");

    /** {@code …/plus6a6dd2ccef632fe6ede67ae0/chinas-weg…html} - the gated URL shape. */
    private static final Pattern PLUS_URL = Pattern.compile("/plus[0-9a-zA-Z]+/");

    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Newest first; items without a timestamp sort to the end. */
    private static final Comparator<RawNewsItem> BY_RECENCY =
            Comparator.comparing(RawNewsItem::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    /** {@code Sun, 02 Aug 2026 15:52:58 GMT} - RFC-1123. */
    private static final DateTimeFormatter RSS_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final String userAgent;
    private final String secChUa;
    private final String platform;
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** One cache slot per feed key, so the pool and {@link #section} share fetches. */
    private final Map<String, Cached> feedCache = new ConcurrentHashMap<>();

    private record Cached(Instant fetchedAt, List<RawNewsItem> items) {}

    /** Test/default: plain direct transport. */
    public WeltNewsClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: rides the shared standard {@link WebFetcher} chain
     * (browser → direct). The direct leg suffices today - 15 search calls in a
     * burst all answered 200 - but the chain costs nothing at this cadence and
     * survives the Akamai profile tightening.
     */
    @Inject
    public WeltNewsClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
        String ua = pickChromeUserAgent();
        this.userAgent = ua;
        this.secChUa = secChUaFor(ua);
        this.platform = ua.contains("Windows") ? "\"Windows\"" : "\"macOS\"";
    }

    @Override
    public String sourceName() {
        return "welt";
    }

    /**
     * Dossier leg. The value here is the archive back to 2007, not the
     * live minute - and the search is padded with same-day filler that only
     * the title cut keeps usable. Depth for a dossier, noise on the wire
     * (2026-08-03).
     */
    @Override
    public boolean dossierOnly() {
        return true;
    }

    // ---------------------------------------------------------------- fans

    /**
     * No-op: WELT tags no tickers on either surface. Feeding a bare symbol into
     * a stem-based fuzzy search would answer every passing mention of a
     * three-letter word - and WELT answers 200 even for terms that exist
     * nowhere, so there is not even a "no hits" signal to lean on.
     */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /**
     * No-op: the search endpoint answers 200 for an ISIN, but the payload is the
     * same ~200-item filler a nonsense term produces (probed 2026-08-02 with
     * {@code DE0007236101} - K+S articles from 2010/2011 with no ISIN anywhere).
     * WELT indexes no ISINs; the name fan carries this source.
     */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        return List.of();
    }

    /**
     * By name: the cached feed pool merged with one live search page, both cut
     * against headline AND teaser, freshest first. The pool answers instantly
     * and covers what WELT is running right now; the search reaches everything
     * the five polled feeds never carried.
     */
    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        List<RawNewsItem> fromPool = pool().stream()
                .filter(it -> titleMatches(haystack(it), words))
                .toList();
        List<RawNewsItem> fromSearch = searchPage(companyName, 0, null, null).stream()
                .filter(it -> titleMatches(haystack(it), words))
                .toList();

        return mergeByLink(fromPool, fromSearch).stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /**
     * ARCHIVE window over the search leg, reaching back to at least 2007.
     *
     * <p>WELT's ranking is neither chronological nor honestly relevance-ordered,
     * so this walks EVERY reachable page ({@link #ARCHIVE_OFFSETS}) and filters
     * locally instead of stopping at the first out-of-window hit. Where the
     * window's lower bound is recent enough, {@code restrictBy} narrows the
     * corpus server-side first ({@link #restrictByFor}), which raises the
     * density of in-window hits inside the ~240 reachable results.
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

        String restrictBy = restrictByFor(from);
        List<RawNewsItem> collected = new ArrayList<>();
        for (int offset : ARCHIVE_OFFSETS) {
            List<RawNewsItem> batch = searchPage(companyName, offset, restrictBy, null);
            if (batch.isEmpty()) break;
            for (RawNewsItem it : batch) {
                Instant at = it.publishedAt();
                if (at == null || at.isBefore(from) || !at.isBefore(toExcl)) continue;
                if (titleMatches(haystack(it), words)) collected.add(it);
            }
        }
        return mergeByLink(collected, List.of()).stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    // ------------------------------------------------- general news stream

    /**
     * The GENERAL stream: everything the five polled feeds carry right now,
     * deduped and newest first - WELT's own front page as a list, with no
     * instrument in the question. Served straight from the same TTL pool the
     * instrument fans use, so it never costs an extra fetch.
     */
    public List<RawNewsItem> latest(int limit) {
        if (limit <= 0) return List.of();
        return pool().stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * One named section, newest first. {@code feedKey} is a key of
     * {@link #SECTION_FEEDS} or any other live WELT feed path (e.g.
     * {@code section/wissenschaft}, {@code section/politik/ausland}) - the wired
     * five come out of the shared pool cache, anything else is fetched on demand
     * and then cached under the same TTL.
     */
    public List<RawNewsItem> section(String feedKey, int limit) {
        if (feedKey == null || feedKey.isBlank() || limit <= 0) return List.of();
        return feed(feedKey.trim()).stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * The RAW search door - full-text over WELT's archive, unfiltered by any
     * instrument name. Public and currently unused: it is the seam for topic
     * queries ("Zinswende", "Chipfabrik") that no caller issues today.
     *
     * <p>No title-precision cut is applied here (the caller decides what
     * "precise" means for its term), so expect WELT's fuzzy padding in the
     * result - see the class doc.
     *
     * @param query      the search term; blank yields an empty list
     * @param offset     paging offset, capped at {@link #MAX_OFFSET} (195+ → HTTP 400)
     * @param restrictBy a member of {@link #RESTRICT_BY}, or {@code null} for the
     *                   full archive; an unknown value is dropped rather than sent
     *                   (an empty or bogus value answers HTTP 400)
     * @param section    a member of {@link #SEARCH_SECTIONS}, or {@code null};
     *                   unknown values are likewise dropped
     */
    public List<RawNewsItem> search(String query, int offset, String restrictBy,
                                    String section, int limit) {
        if (limit <= 0) return List.of();
        return searchPage(query, offset, restrictBy, section).stream().limit(limit).toList();
    }

    // ------------------------------------------------------------ fetching

    /** The merged pool over all wired feeds; each feed refreshed at most once per TTL. */
    private List<RawNewsItem> pool() {
        List<RawNewsItem> merged = new ArrayList<>();
        for (String key : SECTION_FEEDS.keySet()) merged.addAll(feed(key));
        return mergeByLink(merged, List.of());
    }

    /** One feed, cached per key. An outage keeps the previous items over an empty list. */
    private List<RawNewsItem> feed(String key) {
        Cached c = feedCache.get(key);
        if (c != null && c.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return c.items();
        }
        synchronized (feedCache) {
            c = feedCache.get(key);
            if (c != null && c.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
                return c.items();
            }
            List<RawNewsItem> fresh = fetchFeed(key);
            if (fresh.isEmpty() && c != null) {
                fresh = c.items(); // outage: keep the stale feed over an empty one
            }
            feedCache.put(key, new Cached(Instant.now(), fresh));
            return fresh;
        }
    }

    private List<RawNewsItem> fetchFeed(String key) {
        String url = String.format(FEED_URL, key);
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                return parseFeed(resp.body());
            }
            LOG.debug("WELT feed {} answered status {}", key,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("WELT feed {} failed: {}", key, e.getMessage());
        }
        return List.of();
    }

    /** One search page. Empty-valued parameters are OMITTED - WELT 400s on them. */
    private List<RawNewsItem> searchPage(String query, int offset, String restrictBy,
                                         String section) {
        if (query == null || query.isBlank()) return List.of();
        StringBuilder url = new StringBuilder(String.format(SEARCH_URL,
                URLEncoder.encode(query.trim(), StandardCharsets.UTF_8),
                Math.max(0, Math.min(offset, MAX_OFFSET))));
        if (restrictBy != null && RESTRICT_BY.contains(restrictBy)) {
            url.append("&restrictBy=").append(restrictBy);
        }
        if (section != null && SEARCH_SECTIONS.contains(section)) {
            url.append("&section=").append(section);
        }
        try {
            WebResponse resp = fetcher.fetch(url.toString(), searchHeaders(), requestTimeout);
            if (resp != null && resp.status() == 200) {
                return parseSearch(resp.body());
            }
            LOG.debug("WELT search answered status {}", resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("WELT search failed: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * The FULL Chrome header set Akamai demands on {@code /api/}. Dropping the
     * {@code Sec-Fetch-*} group - or sending nothing but a User-Agent - answers
     * 403 (both probed 2026-08-02). {@code sec-ch-ua} is derived from the very
     * User-Agent this instance sends, so the set stays internally consistent.
     *
     * <p><b>{@code Accept-Encoding} is part of the fingerprint too</b> and is the
     * one header a JDK {@link java.net.http.HttpClient} does not send by itself:
     * the same set answered 403 without it and 200 with it, over HTTP/2 and
     * HTTP/1.1 alike. It is sent as {@code identity} rather than
     * {@code gzip, deflate, br} because the JDK client decompresses nothing - a
     * gzip body would arrive as mojibake. {@code identity} satisfies Akamai
     * (probed: 200, {@code application/json}) and keeps the body readable.
     * {@code Referer} is NOT required (200 without it) but a real browser sends
     * one, so it stays in the set.
     */
    Map<String, String> searchHeaders() {
        return Map.ofEntries(
                Map.entry("User-Agent", userAgent),
                Map.entry("Accept", "application/json, text/plain, */*"),
                Map.entry("Accept-Language", "de-DE,de;q=0.9,en;q=0.8"),
                Map.entry("Accept-Encoding", "identity"),
                Map.entry("sec-ch-ua", secChUa),
                Map.entry("sec-ch-ua-mobile", "?0"),
                Map.entry("sec-ch-ua-platform", platform),
                Map.entry("Sec-Fetch-Dest", "empty"),
                Map.entry("Sec-Fetch-Mode", "cors"),
                Map.entry("Sec-Fetch-Site", "same-origin"),
                Map.entry("Referer", "https://www.welt.de/suche/"));
    }

    // ------------------------------------------------------------- parsing

    /**
     * Parses a WELT RSS feed. {@code guid} is the stable article id (not a
     * permalink), {@code welt:premium} the paywall flag, {@code welt:subType}
     * filters video entries and flags advertorials, {@code dc:source} carries
     * the agency chain. Package-private for tests.
     */
    static List<RawNewsItem> parseFeed(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null, link = null, guid = null, pubDate = null, description = null,
                    premium = null, subType = null, source = null, image = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = guid = pubDate = description = null;
                            premium = subType = source = image = null;
                        } else if (inItem) {
                            switch (ln) {
                                case "title" -> title = textOf(r);
                                case "link" -> link = textOf(r);
                                case "guid" -> guid = textOf(r);
                                case "pubDate" -> pubDate = textOf(r);
                                case "description" -> description = textOf(r);
                                case "premium" -> premium = textOf(r);
                                case "subType" -> subType = textOf(r);
                                case "source" -> source = textOf(r);
                                case "content" -> {
                                    if (image == null) image = r.getAttributeValue(null, "url");
                                }
                                default -> { /* category, creator, topic, keywords - not needed */ }
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT
                            && "item".equals(r.getLocalName())) {
                        inItem = false;
                        if (title == null || title.isEmpty() || link == null || link.isEmpty()) {
                            continue;
                        }
                        if (isVideo(subType)) continue;
                        out.add(new RawNewsItem(
                                guid != null && !guid.isEmpty() ? guid : link,
                                title,
                                publisherFor(isPremium(premium, link), source),
                                link,
                                parseRssDate(pubDate),
                                List.of(),
                                null,
                                blankToNull(description),
                                isAdvertorial(subType),
                                blankToNull(image)));
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.warn("Unparseable WELT feed: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    /**
     * Parses a search page ({@code items[]}). Gated pieces keep their headline
     * but are published under a {@link #PUBLISHER_GATED} publisher; video
     * entries drop out (they only appear when {@code type=article} is omitted).
     * Package-private for tests.
     */
    static List<RawNewsItem> parseSearch(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            JsonNode items = MAPPER.readTree(json).path("items");
            for (JsonNode n : items) {
                String title = text(n, "headline");
                String url = text(n, "url");
                if (title == null || url == null) continue;
                if (isVideo(text(n, "subType"))) continue;

                String id = text(n, "id");
                boolean premium = n.path("premium").asBoolean(false) || isPremiumUrl(url);
                out.add(new RawNewsItem(
                        id != null ? id : url,
                        title,
                        publisherFor(premium, null),
                        url,
                        parseSearchDate(text(n, "publicationDate")),
                        List.of(),
                        null,
                        text(n, "intro"),
                        n.path("sponsored").asBoolean(false)
                                || isAdvertorial(text(n, "subType")),
                        text(n, "teaserImage")));
            }
        } catch (Exception e) {
            LOG.warn("Unparseable WELT search payload: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    // -------------------------------------------------------------- paywall

    /**
     * True when the piece sits behind WELT Plus - over BOTH independent signals:
     * the feed's {@code welt:premium} / search {@code premium} flag, and the
     * {@code /plus<id>/} URL shape. Either one alone is enough; the two agreed
     * on every one of the 190 items probed, but a missing flag must not smuggle
     * a walled body into a full-text reader.
     */
    static boolean isPremium(String flag, String url) {
        return "true".equalsIgnoreCase(flag) || isPremiumUrl(url);
    }

    /** {@code …/plus6a6d…/slug.html} is gated, {@code …/article6a6d…/slug.html} is free. */
    static boolean isPremiumUrl(String url) {
        return url != null && PLUS_URL.matcher(url).find();
    }

    /** True when this item's body is walled - the downstream reader's skip test. */
    public static boolean isGated(RawNewsItem item) {
        return item != null && item.publisher() != null
                && item.publisher().startsWith(PUBLISHER_GATED);
    }

    /**
     * {@code "WELT"} / {@code "WELT Plus"}, with the agency chain appended where
     * {@code dc:source} named one ({@code "WELT (dpa/ceb)"}) - the wire origin is
     * editorially meaningful and would otherwise be lost.
     */
    static String publisherFor(boolean premium, String source) {
        String base = premium ? PUBLISHER_GATED : PUBLISHER;
        String s = blankToNull(source);
        return s == null ? base : base + " (" + s + ")";
    }

    // --------------------------------------------------------------- shared

    static boolean isVideo(String subType) {
        return subType != null && subType.trim().equalsIgnoreCase("video");
    }

    static boolean isAdvertorial(String subType) {
        return subType != null && subType.trim().equalsIgnoreCase("advertorial");
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

    /** Headline plus teaser - WELT headlines are teasing, the {@code intro} names the company. */
    private static String haystack(RawNewsItem it) {
        return it.summary() == null ? it.title() : it.title() + " " + it.summary();
    }

    /**
     * The narrowest {@code restrictBy} window that still covers everything from
     * {@code from} onwards, or {@code null} when the window reaches further back
     * than WELT's widest bucket ({@code y5}).
     */
    static String restrictByFor(Instant from) {
        if (from == null) return null;
        Duration age = Duration.between(from, Instant.now());
        if (age.isNegative()) return "d1";
        long days = age.toDays();
        if (days <= 1) return "d1";
        if (days <= 7) return "w1";
        if (days <= 31) return "m1";
        if (days <= 366) return "y1";
        if (days <= 731) return "y2";
        if (days <= 1827) return "y5";
        return null;
    }

    /** {@code Sun, 02 Aug 2026 15:52:58 GMT} → {@link Instant}; unparseable → null. */
    static Instant parseRssDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return ZonedDateTime.parse(s.trim(), RSS_DATE).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code 2026-08-02T14:01:56.910Z} → {@link Instant}; unparseable → null. */
    static Instant parseSearchDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s.trim()).toInstant();
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

    /**
     * True when the text carries at least one significant word of the queried
     * name as a WHOLE word - the cut that keeps "Rheinpegel" out of a
     * Rheinmetall dossier and "Bayern" out of a Bayer one.
     */
    static boolean titleMatches(String text, Set<String> nameWords) {
        if (text == null || nameWords.isEmpty()) return false;
        String t = normalize(text);
        for (String w : nameWords) {
            if (Pattern.compile("\\b" + Pattern.quote(w) + "\\b").matcher(t).find()) return true;
        }
        return false;
    }

    /** Significant (length ≥ 3, non-generic) words of the queried name. */
    static Set<String> significantWords(String name) {
        Set<String> out = new LinkedHashSet<>();
        if (name == null) return out;
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String textOf(XMLStreamReader r) throws Exception {
        String t = r.getElementText();
        return t != null ? t.trim() : null;
    }

    /**
     * Word-boundary matching has to see German compounds as one token, so
     * umlauts fold to their ASCII pair rather than being stripped.
     */
    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    /**
     * A CHROME User-Agent from the shared pool - the {@code sec-ch-ua} header
     * only exists in Chromium browsers, so a Firefox or Safari string would make
     * the very header set Akamai checks internally inconsistent.
     */
    static String pickChromeUserAgent() {
        for (String ua : BrowserUserAgent.pool()) {
            if (ua.contains("Chrome/") && !ua.contains("Edg/")) return ua;
        }
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    }

    /** The {@code sec-ch-ua} brand list for exactly the Chrome version in {@code ua}. */
    static String secChUaFor(String ua) {
        String major = "131";
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(ua == null ? "" : ua);
        if (m.find()) major = m.group(1);
        return "\"Google Chrome\";v=\"" + major + "\", \"Chromium\";v=\"" + major
                + "\", \"Not_A Brand\";v=\"24\"";
    }

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
