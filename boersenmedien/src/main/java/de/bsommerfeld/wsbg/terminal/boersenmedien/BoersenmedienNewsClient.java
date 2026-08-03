package de.bsommerfeld.wsbg.terminal.boersenmedien;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.core.util.HtmlText;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * Börsenmedien AG as the GERMAN retail-press leg - <b>Börse Online</b> and
 * <b>Der Aktionär</b> in ONE client, because they are one backend wearing two
 * brands (probed live 2026-08-02).
 *
 * <p><b>The core finding.</b> {@code boerse-online.de/symbol/remote?s=<ISIN>}
 * and {@code deraktionaer.de/api/remote/symbols/?s=<ISIN>} answer byte-for-byte
 * identical JSON, the article ids interleave across both houses (a Börse Online
 * piece is 20405851, a Der Aktionär piece 20405903 - one global CMS id space),
 * and the wire pieces carry the SAME id under both roofs
 * ({@code /dpa-afx/…-542040.html} on Börse Online is
 * {@code /nachricht/…-542040.html} on Der Aktionär). Two modules would have
 * meant two clients duplicating one parser and no cross-house dedupe - so this
 * is one source with four instrument legs, and {@link #articleId} is the merge
 * key that collapses the wire duplicates.
 *
 * <p><b>No wall, no key, no cookie.</b> Every surface answers 200 with and
 * without a User-Agent. {@code robots.txt} disallows only {@code /account/}
 * (Börse Online) resp. {@code /account/} and {@code /suchen*} (Der Aktionär) -
 * nothing this client touches.
 *
 * <p><b>There is no RSS.</b> Neither house publishes a feed (37 candidate paths
 * per host, all 404). The replacement is paginated per-ISIN news lists, four of
 * them, merged:
 *
 * <ul>
 *   <li>{@code boerse-online.de/aktie/x-<ISIN>/nachrichten/<n>} - Börse Online
 *       editorial, 30 per page, entirely free in every sample.</li>
 *   <li>{@code boerse-online.de/aktie/x-<ISIN>/dpa-afx/<n>} - the dpa-AFX wire,
 *       30 per page and deep (Siemens ≈ 2.386 pieces back to 2020).</li>
 *   <li>{@code deraktionaer.de/aktien/artikel/x-<ISIN>/<n>} - Der Aktionär
 *       editorial, 30 per page, part of it behind the Plus wall.</li>
 *   <li>{@code deraktionaer.de/aktien/nachrichten/x-<ISIN>/<n>} - the same wire
 *       under the Der Aktionär roof, always free.</li>
 * </ul>
 *
 * <p>The URL slug is decorative: {@code x-<ISIN>} is enough, the backend keys
 * off the ISIN alone.
 *
 * <p><b>Paywall is decided BEFORE the body fetch.</b> Roughly half of Der
 * Aktionär's own pieces are Plus. Three independent tells, any one of which is
 * enough: the path segment {@code /artikel/deraktionaerplus/} (list markup),
 * the segment {@code /artikel/der-aktionaer-plus/} (sitemap spelling of the
 * same rubric), and the {@code plus-article} CSS class on the teaser figure.
 * Such items keep their headline but are published as {@link #PUBLISHER_DA_PLUS},
 * so a full-text reader skips the body instead of burning a fetch on a stub.
 * {@link #isGatedArticleHtml} is the fourth tell for a reader that already HAS
 * the article page: the JSON-LD carries {@code isAccessibleForFree:false}
 * (Börse Online articles carry no such flag at all - they are free).
 *
 * <p><b>GOTCHA - pagination never runs out.</b> Asking for a page past the last
 * one does NOT answer 404 or an empty list: the backend CLAMPS and re-serves
 * the last page forever (page 50, 80, 2000 and 5000 of the Siemens wire all
 * returned the identical 16 items from 2020-07-13). "Stop when the page is
 * empty" would loop until the page cap every single time, so every walk here
 * stops on a REPEATED first article id as well as on the date bound and a hard
 * page cap.
 *
 * <p><b>GOTCHA - {@code data-quote} is the article's PRIMARY instrument, not
 * the queried one.</b> A Siemens list happily serves a market round-up whose
 * badge reads {@code DE0006202005} (Salzgitter). The badge is therefore read as
 * the item's own ISIN tag, while the ISIN that ADDRESSED the list is what the
 * item is attributed to - filtering the list against the queried ISIN would
 * throw away exactly the multi-name pieces a desk wants.
 *
 * <p><b>The archive reaches 2003.</b> Der Aktionär publishes 131 article
 * sitemaps of 1.000 URLs each, Börse Online 73 - and they carry
 * {@code news:publication_date} plus {@code news:title}, sorted ascending, so a
 * dated window is a binary search over sitemap files rather than a crawl. That
 * is 23 years of German retail press, far past the ~2020 floor of the ISIN
 * lists.
 *
 * <p><b>Two ways in.</b> Beside the instrument fans this client exposes the
 * house's GENERAL stream - {@link #latest}, {@link #section} over
 * {@link #SECTIONS}, and the raw {@link #sitemapIndex}/{@link #articlesFromSitemap}
 * firehose. Those have no caller today; they exist so the general stream can be
 * mobilized later without opening this source a second time. They share the
 * {@link #CACHE_TTL} pool and cost the instrument path nothing.
 */
@Singleton
public class BoersenmedienNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(BoersenmedienNewsClient.class);

    static final String HOST_BO = "https://www.boerse-online.de";
    static final String HOST_DA = "https://www.deraktionaer.de";

    public static final String PUBLISHER_BO = "Börse Online";
    public static final String PUBLISHER_DA = "Der Aktionär";
    /** Publisher marker for Plus pieces - a body fetch on these yields a stub. */
    public static final String PUBLISHER_DA_PLUS = "Der Aktionär Plus";

    /** Instrument legs: {@code %s} = ISIN, {@code %d} = 1-based page. */
    static final String URL_BO_EDITORIAL = HOST_BO + "/aktie/x-%s/nachrichten/%d";
    static final String URL_BO_WIRE = HOST_BO + "/aktie/x-%s/dpa-afx/%d";
    static final String URL_DA_EDITORIAL = HOST_DA + "/aktien/artikel/x-%s/%d";
    static final String URL_DA_WIRE = HOST_DA + "/aktien/nachrichten/x-%s/%d";

    /**
     * Börse Online's full-text search - a plain GET, robots-ALLOWED (only
     * {@code /account/} is disallowed there), answering 10 instrument hits and
     * 10 article teasers in the ordinary list markup. This is the name door.
     *
     * <p>GOTCHA: it does not paginate. {@code &p=}, {@code &page=} and
     * {@code /suchen/<n>} all re-serve the same 10 hits (the "mehr" button is
     * {@code display:none} with {@code href="#"} - the real paging is a JS call
     * this client does not run). Ten hits is the whole name leg; depth comes
     * from the ISIN lists and the sitemaps.
     */
    static final String URL_BO_SEARCH = HOST_BO + "/suchen?q=%s";

    /**
     * Der Aktionär's story typeahead. It answers a clean HTML result fragment
     * but ONLY to a {@code POST} carrying a JSON body {@code {"q":"…"}} (the
     * GET form returns the bare site shell with zero results, verified
     * 2026-08-02) - and the house {@link WebFetcher} seam is GET-only, so this
     * endpoint is documented rather than wired. Should a POST ever join the
     * seam, this is a ready third name leg; note that Der Aktionär's
     * {@code robots.txt} additionally disallows {@code /suchen*}, so the
     * {@code /api/remote/} path is the only sanctioned door on that host.
     */
    public static final String URL_DA_SEARCH_STORIES = HOST_DA + "/api/remote/searchStories";

    static final String URL_BO_SITEMAP_INDEX = HOST_BO + "/sitemap.xml";
    static final String URL_DA_SITEMAP_INDEX = HOST_DA + "/sitemap.xml";

    /**
     * The GENERAL (instrument-free) news lists of both houses, section key →
     * URL prefix; a 1-based page number is appended as {@code /<n>}. Verified
     * live 2026-08-02. Börse Online serves 16 items per editorial page and 30
     * per wire page, Der Aktionär 20.
     *
     * <p>Not every rubric that appears in article URLs is also a list page:
     * {@code krypto}, {@code technische-analyse}, {@code medien-it-technologie}
     * and {@code der-aktionaer-magazin} answer 404 as lists although articles
     * live under those segments - they are reachable through the sitemap
     * firehose only.
     */
    public static final Map<String, String> SECTIONS = Map.ofEntries(
            Map.entry("bo-alle", HOST_BO + "/nachrichten"),
            Map.entry("bo-aktien", HOST_BO + "/nachrichten/aktien"),
            Map.entry("bo-rohstoffe", HOST_BO + "/nachrichten/rohstoffe"),
            Map.entry("bo-fonds", HOST_BO + "/nachrichten/fonds"),
            Map.entry("bo-zertifikate", HOST_BO + "/nachrichten/zertifikate"),
            Map.entry("bo-geldundvorsorge", HOST_BO + "/nachrichten/geldundvorsorge"),
            Map.entry("bo-dpa-afx", HOST_BO + "/nachrichten/dpa-afx"),
            Map.entry("da-aktien", HOST_DA + "/artikel/aktien"),
            Map.entry("da-indizes", HOST_DA + "/artikel/indizes"),
            Map.entry("da-maerkte", HOST_DA + "/artikel/maerkte-forex-zinsen"),
            Map.entry("da-pharma-biotech", HOST_DA + "/artikel/pharma-biotech"),
            Map.entry("da-gold-rohstoffe", HOST_DA + "/artikel/gold-rohstoffe"),
            Map.entry("da-fintech", HOST_DA + "/artikel/fintech-versicherung-banken"),
            Map.entry("da-mobilitaet-energie", HOST_DA + "/artikel/mobilitaet-oel-energie"),
            Map.entry("da-commerce", HOST_DA + "/artikel/commerce-brands-unicorns"),
            Map.entry("da-plus", HOST_DA + "/artikel/deraktionaerplus"));

    /** The sections {@link #latest} pools: both houses' front lists plus the wire. */
    static final List<String> LATEST_SECTIONS =
            List.of("bo-alle", "bo-dpa-afx", "da-aktien");

    static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /** Pages walked per leg for a plain instrument query. */
    private static final int MAX_INSTRUMENT_PAGES = 3;
    /** Pages walked per leg when filling a dated window - the clamp makes a cap mandatory. */
    private static final int MAX_WINDOW_PAGES = 12;
    /** Article sitemaps parsed per house for one window - each is ~750 KB. */
    private static final int MAX_SITEMAP_SCANS = 3;
    /** Fetches a binary search over the sitemap index may spend per house. */
    private static final int MAX_SITEMAP_PROBES = 8;

    /** German venue - the list timestamps carry no zone. */
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** Generic words that must never carry a name match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "kgaa", "holding", "holdings", "group", "gruppe", "international",
            "aktie", "aktien", "stock", "stocks", "shares");

    // ---- markup seams, all verified against live captures 2026-08-02 ----

    /** Börse Online editorial teaser (instrument list AND general section list). */
    private static final Pattern BO_EDITORIAL_ITEM = Pattern.compile(
            "<article class=\"article-list-item article-list-item-medium.*?</article>", Pattern.DOTALL);
    /** dpa-AFX wire teaser, the flat variant without an image. */
    private static final Pattern BO_WIRE_ITEM = Pattern.compile(
            "<article class=\"article-list-item article-list-item-simple\">.*?</article>", Pattern.DOTALL);
    /** Der Aktionär teaser (instrument list, section list and search fragment). */
    private static final Pattern DA_ITEM = Pattern.compile(
            "<(article|div) class=\"article-list [^\"]*article-list-item\">.*?</\\1>", Pattern.DOTALL);
    /** Der Aktionär wire list - a bare {@code <li><a href="/nachricht/…">} with no date. */
    private static final Pattern DA_WIRE_ITEM = Pattern.compile(
            "<li>\\s*<a href=\"(/nachricht/[^\"]+)\"[^>]*>(.*?)</a>\\s*</li>", Pattern.DOTALL);

    private static final Pattern HREF = Pattern.compile("href=\"(/[^\"#]+\\.html)\"");
    private static final Pattern DATA_QUOTE = Pattern.compile("data-quote=\"([A-Z]{2}[A-Z0-9]{9}\\d)\"");
    private static final Pattern TIME_ATTR = Pattern.compile("<time datetime=\"([^\"]+)\"");
    private static final Pattern DA_SMALL_DATE = Pattern.compile(
            "<small>\\s*(\\d{2}\\.\\d{2}\\.\\d{4}),?\\s*(\\d{2}:\\d{2})");
    private static final Pattern H2_TEXT = Pattern.compile("<h2>\\s*<a[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern STRONG_TITLE = Pattern.compile("<strong>(.*?)</strong>", Pattern.DOTALL);
    private static final Pattern ARIA_LABEL = Pattern.compile("aria-label=\"(.*?)\"", Pattern.DOTALL);
    private static final Pattern TEASER = Pattern.compile("<p>(.*?)</p>", Pattern.DOTALL);
    private static final Pattern IMG_SRC = Pattern.compile("<img[^>]*\\ssrc=\"([^\"]+)\"");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    /** Trailing CMS id of any article URL - the cross-house identity. */
    private static final Pattern ARTICLE_ID = Pattern.compile("-(\\d{4,})\\.html$");

    private static final Pattern SITEMAP_LOC = Pattern.compile("<loc>\\s*(.*?)\\s*</loc>", Pattern.DOTALL);
    private static final Pattern SITEMAP_URL_BLOCK = Pattern.compile("<url>.*?</url>", Pattern.DOTALL);
    private static final Pattern NEWS_DATE = Pattern.compile(
            "<news:publication_date>\\s*(.*?)\\s*</news:publication_date>", Pattern.DOTALL);
    private static final Pattern NEWS_TITLE = Pattern.compile(
            "<news:title>\\s*(.*?)\\s*</news:title>", Pattern.DOTALL);
    private static final Pattern IMAGE_LOC = Pattern.compile(
            "<image:loc>\\s*(.*?)\\s*</image:loc>", Pattern.DOTALL);
    /** JSON-LD paywall flag on a fetched ARTICLE page. */
    private static final Pattern ACCESSIBLE_FOR_FREE = Pattern.compile(
            "\"isAccessibleForFree\"\\s*:\\s*(true|false)");

    private static final DateTimeFormatter LIST_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final DateTimeFormatter GERMAN_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY);

    /** Newest first; items without a timestamp sort to the end. */
    private static final Comparator<RawNewsItem> BY_RECENCY =
            Comparator.comparing(RawNewsItem::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** section key → cached page-1 items. */
    private final Map<String, Cached> sectionCache = new ConcurrentHashMap<>();
    /** sitemap URL → its FIRST publication instant; immutable once written, so it never expires. */
    private final Map<String, Instant> sitemapFloor = new ConcurrentHashMap<>();
    /** house sitemap index URL → the ordered article sitemap URLs. */
    private final Map<String, Cached> sitemapIndexCache = new ConcurrentHashMap<>();

    private record Cached(Instant fetchedAt, List<RawNewsItem> items, List<String> urls) {}

    /** Test/default: plain direct transport. */
    public BoersenmedienNewsClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the shared {@code @DirectFirst} seam. Neither house carries a
     * wall (both answer 200 without a User-Agent), so plain HTTP would do - the
     * annotation records exactly that while still riding the house-wide
     * browser-first chain.
     */
    @Inject
    public BoersenmedienNewsClient(@DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "boersenmedien";
    }

    /**
     * Dossier leg. Boerse Online and Der Aktionaer republish the same dpa-AFX
     * piece under two more roofs; on the wire that is one story arriving four
     * times, in a dossier it is the 2003 archive (2026-08-03).
     */
    @Override
    public boolean dossierOnly() {
        return true;
    }

    // ------------------------------------------------------------------
    // instrument fans
    // ------------------------------------------------------------------

    /**
     * No-op: both houses address instruments by ISIN and WKN only. The
     * {@code ticker} field exists in the quote JSON but there is no
     * ticker-addressed list URL, so a symbol cannot open a door here. Resolve
     * the symbol to an ISIN first (see {@link BoersenmedienResolver}) and use
     * {@link #newsForIsin}.
     */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /**
     * The instrument door: all four per-ISIN lists, merged and deduped by CMS
     * article id (which collapses the dpa-AFX pieces that appear under both
     * roofs), freshest first.
     */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        String key = normalizeIsin(isin);
        if (key == null || limit <= 0) return List.of();

        List<RawNewsItem> all = new ArrayList<>();
        all.addAll(walk(URL_BO_EDITORIAL, key, limit, MAX_INSTRUMENT_PAGES, Leg.BO_EDITORIAL, null));
        all.addAll(walk(URL_BO_WIRE, key, limit, MAX_INSTRUMENT_PAGES, Leg.BO_WIRE, null));
        all.addAll(walk(URL_DA_EDITORIAL, key, limit, MAX_INSTRUMENT_PAGES, Leg.DA_EDITORIAL, null));
        all.addAll(walk(URL_DA_WIRE, key, limit, MAX_INSTRUMENT_PAGES, Leg.DA_WIRE, null));

        return dedupe(all).stream()
                .map(it -> withIsin(it, key))
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /**
     * By name over Börse Online's {@code /suchen?q=} - a plain GET that answers
     * 10 article teasers in the ordinary editorial markup, title-precision cut
     * against the queried name. Deliberately shallow (the endpoint does not
     * paginate, see {@link #URL_BO_SEARCH}): it exists so a name that never
     * resolved to an ISIN still finds this house, while depth stays the job of
     * {@link #newsForIsin} and {@link #newsForNameWindow}.
     */
    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        return search(companyName).stream()
                .filter(it -> titleMatches(it.title(), words))
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /**
     * One page of Börse Online's site search as news items - the instrument
     * hits in the same result page are ignored here (they carry no headline);
     * {@link BoersenmedienResolver} is the door for those. Public because the
     * general stream may want a keyword probe without going through the
     * NewsSource fan.
     */
    public List<RawNewsItem> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        String url = String.format(URL_BO_SEARCH,
                java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8));
        return parseBoerseOnlineList(get(url));
    }

    /**
     * ARCHIVE window, two legs that reach different depths:
     *
     * <ol>
     *   <li>When an ISIN is known, the four instrument lists are paginated
     *       backwards until they fall below {@code fromIsoDate}. Precise
     *       (ISIN-scoped, no name guessing) but the lists bottom out around
     *       2020.</li>
     *   <li>The article SITEMAPS of both houses, binary-searched by
     *       {@code news:publication_date} - they carry a title and a date per
     *       URL and reach back to 2003, so a multi-year dossier gets the years
     *       the lists never had.</li>
     * </ol>
     *
     * Both legs are capped hard ({@link #MAX_WINDOW_PAGES} pages,
     * {@link #MAX_SITEMAP_SCANS} sitemaps per house) - a name with a five-figure
     * hit count must not turn one dossier into a crawl.
     */
    @Override
    public List<RawNewsItem> newsForNameWindow(String companyName, String isin,
                                               String fromIsoDate, String toIsoDateExclusive,
                                               int limit) {
        if (limit <= 0) return List.of();
        Instant from = startOfDay(fromIsoDate);
        Instant toExcl = startOfDay(toIsoDateExclusive);
        if (from == null || toExcl == null || !from.isBefore(toExcl)) return List.of();

        Set<String> words = significantWords(companyName);
        List<RawNewsItem> out = new ArrayList<>();

        String key = normalizeIsin(isin);
        if (key != null) {
            for (Map.Entry<String, Leg> leg : Map.of(
                    URL_BO_EDITORIAL, Leg.BO_EDITORIAL, URL_BO_WIRE, Leg.BO_WIRE,
                    URL_DA_EDITORIAL, Leg.DA_EDITORIAL).entrySet()) {
                walk(leg.getKey(), key, limit, MAX_WINDOW_PAGES, leg.getValue(), from).stream()
                        .map(it -> withIsin(it, key))
                        .forEach(out::add);
            }
        }
        if (!words.isEmpty()) {
            out.addAll(archiveFromSitemaps(URL_DA_SITEMAP_INDEX, PUBLISHER_DA, words, from, toExcl));
            out.addAll(archiveFromSitemaps(URL_BO_SITEMAP_INDEX, PUBLISHER_BO, words, from, toExcl));
        }

        return dedupe(out).stream()
                .filter(it -> inWindow(it.publishedAt(), from, toExcl))
                .filter(it -> words.isEmpty() || titleMatches(it.title(), words))
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    // ------------------------------------------------------------------
    // general stream - no caller yet, kept public so it can be mobilized
    // ------------------------------------------------------------------

    /**
     * The house firehose: both front lists plus the dpa-AFX wire, merged,
     * deduped and freshest first. Served from the same {@link #CACHE_TTL} pool
     * the {@link #section} calls use, so it never slows an instrument query
     * down (the instrument legs don't touch this pool at all).
     */
    public List<RawNewsItem> latest(int limit) {
        if (limit <= 0) return List.of();
        List<RawNewsItem> all = new ArrayList<>();
        for (String key : LATEST_SECTIONS) all.addAll(section(key, limit));
        return dedupe(all).stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * One general section's page 1, cached per {@link #CACHE_TTL}. Keys are the
     * ones in {@link #SECTIONS}; an unknown key answers empty rather than
     * guessing a URL.
     */
    public List<RawNewsItem> section(String sectionKey, int limit) {
        if (limit <= 0) return List.of();
        String base = SECTIONS.get(sectionKey);
        if (base == null) {
            LOG.debug("unknown Börsenmedien section {}", sectionKey);
            return List.of();
        }
        Cached c = sectionCache.get(sectionKey);
        if (c == null || c.fetchedAt().plus(CACHE_TTL).isBefore(Instant.now())) {
            Leg leg = legFor(base);
            List<RawNewsItem> fetched = parseList(get(base + "/1"), leg);
            if (fetched.isEmpty() && c != null) {
                return c.items().stream().limit(limit).toList(); // outage: keep the stale page
            }
            c = new Cached(Instant.now(), fetched, List.of());
            sectionCache.put(sectionKey, c);
        }
        return c.items().stream().limit(limit).toList();
    }

    /**
     * Page {@code page} (1-based) of a general section, uncached. Remember the
     * clamp: past the last page the backend re-serves the last page instead of
     * answering empty, so a caller walking pages must stop on repeated ids.
     */
    public List<RawNewsItem> sectionPage(String sectionKey, int page, int limit) {
        String base = SECTIONS.get(sectionKey);
        if (base == null || page < 1 || limit <= 0) return List.of();
        return parseList(get(base + "/" + page), legFor(base)).stream().limit(limit).toList();
    }

    /**
     * The ordered article sitemap URLs of one house - pass
     * {@link #URL_BO_SITEMAP_INDEX} or {@link #URL_DA_SITEMAP_INDEX}. Der
     * Aktionär publishes 131 of them (≈ 131.000 articles back to 2003), Börse
     * Online 73 (≈ 73.000); they are sorted OLDEST first. Currently unused by
     * any caller - exposed so the full back catalogue is reachable without
     * reopening this source.
     */
    public List<String> sitemapIndex(String indexUrl) {
        Cached c = sitemapIndexCache.get(indexUrl);
        if (c != null && c.fetchedAt().plus(Duration.ofHours(6)).isAfter(Instant.now())) {
            return c.urls();
        }
        List<String> urls = parseSitemapIndex(get(indexUrl));
        if (urls.isEmpty()) return c != null ? c.urls() : List.of();
        sitemapIndexCache.put(indexUrl, new Cached(Instant.now(), List.of(), urls));
        return urls;
    }

    /**
     * Every article of one sitemap file as news items - title, permalink,
     * publication instant and teaser image, no HTML parsing needed. ~1.000
     * items and ~750 KB per call. Currently unused by any caller; it is the
     * cheapest bulk door either house has.
     */
    public List<RawNewsItem> articlesFromSitemap(String sitemapUrl) {
        String publisher = sitemapUrl.startsWith(HOST_BO) ? PUBLISHER_BO : PUBLISHER_DA;
        return parseSitemap(get(sitemapUrl), publisher);
    }

    // ------------------------------------------------------------------
    // fetching
    // ------------------------------------------------------------------

    /** Which parser a URL's markup needs. */
    enum Leg {
        BO_EDITORIAL(PUBLISHER_BO, HOST_BO),
        BO_WIRE(PUBLISHER_BO, HOST_BO),
        DA_EDITORIAL(PUBLISHER_DA, HOST_DA),
        DA_WIRE(PUBLISHER_DA, HOST_DA);

        final String publisher;
        final String host;

        Leg(String publisher, String host) {
            this.publisher = publisher;
            this.host = host;
        }
    }

    private static Leg legFor(String base) {
        if (base.startsWith(HOST_DA)) return Leg.DA_EDITORIAL;
        return base.endsWith("/dpa-afx") ? Leg.BO_WIRE : Leg.BO_EDITORIAL;
    }

    /**
     * Walks one paginated leg. Stops on the first of: enough items, the page
     * cap, an empty page, a REPEATED page (the clamp - see the class note), or
     * - when {@code floor} is given - a page whose newest item already sits
     * below the window.
     */
    private List<RawNewsItem> walk(String urlTemplate, String isin, int limit,
                                   int maxPages, Leg leg, Instant floor) {
        List<RawNewsItem> out = new ArrayList<>();
        String previousFirstId = null;
        for (int page = 1; page <= maxPages && out.size() < limit; page++) {
            List<RawNewsItem> batch = parseList(get(String.format(urlTemplate, isin, page)), leg);
            if (batch.isEmpty()) break;
            String firstId = articleId(batch.get(0).link());
            if (firstId != null && firstId.equals(previousFirstId)) break; // clamped past the end
            previousFirstId = firstId;
            out.addAll(batch);
            if (floor != null && pagedBelow(batch, floor)) break;
        }
        return out;
    }

    /** True when every dated item on the page is older than the window's floor. */
    private static boolean pagedBelow(List<RawNewsItem> batch, Instant floor) {
        boolean sawDate = false;
        for (RawNewsItem it : batch) {
            if (it.publishedAt() == null) continue;
            sawDate = true;
            if (!it.publishedAt().isBefore(floor)) return false;
        }
        return sawDate;
    }

    private String get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent,
                            "Accept", "text/html,application/xhtml+xml,application/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("Börsenmedien {} answered status {}", url, resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("Börsenmedien {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    // ------------------------------------------------------------------
    // parsers - package-private for tests
    // ------------------------------------------------------------------

    static List<RawNewsItem> parseList(String html, Leg leg) {
        if (html == null || html.isBlank()) return List.of();
        return switch (leg) {
            case BO_EDITORIAL -> parseBoerseOnlineList(html);
            case BO_WIRE -> parseBoerseOnlineWireList(html);
            case DA_EDITORIAL -> parseAktionaerList(html, PUBLISHER_DA);
            case DA_WIRE -> parseAktionaerWireList(html);
        };
    }

    /**
     * Börse Online editorial teasers - {@code <article class="…-medium…">} with
     * a {@code <time datetime>} attribute (the visible label may read "Heute",
     * the attribute never does), an {@code <h2>} headline and a
     * {@code stock-info-badge} carrying the article's primary ISIN.
     */
    static List<RawNewsItem> parseBoerseOnlineList(String html) {
        if (html == null) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        Matcher m = BO_EDITORIAL_ITEM.matcher(html);
        while (m.find()) {
            String block = m.group();
            String link = absolute(firstGroup(HREF, block), HOST_BO);
            String title = clean(firstGroup(H2_TEXT, block));
            if (link == null || title == null) continue;
            out.add(new RawNewsItem(
                    identity(link), title, PUBLISHER_BO, link,
                    parseListDate(firstGroup(TIME_ATTR, block)),
                    List.of(), firstGroup(DATA_QUOTE, block), null, false,
                    firstGroup(IMG_SRC, block)));
        }
        return List.copyOf(out);
    }

    /**
     * dpa-AFX wire teasers - the flat {@code …-simple} variant. No image, no
     * teaser, and the ISIN sits on the {@code article-info} div rather than on
     * a badge. The headline is the anchor's own text.
     */
    static List<RawNewsItem> parseBoerseOnlineWireList(String html) {
        if (html == null) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        Matcher m = BO_WIRE_ITEM.matcher(html);
        while (m.find()) {
            String block = m.group();
            String link = absolute(firstGroup(HREF, block), HOST_BO);
            if (link == null) continue;
            String title = clean(afterLastAnchorOpen(block));
            if (title == null) continue;
            out.add(new RawNewsItem(
                    identity(link), title, PUBLISHER_BO, link,
                    parseListDate(firstGroup(TIME_ATTR, block)),
                    List.of(), firstGroup(DATA_QUOTE, block), null, false, null));
        }
        return List.copyOf(out);
    }

    /**
     * Der Aktionär teasers - same shape in the instrument list, the section
     * lists and the search fragment. The date is German prose
     * ({@code 16.07.2026, 09:00 ‧ Autor}), the headline is an {@code <h2>} in
     * the lists and a {@code <strong>} in the search fragment, and the Plus
     * wall shows up as a path segment or a {@code plus-article} class.
     */
    static List<RawNewsItem> parseAktionaerList(String html, String publisher) {
        if (html == null) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        Matcher m = DA_ITEM.matcher(html);
        while (m.find()) {
            String block = m.group();
            String link = absolute(firstGroup(HREF, block), HOST_DA);
            if (link == null) continue;
            String title = clean(firstGroup(H2_TEXT, block));
            if (title == null) title = clean(firstGroup(STRONG_TITLE, block));
            if (title == null) title = clean(firstGroup(ARIA_LABEL, block));
            if (title == null) continue;
            boolean gated = isPlus(link) || block.contains("plus-article");
            out.add(new RawNewsItem(
                    identity(link), title,
                    gated ? PUBLISHER_DA_PLUS : publisher, link,
                    parseGermanDate(block),
                    List.of(), firstGroup(DATA_QUOTE, block), clean(firstGroup(TEASER, block)),
                    false, firstGroup(IMG_SRC, block)));
        }
        return List.copyOf(out);
    }

    /**
     * Der Aktionär's wire list ({@code #symbol-articles-poolnews}) - a bare
     * {@code <ul>} of links. GOTCHA: it carries NO date at all, so items land
     * with a null {@code publishedAt}; the very same pieces come dated off the
     * Börse Online dpa-AFX leg and the merge keeps the dated twin, because
     * dedupe is by article id and the dated leg is added first.
     */
    static List<RawNewsItem> parseAktionaerWireList(String html) {
        if (html == null) return List.of();
        String isin = firstGroup(DATA_QUOTE, html);
        List<RawNewsItem> out = new ArrayList<>();
        Matcher m = DA_WIRE_ITEM.matcher(html);
        while (m.find()) {
            String link = HOST_DA + m.group(1);
            String title = clean(m.group(2));
            if (title == null) continue;
            out.add(new RawNewsItem(identity(link), title, PUBLISHER_DA, link,
                    null, List.of(), isin, null, false, null));
        }
        return List.copyOf(out);
    }

    /** The article sitemap URLs of a house's sitemap index, oldest first. */
    static List<String> parseSitemapIndex(String xml) {
        if (xml == null) return List.of();
        List<String> out = new ArrayList<>();
        Matcher m = SITEMAP_LOC.matcher(xml);
        while (m.find()) {
            String loc = m.group(1);
            if (loc.contains("sitemaparticles")) out.add(loc);
        }
        return List.copyOf(out);
    }

    /**
     * One article sitemap file. {@code news:publication_date} is the real
     * publication instant (the {@code lastmod} is a re-render date and lies),
     * {@code news:title} the headline, {@code image:loc} the teaser image.
     */
    static List<RawNewsItem> parseSitemap(String xml, String publisher) {
        if (xml == null) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        Matcher m = SITEMAP_URL_BLOCK.matcher(xml);
        while (m.find()) {
            String block = m.group();
            String link = firstGroup(SITEMAP_LOC, block);
            String title = clean(firstGroup(NEWS_TITLE, block));
            if (link == null || title == null) continue;
            boolean gated = isPlus(link);
            out.add(new RawNewsItem(
                    identity(link), title,
                    gated ? PUBLISHER_DA_PLUS : publisher, link,
                    parseIsoDate(firstGroup(NEWS_DATE, block)),
                    List.of(), null, null, false, firstGroup(IMAGE_LOC, block)));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------
    // archive over the sitemaps
    // ------------------------------------------------------------------

    private List<RawNewsItem> archiveFromSitemaps(String indexUrl, String publisher,
                                                  Set<String> words, Instant from, Instant toExcl) {
        List<String> maps = sitemapIndex(indexUrl);
        if (maps.isEmpty()) return List.of();

        int start = locateSitemap(maps, from);
        List<RawNewsItem> out = new ArrayList<>();
        for (int i = start, scanned = 0; i < maps.size() && scanned < MAX_SITEMAP_SCANS; i++, scanned++) {
            Instant floor = floorOf(maps.get(i), publisher);
            if (floor != null && !floor.isBefore(toExcl)) break; // already past the window
            for (RawNewsItem it : articlesFromSitemap(maps.get(i))) {
                if (!inWindow(it.publishedAt(), from, toExcl)) continue;
                if (titleMatches(it.title(), words)) out.add(it);
            }
        }
        return out;
    }

    /**
     * Binary search for the first sitemap that can still contain {@code from}.
     * The files are chronological, so probing a file's FIRST publication date
     * is enough - {@link #MAX_SITEMAP_PROBES} fetches cover 131 files with room
     * to spare, and each floor is memoized forever (a sitemap file's oldest
     * entry never changes).
     */
    private int locateSitemap(List<String> maps, Instant from) {
        int lo = 0, hi = maps.size() - 1, answer = 0, probes = 0;
        while (lo <= hi && probes < MAX_SITEMAP_PROBES) {
            int mid = (lo + hi) >>> 1;
            probes++;
            Instant floor = floorOf(maps.get(mid), null);
            if (floor == null) break;
            if (floor.isBefore(from)) {
                answer = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return answer;
    }

    /** The oldest publication instant in one sitemap file; memoized. */
    private Instant floorOf(String sitemapUrl, String publisher) {
        Instant known = sitemapFloor.get(sitemapUrl);
        if (known != null) return known;
        List<RawNewsItem> items = parseSitemap(get(sitemapUrl),
                publisher != null ? publisher : PUBLISHER_DA);
        Instant floor = items.stream()
                .map(RawNewsItem::publishedAt)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (floor != null) sitemapFloor.put(sitemapUrl, floor);
        return floor;
    }

    // ------------------------------------------------------------------
    // helpers - package-private where tests need them
    // ------------------------------------------------------------------

    /**
     * True when the URL points at a Der Aktionär Plus piece. Both spellings of
     * the same rubric count: the list markup writes
     * {@code /artikel/deraktionaerplus/}, the sitemap
     * {@code /artikel/der-aktionaer-plus/}.
     */
    static boolean isPlus(String link) {
        if (link == null) return false;
        return link.contains("/artikel/deraktionaerplus/")
                || link.contains("/artikel/der-aktionaer-plus/");
    }

    /**
     * The second Plus tell, for a reader that already fetched the article page:
     * the JSON-LD {@code isAccessibleForFree} flag. Börse Online articles carry
     * no such flag (they are free), so a MISSING flag is not a wall.
     */
    public static boolean isGatedArticleHtml(String articleHtml) {
        if (articleHtml == null) return false;
        Matcher m = ACCESSIBLE_FOR_FREE.matcher(articleHtml);
        return m.find() && "false".equals(m.group(1));
    }

    /**
     * The trailing CMS article id - the cross-house identity. Börsenmedien runs
     * ONE id space: the same wire piece is {@code …-542040.html} under both
     * roofs, and the editorial ids of the two houses interleave. Falls back to
     * the URL when a link carries no id.
     */
    static String articleId(String link) {
        if (link == null) return null;
        Matcher m = ARTICLE_ID.matcher(link);
        return m.find() ? m.group(1) : null;
    }

    private static String identity(String link) {
        String id = articleId(link);
        return id != null ? "bm-" + id : link;
    }

    /**
     * Dedupes by CMS article id, first occurrence wins - which is why the DATED
     * legs are always added before the undated Der Aktionär wire list.
     */
    static List<RawNewsItem> dedupe(List<RawNewsItem> items) {
        Map<String, RawNewsItem> byId = new LinkedHashMap<>();
        for (RawNewsItem it : items) {
            if (it == null || it.link() == null) continue;
            byId.putIfAbsent(it.uuid(), it);
        }
        return List.copyOf(byId.values());
    }

    /** Attributes an item to the ISIN that ADDRESSED the list it came from. */
    private static RawNewsItem withIsin(RawNewsItem it, String isin) {
        return new RawNewsItem(it.uuid(), it.title(), it.publisher(), it.link(),
                it.publishedAt(), it.relatedTickers(), isin, it.summary(),
                it.sponsored(), it.imageUrl());
    }

    /** {@code 2026-07-31 15:21} (no zone, German venue) → {@link Instant}. */
    static Instant parseListDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s.trim(), LIST_DATE).atZone(BERLIN).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code 16.07.2026, 09:00 ‧ Autor} inside a block → {@link Instant}. */
    static Instant parseGermanDate(String block) {
        if (block == null) return null;
        Matcher m = DA_SMALL_DATE.matcher(block);
        if (!m.find()) return null;
        try {
            return LocalDateTime.parse(m.group(1) + " " + m.group(2), GERMAN_DATE)
                    .atZone(BERLIN).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code 2026-07-13T05:06:59+00:00} → {@link Instant}. */
    static Instant parseIsoDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s.trim()).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

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

    /** Significant (length ≥ 3, non-generic) words of the queried name. */
    static Set<String> significantWords(String name) {
        Set<String> out = new LinkedHashSet<>();
        if (name == null) return out;
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    /** True when the text carries at least one significant word as a WHOLE word. */
    static boolean titleMatches(String text, Set<String> nameWords) {
        if (text == null || nameWords == null || nameWords.isEmpty()) return false;
        String t = normalize(text);
        for (String w : nameWords) {
            if (Pattern.compile("\\b" + Pattern.quote(w) + "\\b").matcher(t).find()) return true;
        }
        return false;
    }

    static String normalizeIsin(String isin) {
        if (isin == null) return null;
        String v = isin.trim().toUpperCase(Locale.ROOT);
        return v.matches("[A-Z]{2}[A-Z0-9]{9}\\d") ? v : null;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    private static String firstGroup(Pattern p, String s) {
        if (s == null) return null;
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** The wire teaser's headline: the text after the LAST opening anchor tag. */
    private static String afterLastAnchorOpen(String block) {
        int i = block.lastIndexOf("<a ");
        if (i < 0) return null;
        int j = block.indexOf('>', i);
        int k = block.indexOf("</a>", j);
        return j < 0 || k < 0 ? null : block.substring(j + 1, k);
    }

    private static String absolute(String path, String host) {
        if (path == null) return null;
        return path.startsWith("http") ? path : host + path;
    }

    /**
     * Strips tags and entities and normalizes whitespace. The houses print soft
     * hyphens as {@code &#8209;} (non-breaking hyphen) inside company names -
     * folded to a plain hyphen so a title match is not defeated by typography.
     */
    static String clean(String raw) {
        if (raw == null) return null;
        // Basic entities FIRST: the houses double-escape their own soft hyphen
        // as "&amp;#8209;", so the numeric pass only sees it after "&amp;" fell.
        String s = TAG.matcher(raw).replaceAll(" ");
        s = HtmlText.unescapeBasic(s);
        s = decodeNumericEntities(s);
        s = s.replace('‑', '-').replace(' ', ' ').replace(' ', ' ')
                .replace(' ', ' ').replace(' ', ' ').replace('‧', ' ');
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    private static String decodeNumericEntities(String s) {
        Matcher m = Pattern.compile("&#(x?)([0-9A-Fa-f]+);").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                cp = ' ';
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
