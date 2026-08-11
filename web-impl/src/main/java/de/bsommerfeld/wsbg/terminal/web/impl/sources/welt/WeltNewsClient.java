package de.bsommerfeld.wsbg.terminal.web.impl.sources.welt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
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
import java.util.regex.Pattern;

/**
 * WELT (Axel Springer) as the German general-press leg - keyless on every
 * surface, no cookie, no token (probed 2026-08-02). Two legs with different
 * jobs, both ridden additively by {@link #newsFor}:
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
 *       the instrument-addressed door. 50 items per page with
 *       {@code totalResults}.</li>
 * </ul>
 *
 * <h3>Gotchas (all verified live 2026-08-02)</h3>
 * <ul>
 *   <li><b>Akamai wants the FULL Chrome header set on {@code /api/}.</b> The
 *       transport now completes the browser fingerprint (User-Agent, client
 *       hints, {@code Accept-Encoding}) centrally; what stays at THIS call site
 *       is the XHR-shaped profile a real search tab sends - {@code Accept:
 *       application/json}, the {@code Sec-Fetch-Dest/Mode/Site} group as
 *       {@code empty/cors/same-origin} and the {@code Referer}. The RSS feeds
 *       are open on a plain UA.</li>
 *   <li><b>{@code offset} caps at 190.</b> 195 and above answer HTTP 400, so a
 *       query reaches ~240 hits and no further; {@link #MAX_OFFSET} caps hard.</li>
 *   <li><b>The search is stem-based fuzzy AND date-padded.</b> "Rheinmetall"
 *       answers 25.571 hits and its first ~19 results are simply today's
 *       articles ("Rente mit 63", "Wacken"); "Bayern" comes back for "Bayer",
 *       "Rheinpegel" for "Rheinmetall". A term that exists nowhere still
 *       answers 200 with 200 unrelated results. Roughly 30 of 50 top hits
 *       actually carry the term, so the house TITLE-precision cut against
 *       headline AND {@code intro} is MANDATORY, not cosmetic.</li>
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
 * instrument), so the instrument fan takes the NAME key alone rather than
 * pumping noise into a dossier.
 */
@Singleton
public class WeltNewsClient extends AbstractWebSource implements InstrumentSource, ArchiveSource {

    private static final Logger LOG = LoggerFactory.getLogger(WeltNewsClient.class);

    static final String PUBLISHER = "WELT";
    /** Publisher PREFIX for WELT Plus pieces - a body fetch on these yields a 186-char stub. */
    static final String PUBLISHER_GATED = "WELT Plus";

    static final String FEED_URL = "https://www.welt.de/feeds/%s.rss";
    static final String SEARCH_URL = "https://www.welt.de/api/search/%s?offset=%d&type=article";

    /**
     * The wired feeds, verified live 2026-08-02 (path key → label). A section
     * key is the path under {@code /feeds/}, so {@code section/wirtschaft}
     * addresses {@code /feeds/section/wirtschaft.rss}.
     */
    public static final Map<String, String> SECTION_FEEDS = Map.of(
            "section/wirtschaft", "Wirtschaft",
            "section/finanzen", "Finanzen",
            "latest", "Neueste",
            "topnews", "Topnews",
            "section/politik", "Politik");

