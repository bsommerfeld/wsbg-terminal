package de.bsommerfeld.wsbg.terminal.boersede;

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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * boerse.de as the ISIN-addressed German press leg - probed live 2026-08-02 and
 * the legally quietest source of the 2026-08 wave: {@code robots.txt} says
 * {@code User-agent: * / Allow: /}, there is no bot wall (20 requests, 20 × 200),
 * and the site publishes no terms page with a clause on automated retrieval
 * ({@code /agb} and {@code /nutzungsbedingungen} both 404).
 *
 * <p>Two doors, deliberately different in job:
 *
 * <ul>
 *   <li><b>Instrument leg</b> - {@code /nachrichten/x/<ISIN>}: dpa-AFX,
 *       EQS-PVR/-DD and ROUNDUP items for exactly that instrument, ~5 pages
 *       deep. The ISIN is the whole address; no resolver, no name guessing.</li>
 *   <li><b>General leg</b> - the section lists ({@link #SECTIONS}) plus the
 *       news sitemap firehose ({@link #sitemapLatest}). Both are pooled behind
 *       one TTL cache and serve {@link #latest}, {@link #section} and the
 *       name-addressed fan alike, so a name query costs zero extra fetches.</li>
 * </ul>
 *
 * <p><b>Gotcha 1 - the slug is ignored, but only without pagination.</b>
 * {@code /nachrichten/x/<ISIN>} answers a 301 to the canonical
 * {@code /nachrichten/<Slug>/<ISIN>} and the transport follows it, so page 1
 * needs no slug knowledge. Appending {@code _seite,N} to the {@code /x/} form
 * however 301s to the canonical page 1 and SILENTLY DROPS the pagination - the
 * naive paginator would refetch page 1 five times. This client therefore never
 * builds a page URL itself: it follows the page's own
 * {@code <link rel="next">}, which is emitted on every list surface (instrument
 * news, recommendations, sections).
 *
 * <p><b>Gotcha 2 - no {@code data-quote} attribute.</b> The 2026-08-02 research
 * note expected article rows to carry {@code data-quote="<ISIN>"}; the live
 * markup does not (verified on SAP: zero occurrences). It does not matter - the
 * whole PAGE is ISIN-addressed, so the queried ISIN is stamped onto every item
 * it yields, which is a stronger claim than a per-row attribute anyway.
 *
 * <p><b>Gotcha 3 - unknown ISIN answers 410,</b> not 404, and the chain treats
 * 410 as definitive. An unlisted instrument therefore yields an empty list at
 * the cost of one request and never falls through to the browser joker.
 *
 * <p>{@link #newsFor} stays a no-op: boerse.de has no ticker addressing and no
 * name/WKN search at all (the search box is JS-driven with no suggest endpoint),
 * so the ISIN must come from outside. Name queries are served by the pooled
 * general stream under the house TITLE-precision cut.
 */
@Singleton
public class BoerseDeNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(BoerseDeNewsClient.class);

    static final String PUBLISHER = "boerse.de";
    static final String BASE = "https://www.boerse.de";

    /** Instrument news, page 1 - the {@code /x/} form 301s to the canonical slug. */
    static final String ISIN_NEWS_URL = BASE + "/nachrichten/x/%s";
    /** The news sitemap INDEX: four child sitemaps of ~1000 article URLs each. */
    static final String SITEMAP_INDEX_URL = BASE + "/sitemap/sitemap_news.xml";

    /**
     * The general news surfaces, verified live 2026-08-02 (key → path). Every
     * one of them renders the same {@code row row-bordered} list and emits
     * {@code <link rel="next">}, so they all page with the same code.
     *
     * <p>Reachable today through {@link #section(String, int)}; nothing in the
     * app calls them yet. They exist so the general stream can be mobilised
     * later without reopening this source:
     *
     * <ul>
     *   <li>{@code nachrichten} - the undifferentiated main wire (20/page)</li>
     *   <li>{@code adhoc} - regulatory ad-hoc releases only (10/page)</li>
     *   <li>{@code top-news} - the editorial front page ({@code /news/}, ~89 rows,
     *       unpaginated)</li>
     *   <li>{@code unternehmensnachrichten} - company news (40/page)</li>
     *   <li>{@code marktberichte} - market reports / session wrap-ups (40/page)</li>
     *   <li>{@code finanznachrichten} - the broad finance wire (~60 rows)</li>
     *   <li>{@code aktienanalysen} - equity analyses (40 rows)</li>
     *   <li>{@code analysen} - the house BOTSI analyses; note this path redirects
     *       to {@code /botsi-analysen/} and pages from there</li>
     * </ul>
     */
    public static final Map<String, String> SECTIONS = Map.of(
            "nachrichten", "/nachrichten/",
            "adhoc", "/adhoc/",
            "top-news", "/news/",
            "unternehmensnachrichten", "/unternehmensnachrichten/",
            "marktberichte", "/marktberichte/",
            "finanznachrichten", "/finanznachrichten/",
            "aktienanalysen", "/aktienanalysen/",
            "analysen", "/analysen/");

    /**
     * The sections merged into the general {@link #latest} pool. A deliberate
     * subset: the four wires that actually carry market-moving copy. The rest
     * stay reachable one call away via {@link #section(String, int)} but do not
     * cost a fetch on every pool refresh.
     */
    static final List<String> POOLED_SECTIONS =
            List.of("nachrichten", "unternehmensnachrichten", "marktberichte", "adhoc");

    /** Instrument news pages to walk at most - the ISIN archive is ~5 pages deep. */
    static final int MAX_INSTRUMENT_PAGES = 5;
    /** Section pages to walk at most when a caller asks for more than one page. */
    static final int MAX_SECTION_PAGES = 3;

    private static final Duration POOL_TTL = Duration.ofMinutes(10);
    /** The firehose is 4 × ~650 kB, so it refreshes on a slower beat than the sections. */
    private static final Duration SITEMAP_TTL = Duration.ofMinutes(30);
    private static final Duration INSTRUMENT_TTL = Duration.ofMinutes(10);

    /** Generic words that must never carry the title-relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            // normalized form: significantWords() lowercases and folds umlauts first
            "the", "and", "der", "die", "das", "und", "von", "fuer",
            "inc", "incorporated", "corp", "corporation", "co", "company",
            "ag", "se", "kgaa", "gmbh", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "gruppe", "international",
            "aktie", "aktien", "stock", "stocks", "shares", "vz", "st");

    /** Newest first; items without a timestamp sort to the end. */
    private static final Comparator<RawNewsItem> BY_RECENCY =
            Comparator.comparing(RawNewsItem::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    private static final Pattern LEAD_TEASER = Pattern.compile(
            "(?is)<h3>\\s*<a\\s+href=\"([^\"]+)\"[^>]*>(.*?)</a>\\s*</h3>\\s*<p>(.*?)</p>");
    private static final Pattern ROW_LINK =
            Pattern.compile("(?is)<a\\s+href=\"([^\"]+)\"[^>]*>(.*?)</a>");
    private static final Pattern SITEMAP_LOC =
            Pattern.compile("(?is)<loc>\\s*(.*?)\\s*</loc>");
    private static final Pattern SITEMAP_URL_BLOCK =
            Pattern.compile("(?is)<url>(.*?)</url>");
    private static final Pattern NEWS_TITLE =
            Pattern.compile("(?is)<news:title>(.*?)</news:title>");
    private static final Pattern LASTMOD =
            Pattern.compile("(?is)<lastmod>\\s*(.*?)\\s*</lastmod>");

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    private volatile Cached sectionPool;
    private volatile Cached sitemapPool;
    private final Map<String, Cached> byIsin = new ConcurrentHashMap<>();

    private record Cached(Instant fetchedAt, List<RawNewsItem> items) {
        boolean fresh(Duration ttl) {
            return fetchedAt.plus(ttl).isAfter(Instant.now());
        }
    }

    /** Test/default: plain direct transport. */
    public BoerseDeNewsClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@link DirectFirst} seam (direct → browser rescue). The
     * standard browser-first chain would be wrong here for the exact reason the
     * {@code NetModule} javadoc names - boerse.de is a keyless, wall-free host
     * that answers plain HTTP in milliseconds, and paying the hidden-browser
     * warmup per page would turn a five-page instrument walk into half a minute.
     * The joker stays behind it should the host ever grow a wall.
     */
    @Inject
    public BoerseDeNewsClient(@DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "boerse-de";
    }

    /**
     * No-op: boerse.de has no ticker addressing. Every surface is keyed by ISIN
     * (or by nothing at all), so a symbol is not a question this venue can be
     * asked - {@link #newsForIsin} and {@link #newsForName} carry this source.
     */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /**
     * By ISIN: the instrument's own news list, newest first, walked up to
     * {@link #MAX_INSTRUMENT_PAGES} pages via {@code rel="next"}. Result cached
     * per ISIN for the pool TTL, so a repeated dossier pass is free.
     */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        List<RawNewsItem> all = instrumentNews(isin);
        return all.stream().limit(Math.max(limit, 0)).toList();
    }

    /**
     * By name: the pooled general stream (sections + sitemap firehose) under the
     * house TITLE-precision cut. Costs no fetch while the pool is warm, which is
     * why the general leg and the name fan deliberately share one cache.
     */
    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();
        List<RawNewsItem> pooled = mergeByLink(pool(), sitemapPool());
        return pooled.stream()
                .filter(it -> titleMatches(it.title(), words))
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /**
     * ARCHIVE window. The ISIN list is the only date-addressable surface here,
     * so a window query without an ISIN degrades to the pooled name cut clipped
     * to the window rather than answering nothing. {@code to} is exclusive.
     */
    @Override
    public List<RawNewsItem> newsForNameWindow(String companyName, String isin,
                                               String fromIsoDate, String toIsoDateExclusive,
                                               int limit) {
        if (limit <= 0) return List.of();
        Instant from = startOfDay(fromIsoDate);
        Instant toExcl = startOfDay(toIsoDateExclusive);
        if (from == null || toExcl == null) return List.of();

        List<RawNewsItem> candidates;
        if (isin != null && !isin.isBlank()) {
            candidates = instrumentNews(isin);
        } else {
            candidates = newsForName(companyName, Integer.MAX_VALUE);
        }
        return candidates.stream()
                .filter(it -> it.publishedAt() != null
                        && !it.publishedAt().isBefore(from)
                        && it.publishedAt().isBefore(toExcl))
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    // ---------------------------------------------------------------- general leg

    /**
     * The GENERAL news stream, instrument-free: the pooled section wires merged
     * with the sitemap firehose, newest first. Currently unused by the app -
     * wired for a later caller (a plain "what is the German wire carrying right
     * now" feed) without reopening this source.
     */
    public List<RawNewsItem> latest(int limit) {
        if (limit <= 0) return List.of();
        return mergeByLink(pool(), sitemapPool()).stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /**
     * One named general surface from {@link #SECTIONS}, walked up to
     * {@link #MAX_SECTION_PAGES} pages. Unknown key → empty list. Not cached
     * per section on purpose: the pooled sections already sit behind
     * {@link #latest}, and an ad-hoc section pull is an explicit request.
     */
    public List<RawNewsItem> section(String sectionKey, int limit) {
        if (limit <= 0) return List.of();
        String path = SECTIONS.get(sectionKey == null ? "" : sectionKey.trim());
        if (path == null) {
            LOG.debug("[boerse.de] unknown section '{}'", sectionKey);
            return List.of();
        }
        return walkList(BASE + path, MAX_SECTION_PAGES).stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /**
     * The news FIREHOSE over {@code /sitemap/sitemap_news.xml}: an index over
     * four child sitemaps of ~1000 article URLs each, every entry carrying its
     * {@code news:title} and {@code lastmod}. ~4000 headlines for five requests
     * - the widest recall surface boerse.de has, and the reason a name query
     * finds pieces the four polled sections never listed. Cached for
     * {@link #SITEMAP_TTL}.
     *
     * <p>Article ids are sequential and the slug is decorative: the sitemap
     * URL {@code /nachrichten/<Slug>/38496476} and the bare
     * {@code /nachrichten/x/38496476} serve the same dpa-AFX full text, so a
     * downstream body reader can address an item by id alone.
     */
    public List<RawNewsItem> sitemapLatest(int limit) {
        if (limit <= 0) return List.of();
        return sitemapPool().stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    // ------------------------------------------------------------------ internals

    /** The per-ISIN news list behind a TTL cache, newest first. */
    private List<RawNewsItem> instrumentNews(String isin) {
        if (isin == null || isin.isBlank()) return List.of();
        String key = isin.trim().toUpperCase(Locale.ROOT);
        Cached c = byIsin.get(key);
        if (c != null && c.fresh(INSTRUMENT_TTL)) return c.items();

        List<RawNewsItem> items = walkList(String.format(ISIN_NEWS_URL, key), MAX_INSTRUMENT_PAGES)
                .stream()
                .map(it -> withIsin(it, key))
                .sorted(BY_RECENCY)
                .toList();
        if (items.isEmpty() && c != null) return c.items(); // outage: keep the stale list
        byIsin.put(key, new Cached(Instant.now(), items));
        return items;
    }

    /** The merged, cached section pool; refreshed at most once per {@link #POOL_TTL}. */
    private List<RawNewsItem> pool() {
        Cached c = sectionPool;
        if (c != null && c.fresh(POOL_TTL)) return c.items();
        synchronized (this) {
            c = sectionPool;
            if (c != null && c.fresh(POOL_TTL)) return c.items();
            List<RawNewsItem> merged = new ArrayList<>();
            for (String key : POOLED_SECTIONS) {
                merged.addAll(walkList(BASE + SECTIONS.get(key), 1));
            }
            List<RawNewsItem> deduped = mergeByLink(merged, List.of());
            if (deduped.isEmpty() && c != null) deduped = c.items();
            sectionPool = new Cached(Instant.now(), deduped);
            return deduped;
        }
    }

    /** The cached sitemap firehose; refreshed at most once per {@link #SITEMAP_TTL}. */
    private List<RawNewsItem> sitemapPool() {
        Cached c = sitemapPool;
        if (c != null && c.fresh(SITEMAP_TTL)) return c.items();
        synchronized (this) {
            c = sitemapPool;
            if (c != null && c.fresh(SITEMAP_TTL)) return c.items();
            List<RawNewsItem> merged = new ArrayList<>();
            for (String child : sitemapChildren(get(SITEMAP_INDEX_URL))) {
                merged.addAll(parseNewsSitemap(get(child)));
            }
            List<RawNewsItem> deduped = mergeByLink(merged, List.of());
            if (deduped.isEmpty() && c != null) deduped = c.items();
            sitemapPool = new Cached(Instant.now(), deduped);
            return deduped;
        }
    }

    /**
     * Walks a paginated list surface by following its own {@code rel="next"} -
     * never by composing {@code _seite,N} onto the {@code /x/} form, which the
     * site 301s back to page 1 (see the class comment).
     */
    private List<RawNewsItem> walkList(String firstUrl, int maxPages) {
        List<RawNewsItem> out = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        String url = firstUrl;
        for (int page = 0; page < maxPages && url != null && seenUrls.add(url); page++) {
            String html = get(url);
            if (html == null || html.isEmpty()) break;
            List<RawNewsItem> batch = parseNewsList(html);
            if (batch.isEmpty()) break;
            out.addAll(batch);
            url = BoerseDeHtml.relNext(html);
        }
        return mergeByLink(out, List.of());
    }

    private String get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent,
                            "Accept", "text/html,application/xhtml+xml,application/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            // 410 = ISIN unknown to boerse.de; nothing to rescue, nothing to log loudly.
            LOG.debug("[boerse.de] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[boerse.de] fetch failed for {}: {}", url, e.getMessage());
        }
        return null;
    }

    // -------------------------------------------------------------------- parsers

    /**
     * Parses a boerse.de list page: the illustrated LEAD teaser (headline, link,
     * summary - the only item that carries body text) plus every
     * {@code row row-bordered} row (date + headline + link). Package-private for
     * tests; network-free and garbage-tolerant.
     */
    static List<RawNewsItem> parseNewsList(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            Matcher lead = LEAD_TEASER.matcher(html);
            if (lead.find()) {
                String link = BoerseDeHtml.decodeEntities(lead.group(1));
                String title = BoerseDeHtml.text(lead.group(2));
                String summary = BoerseDeHtml.text(lead.group(3));
                // The teaser opens with a run-together yyyyMMdd on dpa-AFX copy;
                // the lead has no date cell of its own, so that is the only date.
                Instant at = BoerseDeHtml.atBerlinStartOfDay(
                        BoerseDeHtml.parseCompactDate(summary));
                RawNewsItem item = item(link, title, summary, at);
                if (item != null) out.add(item);
            }
            for (String row : BoerseDeHtml.chunksAt(html, "<div class=\"row row-bordered")) {
                LocalDate date = BoerseDeHtml.parseGermanDate(BoerseDeHtml.text(row));
                Matcher m = ROW_LINK.matcher(row);
                if (!m.find()) continue;
                RawNewsItem item = item(BoerseDeHtml.decodeEntities(m.group(1)),
                        BoerseDeHtml.text(m.group(2)), null,
                        BoerseDeHtml.atBerlinStartOfDay(date));
                if (item != null) out.add(item);
            }
        } catch (Exception e) {
            LOG.warn("Unparseable boerse.de list page: {}", e.getMessage());
            return List.of();
        }
        return mergeByLink(out, List.of());
    }

    /** The child sitemap URLs of the news sitemap index. Package-private for tests. */
    static List<String> sitemapChildren(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        Matcher m = SITEMAP_LOC.matcher(xml);
        while (m.find()) {
            String loc = BoerseDeHtml.decodeEntities(m.group(1));
            if (!loc.isBlank()) out.add(loc);
        }
        return List.copyOf(out);
    }

    /**
     * Parses one {@code sitemap-news} child: each {@code <url>} carries the
     * article link, {@code <lastmod>} (an ISO offset timestamp - a real clock,
     * unlike the day-granular list pages) and the Google-News
     * {@code <news:title>}. Package-private for tests.
     */
    static List<RawNewsItem> parseNewsSitemap(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            Matcher blocks = SITEMAP_URL_BLOCK.matcher(xml);
            while (blocks.find()) {
                String block = blocks.group(1);
                Matcher loc = SITEMAP_LOC.matcher(block);
                if (!loc.find()) continue;
                Matcher title = NEWS_TITLE.matcher(block);
                if (!title.find()) continue;
                Matcher mod = LASTMOD.matcher(block);
                Instant at = mod.find() ? parseIsoInstant(mod.group(1)) : null;
                RawNewsItem item = item(BoerseDeHtml.decodeEntities(loc.group(1)),
                        BoerseDeHtml.text(title.group(1)), null, at);
                if (item != null) out.add(item);
            }
        } catch (Exception e) {
            LOG.warn("Unparseable boerse.de news sitemap: {}", e.getMessage());
            return List.of();
        }
        return mergeByLink(out, List.of());
    }

    /** One item, or {@code null} when link/title are unusable. */
    private static RawNewsItem item(String link, String title, String summary, Instant at) {
        if (link == null || link.isBlank() || title == null || title.isBlank()) return null;
        String id = BoerseDeHtml.articleId(link);
        return new RawNewsItem(id != null ? id : link, title, PUBLISHER, link, at,
                List.of(), null, summary == null || summary.isBlank() ? null : summary,
                false, null);
    }

    /** Stamps the queried ISIN onto an item - the page, not the row, carries it. */
    static RawNewsItem withIsin(RawNewsItem it, String isin) {
        return new RawNewsItem(it.uuid(), it.title(), it.publisher(), it.link(),
                it.publishedAt(), it.relatedTickers(), isin, it.summary(),
                it.sponsored(), it.imageUrl());
    }

    /** Dedupes by article id (falling back to the link); first occurrence wins. */
    static List<RawNewsItem> mergeByLink(List<RawNewsItem> first, List<RawNewsItem> second) {
        Map<String, RawNewsItem> byKey = new LinkedHashMap<>();
        for (RawNewsItem it : first) byKey.putIfAbsent(identity(it), it);
        for (RawNewsItem it : second) byKey.putIfAbsent(identity(it), it);
        return List.copyOf(byKey.values());
    }

    /**
     * The merge key: the numeric article id where the link carries one. The same
     * piece appears under different slugs across surfaces (the sitemap prints a
     * headline slug, a section list may print another), so the LINK alone would
     * dedupe nothing.
     */
    static String identity(RawNewsItem it) {
        String id = BoerseDeHtml.articleId(it.link());
        return id != null ? id : String.valueOf(it.link());
    }

    /** {@code 2026-08-02T21:38:06+00:00} → instant; unparseable → null. */
    static Instant parseIsoInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s.trim()).toInstant();
        } catch (Exception e) {
            try {
                return LocalDate.parse(s.trim())
                        .atStartOfDay(BoerseDeHtml.ZONE).toInstant();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /**
     * ISO date → the instant that day STARTS IN BERLIN; unparseable → null.
     * Deliberately not UTC: the list pages date items to a German trading day
     * with no clock, so a UTC window bound would shift every item two hours and
     * push a whole day out of its own window.
     */
    static Instant startOfDay(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return null;
        try {
            return LocalDate.parse(isoDate.trim()).atStartOfDay(BoerseDeHtml.ZONE).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the text carries at least one significant word of the queried name. */
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

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }
}