    /**
     * The {@code restrictBy} time windows the search API accepts (probed:
     * {@code h1} answers 400).
     */
    public static final Set<String> RESTRICT_BY = Set.of("d1", "w1", "m1", "y1", "y2", "y5");

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
    private static final Comparator<Article> BY_RECENCY =
            Comparator.comparing(Article::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    /** {@code Sun, 02 Aug 2026 15:52:58 GMT} - RFC-1123. */
    private static final DateTimeFormatter RSS_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final Duration requestTimeout = Duration.ofSeconds(12);

    @Inject
    public WeltNewsClient(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "welt";
    }

    /** German masthead - a German-language house of the German press sphere. */
    @Override
    public SourceOrigin origin() {
        return SourceOrigin.of("de", "DE");
    }

    /**
     * The direct leg sufficed on every probe - 15 search calls in a burst all
     * answered 200 - but the browser joker stays behind it for the day the
     * Akamai profile tightens.
     */
    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.BROWSER, FetchUtil.DIRECT};
    }

    /**
     * By NAME (the one key WELT can answer): the live feed pool merged with one
     * search page, both cut against headline AND teaser, freshest first. The
     * pool covers what WELT is running right now; the search reaches everything
     * the five polled feeds never carried. Symbol and ISIN keys are deliberately
     * ignored (see the class javadoc).
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        String companyName = instrument.name();
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        List<Article> fromPool = pool().stream()
                .filter(it -> titleMatches(haystack(it), words))
                .toList();
        List<Article> fromSearch = searchPage(companyName, 0).stream()
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
     * density of in-window hits inside the ~240 reachable results. Name-keyed
     * like every other fan of this source (see the class javadoc on ISINs).
     */
    @Override
    public List<Article> newsForWindow(ResolvedInstrument instrument,
            LocalDate fromDate, LocalDate toDateExclusive, int limit) {
        if (instrument == null || limit <= 0) return List.of();
        String companyName = instrument.name();
        if (companyName == null || companyName.isBlank()) return List.of();
        Instant from = startOfDay(fromDate);
        Instant toExcl = startOfDay(toDateExclusive);
        if (from == null || toExcl == null || !from.isBefore(toExcl)) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        String restrictBy = restrictByFor(from);
        List<Article> collected = new ArrayList<>();
        for (int offset : ARCHIVE_OFFSETS) {
            List<Article> batch = searchPage(companyName, offset, restrictBy);
            if (batch.isEmpty()) break;
            for (Article it : batch) {
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

    // ------------------------------------------------------------ fetching

    /** The merged pool over all wired feeds, fetched live - cadence is the caller's job. */
    private List<Article> pool() {
        List<Article> merged = new ArrayList<>();
        for (String key : SECTION_FEEDS.keySet()) merged.addAll(fetchFeed(key));
        return mergeByLink(merged, List.of());
    }

    private List<Article> fetchFeed(String key) {
        String url = String.format(FEED_URL, key);
        try {
            WebResponse resp = get(url,
                    Map.of("Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp.status() == 200) {
                return parseFeed(resp.body());
            }
            LOG.debug("WELT feed {} answered status {}", key, resp.status());
        } catch (Exception e) {
            LOG.debug("WELT feed {} failed: {}", key, e.getMessage());
        }
        return List.of();
    }

    /** One search page, offset capped at {@link #MAX_OFFSET} (195+ → HTTP 400). */
    private List<Article> searchPage(String query, int offset) {
        return searchPage(query, offset, null);
    }

    /**
     * One search page with an optional {@code restrictBy} time window. An
     * empty-valued or unknown parameter is OMITTED - WELT 400s on it.
     */
    private List<Article> searchPage(String query, int offset, String restrictBy) {
        if (query == null || query.isBlank()) return List.of();
        StringBuilder url = new StringBuilder(String.format(SEARCH_URL,
                URLEncoder.encode(query.trim(), StandardCharsets.UTF_8),
                Math.max(0, Math.min(offset, MAX_OFFSET))));
        if (restrictBy != null && RESTRICT_BY.contains(restrictBy)) {
            url.append("&restrictBy=").append(restrictBy);
        }
        try {
            WebResponse resp = get(url.toString(), searchHeaders(), requestTimeout);
            if (resp.status() == 200) {
                return parseSearch(resp.body());
            }
            LOG.debug("WELT search answered status {}", resp.status());
        } catch (Exception e) {
            LOG.debug("WELT search failed: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * The call-site half of the Akamai handshake on {@code /api/}: the
     * XHR-shaped profile of a real search tab. Dropping the {@code Sec-Fetch-*}
     * group - or sending nothing but a User-Agent - answered 403 (both probed
     * 2026-08-02). The identity half (User-Agent, {@code sec-ch-ua} client
     * hints, {@code Accept-Encoding}) is the TRANSPORT's job now and is kept
     * internally consistent there. {@code Referer} is NOT required (200 without
     * it) but a real browser sends one, so it stays in the set.
     */
    Map<String, String> searchHeaders() {
        return Map.ofEntries(
                Map.entry("Accept", "application/json, text/plain, */*"),
                Map.entry("Accept-Language", "de-DE,de;q=0.9,en;q=0.8"),
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
    static List<Article> parseFeed(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<Article> out = new ArrayList<>();
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
                        out.add(new Article(
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
    static List<Article> parseSearch(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Article> out = new ArrayList<>();
        try {
            JsonNode items = MAPPER.readTree(json).path("items");
            for (JsonNode n : items) {
                String title = text(n, "headline");
                String url = text(n, "url");
                if (title == null || url == null) continue;
                if (isVideo(text(n, "subType"))) continue;

                String id = text(n, "id");
                boolean premium = n.path("premium").asBoolean(false) || isPremiumUrl(url);
                out.add(new Article(
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
    public static boolean isGated(Article item) {
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
    static List<Article> mergeByLink(List<Article> first, List<Article> second) {
        Map<String, Article> byLink = new LinkedHashMap<>();
        for (Article it : first) byLink.putIfAbsent(cleanLink(it.link()), it);
        for (Article it : second) byLink.putIfAbsent(cleanLink(it.link()), it);
        return List.copyOf(byLink.values());
    }

    /** The article URL without its tracking query - the identity/merge key. */
    static String cleanLink(String link) {
        if (link == null) return "";
        int q = link.indexOf('?');
        return q >= 0 ? link.substring(0, q) : link;
    }

    /** Headline plus teaser - WELT headlines are teasing, the {@code intro} names the company. */
    private static String haystack(Article it) {
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

    /** UTC start of day, or null for a missing bound. */
    static Instant startOfDay(LocalDate day) {
        return day == null ? null : day.atStartOfDay(ZoneOffset.UTC).toInstant();
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

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
