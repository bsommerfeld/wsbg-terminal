package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The company's OWN site, walked instead of peeked at: a bounded best-first
 * crawler over one host family. Where the earlier scout followed exactly one
 * link ("the most press-like anchor on the homepage") and stopped, this one
 * keeps a scored frontier — every anchor whose href or link text smells of a
 * press, newsroom, IR, report or calendar section is queued, ranked by how
 * specific that smell is and how deep it sits, and the best candidates are
 * fetched in small parallel batches until a page, depth or wall-clock budget
 * runs out. Two cheap autodiscovery legs run first because corporate sites
 * publish their own map: {@code robots.txt}/{@code sitemap.xml} for the URL
 * inventory, and the {@code <link rel=alternate>} feeds, whose items ARE
 * press headlines and need no HTML heuristics at all.
 *
 * <p>The crawl produces raw material only — pages with their per-mode scores
 * and any feed items. Deciding what is a headline and what is a report entry
 * stays with {@link CompanyPressScout}.
 */
final class CompanySiteCrawler {

    private static final Logger LOG = LoggerFactory.getLogger(CompanySiteCrawler.class);

    /** Pages fetched per crawl, autodiscovery legs included. */
    static final int MAX_PAGES = 24;
    /** Hops away from the homepage; a newsroom's year archive sits at 2-3. */
    static final int MAX_DEPTH = 3;
    /** Pages in flight — the sweep sits on the DD's serial path, so wall clock rules. */
    private static final int BATCH = 4;
    /**
     * The item/section split. A link carrying a headline-shaped text IS the
     * story — harvesting it off the page it sits on costs nothing, while
     * opening it would spend a page of budget to learn what the anchor already
     * said. Only short, label-shaped links ("Presse", "Finanzberichte",
     * "Newsroom") are navigation, and navigation is what a frontier is for.
     */
    static final int ITEM_TITLE_CHARS = 25;
    private static final Duration PAGE_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration TOTAL_BUDGET = Duration.ofSeconds(20);
    private static final int MAX_HTML_CHARS = 400_000;
    private static final int MAX_SITEMAP_URLS = 60;
    private static final int MAX_SITEMAP_CHILDREN = 2;
    private static final int MAX_FEEDS = 2;
    private static final int MAX_FEED_ITEMS = 15;
    /**
     * Pages per directory. Measured live on hellofresh.de 2026-08-03: one
     * recipe-story directory filled 19 of 20 slots and the press section never
     * got a look in. A site has more than one shelf; the walk must see more
     * than one.
     */
    private static final int MAX_PAGES_PER_DIR = 6;

    /**
     * Press-section hints in priority order — matched against href or link
     * text, German and English corporate conventions.
     */
    static final String[] PRESS_HINTS = {
            "pressemitteilung", "press-release", "pressrelease", "newsroom",
            "presse", "press", "media-center", "mediacenter", "investor-relations",
            "investor", "mitteilungen", "adhoc", "ad-hoc", "aktuelles", "news",
    };

    /**
     * IR-archive hints in priority order — the financial-reports/calendar
     * section, NOT the press stream.
     */
    static final String[] IR_HINTS = {
            "finanzberichte", "quartalsbericht", "geschaeftsbericht", "financial-report",
            "quarterly-report", "quarterly-result", "financial-result", "finanzkalender",
            "financial-calendar", "ir-kalender", "hauptversammlung", "annual-general",
            "publikationen", "publications", "berichte", "reports", "results",
            "praesentationen", "presentations", "investor-relations", "investor",
    };

    /**
     * URL/link-text vocabulary that is corporate chrome, never editorial —
     * queueing it burns the page budget on pages that can hold no headline.
     */
    private static final String[] CRAWL_NOISE = {
            "karriere", "career", "jobs", "stellenangebot", "impressum", "imprint",
            "datenschutz", "privacy", "cookie", "kontakt", "contact", "login",
            "anmelden", "sitemap.xml", "agb", "terms", "newsletter", "webshop",
            "warenkorb", "cart", "checkout", "suche", "search", "glossar",
            "faq", "hilfe", "support", "barrierefrei", "accessibility",
    };

    /** Extensions worth linking to but never worth crawling into. */
    private static final Pattern BINARY_TAIL = Pattern.compile(
            "(?i)\\.(pdf|zip|docx?|xlsx?|pptx?|csv|jpe?g|png|gif|svg|webp|mp4|mp3|ics)$");

    private static final Pattern ANCHOR =
            Pattern.compile("(?is)<a\\s[^>]*?href=[\"']([^\"'#>]+)[\"'][^>]*>(.*?)</a>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern SITEMAP_LOC = Pattern.compile("(?is)<loc>\\s*([^<\\s]+)\\s*</loc>");
    private static final Pattern ROBOTS_SITEMAP =
            Pattern.compile("(?im)^\\s*sitemap\\s*:\\s*(\\S+)\\s*$");
    private static final Pattern FEED_LINK = Pattern.compile(
            "(?is)<link\\s[^>]*(?:type=[\"']application/(?:rss|atom)\\+xml[\"'][^>]*"
                    + "href=[\"']([^\"']+)[\"']|href=[\"']([^\"']+)[\"'][^>]*"
                    + "type=[\"']application/(?:rss|atom)\\+xml[\"'])[^>]*>");
    private static final Pattern FEED_ENTRY = Pattern.compile("(?is)<(item|entry)[\\s>].*?</\\1>");
    private static final Pattern FEED_TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern FEED_LINK_TEXT = Pattern.compile("(?is)<link[^>]*>([^<]+)</link>");
    private static final Pattern FEED_LINK_HREF =
            Pattern.compile("(?is)<link\\s[^>]*href=[\"']([^\"']+)[\"']");
    private static final Pattern CDATA = Pattern.compile("(?is)<!\\[CDATA\\[(.*?)]]>");

    /** One anchor of a page, its inner markup already flattened to plain text. */
    record Anchor(String href, String text) {
    }

    /** A fetched page with the two section scores that got it queued. */
    record Page(String url, String html, int depth, int pressScore, int irScore) {
    }

    /** A headline straight out of the company's own feed — no heuristics involved. */
    record FeedItem(String title, String url) {
    }

    /** Everything one walk of a company site brought back. */
    record Crawl(URI home, List<Page> pages, List<FeedItem> feedItems) {

        static Crawl empty() {
            return new Crawl(null, List.of(), List.of());
        }

        boolean isEmpty() {
            return home == null || (pages.isEmpty() && feedItems.isEmpty());
        }
    }

    /**
     * A frontier entry: not yet fetched, ranked by section smell minus how far
     * it sits from the root. Both distances count. The hop count is how far the
     * walk has strayed; the path depth is the site's own statement about what
     * a URL is - a listing lives high ({@code /ir-news/investor-relations}),
     * one of its items lives below it. Measured on nagarro.com 2026-08-03,
     * where card links are too short to read as items and the article pages
     * outranked the listing that names them all.
     */
    private record Candidate(String url, int depth, int pressScore, int irScore, int seq) {

        int priority() {
            return Math.max(pressScore, irScore) - depth * 12 - pathSegments(url) * 5;
        }
    }

    static int pathSegments(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isBlank()) return 0;
            int n = 0;
            for (String seg : path.split("/")) {
                if (!seg.isBlank()) n++;
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    private final WebFetcher fetcher;
    private final String userAgent;

    CompanySiteCrawler(WebFetcher fetcher, String userAgent) {
        this.fetcher = fetcher;
        this.userAgent = userAgent;
    }

    /**
     * Walks the company site best-first and returns what the budget allowed.
     * Empty on any failure — a corporate site that resists costs its leg,
     * never the report.
     */
    Crawl crawl(String website) {
        if (fetcher == null || website == null || website.isBlank()) return Crawl.empty();
        URI home = normalize(website);
        if (home == null) return Crawl.empty();
        long deadline = System.nanoTime() + TOTAL_BUDGET.toNanos();

        List<Page> pages = new ArrayList<>();
        List<FeedItem> feedItems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Integer> perDir = new java.util.HashMap<>();
        PriorityQueue<Candidate> frontier = new PriorityQueue<>(
                Comparator.comparingInt(Candidate::priority).reversed()
                        .thenComparingInt(Candidate::seq));
        int[] seq = {0};

        String homeUrl = home.toString();
        seen.add(canonical(homeUrl));
        ExecutorService pool = Executors.newFixedThreadPool(BATCH, r -> {
            Thread t = new Thread(r, "dd-site-crawler");
            t.setDaemon(true);
            return t;
        });
        try {
            String homeHtml = fetch(homeUrl);
            if (homeHtml == null) return Crawl.empty();
            int fetched = 1;
            pages.add(new Page(homeUrl, homeHtml, 0, 1, 1));
            enqueue(frontier, seen, seq, perDir, home, anchors(homeHtml), homeUrl, 1);

            fetched += autodiscover(home, homeHtml, frontier, seen, seq, perDir, feedItems, deadline);

            while (fetched < MAX_PAGES && !frontier.isEmpty() && System.nanoTime() < deadline) {
                List<Candidate> batch = new ArrayList<>();
                while (batch.size() < BATCH && batch.size() + fetched < MAX_PAGES
                        && !frontier.isEmpty()) {
                    batch.add(frontier.poll());
                }
                List<Future<String>> futures = new ArrayList<>();
                for (Candidate c : batch) futures.add(pool.submit(fetchTask(c.url())));
                for (int i = 0; i < batch.size(); i++) {
                    Candidate c = batch.get(i);
                    fetched++;
                    String html = await(futures.get(i), deadline);
                    if (html == null) continue;
                    pages.add(new Page(c.url(), html, c.depth(), c.pressScore(), c.irScore()));
                    if (c.depth() < MAX_DEPTH) {
                        enqueue(frontier, seen, seq, perDir, home, anchors(html), c.url(), c.depth() + 1);
                    }
                }
            }
            LOG.info("[DEEPDIVE] site crawl {}: {} page(s), {} feed item(s), {} left in frontier",
                    home.getHost(), pages.size(), feedItems.size(), frontier.size());
            return new Crawl(home, pages, feedItems);
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] site crawl failed for '{}': {}", website, e.getMessage());
            return pages.isEmpty() ? Crawl.empty() : new Crawl(home, pages, feedItems);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- autodiscovery: the site's own map, before any guessing ----

    /**
     * The two cheap legs a corporate site hands over for free: its sitemap
     * (URL inventory, scored into the frontier like any anchor) and its
     * declared feeds (finished headlines). Returns how many pages this cost.
     */
    private int autodiscover(URI home, String homeHtml, PriorityQueue<Candidate> frontier,
                             Set<String> seen, int[] seq, Map<String, Integer> perDir,
                             List<FeedItem> feedItems, long deadline) {
        int cost = 0;
        // -- feeds: whatever the site declares, plus nothing invented --
        List<String> feeds = new ArrayList<>();
        Matcher fm = FEED_LINK.matcher(homeHtml);
        while (fm.find() && feeds.size() < MAX_FEEDS) {
            String href = fm.group(1) != null ? fm.group(1) : fm.group(2);
            String abs = resolve(home, decodeEntities(href.strip()));
            if (abs != null && sameHostFamily(home, abs) && !feeds.contains(abs)) feeds.add(abs);
        }
        for (String feed : feeds) {
            if (System.nanoTime() >= deadline) break;
            cost++;
            String xml = fetch(feed);
            if (xml == null) continue;
            for (FeedItem item : parseFeed(xml, URI.create(feed))) {
                if (feedItems.size() >= MAX_FEED_ITEMS) break;
                feedItems.add(item);
            }
        }
        // -- sitemap: robots.txt names it, the well-known path is the fallback --
        if (System.nanoTime() >= deadline) return cost;
        List<String> sitemaps = new ArrayList<>();
        cost++;
        String robots = fetch(home.resolve("/robots.txt").toString());
        if (robots != null) {
            Matcher rm = ROBOTS_SITEMAP.matcher(robots);
            while (rm.find() && sitemaps.size() < MAX_SITEMAP_CHILDREN) {
                String abs = resolve(home, rm.group(1).strip());
                if (abs != null && sameHostFamily(home, abs)) sitemaps.add(abs);
            }
        }
        if (sitemaps.isEmpty()) sitemaps.add(home.resolve("/sitemap.xml").toString());
        for (String sitemap : sitemaps) {
            if (System.nanoTime() >= deadline || cost > MAX_SITEMAP_CHILDREN + MAX_FEEDS) break;
            cost++;
            String xml = fetch(sitemap);
            if (xml == null) continue;
            List<String> locs = sitemapLocs(xml);
            boolean index = xml.contains("<sitemapindex");
            if (index) {
                // A sitemap index: descend into the best-scoring children only.
                List<String> children = rankByHints(locs, MAX_SITEMAP_CHILDREN);
                for (String child : children) {
                    if (System.nanoTime() >= deadline) break;
                    cost++;
                    String childXml = fetch(child);
                    if (childXml != null) {
                        queueSitemapUrls(sitemapLocs(childXml), home, frontier, seen, seq, perDir);
                    }
                }
            } else {
                queueSitemapUrls(locs, home, frontier, seen, seq, perDir);
            }
        }
        return cost;
    }

    private void queueSitemapUrls(List<String> locs, URI home, PriorityQueue<Candidate> frontier,
                                  Set<String> seen, int[] seq, Map<String, Integer> perDir) {
        int queued = 0;
        for (String loc : locs) {
            if (queued >= MAX_SITEMAP_URLS) break;
            String abs = resolve(home, decodeEntities(loc));
            if (abs == null || !sameHostFamily(home, abs) || !crawlable(abs)) continue;
            int press = hintScore(abs, PRESS_HINTS);
            int ir = hintScore(abs, IR_HINTS);
            if (press == 0 && ir == 0) continue;
            // Sitemap URLs arrive without a hop count; treat them as depth 1 —
            // the site vouched for them, they should outrank a deep guess.
            if (offer(frontier, seen, seq, perDir, abs, 1, press, ir)) queued++;
        }
    }

    /** The best-scoring entries of a URL list, most specific hint first. */
    private static List<String> rankByHints(List<String> urls, int limit) {
        List<String> scored = new ArrayList<>(urls);
        scored.sort(Comparator.comparingInt(
                (String u) -> Math.max(hintScore(u, PRESS_HINTS), hintScore(u, IR_HINTS)))
                .reversed());
        List<String> out = new ArrayList<>();
        for (String u : scored) {
            if (out.size() >= limit) break;
            if (Math.max(hintScore(u, PRESS_HINTS), hintScore(u, IR_HINTS)) == 0) break;
            out.add(u);
        }
        return out;
    }

    static List<String> sitemapLocs(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = SITEMAP_LOC.matcher(xml);
        while (m.find()) out.add(m.group(1).strip());
        return out;
    }

    /** RSS/Atom items as ready headlines - the one path that needs no heuristics. */
    static List<FeedItem> parseFeed(String xml, URI base) {
        List<FeedItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher entries = FEED_ENTRY.matcher(xml);
        while (entries.find()) {
            String block = entries.group();
            Matcher tm = FEED_TITLE.matcher(block);
            if (!tm.find()) continue;
            String title = flatten(uncdata(tm.group(1)));
            if (title.isBlank()) continue;
            String href = null;
            Matcher lt = FEED_LINK_TEXT.matcher(block);
            if (lt.find()) href = flatten(uncdata(lt.group(1)));
            if (href == null || href.isBlank()) {
                Matcher lh = FEED_LINK_HREF.matcher(block);
                if (lh.find()) href = lh.group(1).strip();
            }
            if (href == null || href.isBlank()) continue;
            String abs = resolve(base, decodeEntities(href));
            if (abs == null || !seen.add(abs)) continue;
            out.add(new FeedItem(title, abs));
        }
        return out;
    }

    // ---- frontier ----

    private void enqueue(PriorityQueue<Candidate> frontier, Set<String> seen, int[] seq,
                         Map<String, Integer> perDir, URI home, List<Anchor> anchors,
                         String pageUrl, int depth) {
        URI base = URI.create(pageUrl);
        for (Anchor a : anchors) {
            String abs = resolve(base, a.href());
            if (abs == null || !sameHostFamily(home, abs) || !crawlable(abs)) continue;
            if (a.text().length() >= ITEM_TITLE_CHARS) continue;
            String hay = abs.toLowerCase(Locale.ROOT) + " " + a.text().toLowerCase(Locale.ROOT);
            if (containsAny(hay, CRAWL_NOISE)) continue;
            int press = hintScore(hay, PRESS_HINTS);
            int ir = hintScore(hay, IR_HINTS);
            if (press == 0 && ir == 0) continue;
            offer(frontier, seen, seq, perDir, abs, depth, press, ir);
        }
    }

    private static boolean offer(PriorityQueue<Candidate> frontier, Set<String> seen, int[] seq,
                                 Map<String, Integer> perDir, String url, int depth,
                                 int press, int ir) {
        if (!seen.add(canonical(url))) return false;
        String dir = directory(url);
        int taken = perDir.getOrDefault(dir, 0);
        if (taken >= MAX_PAGES_PER_DIR) return false;
        perDir.put(dir, taken + 1);
        frontier.add(new Candidate(url, depth, press, ir, seq[0]++));
        return true;
    }

    /** The shelf a URL sits on: its path up to the last slash. */
    static String directory(String url) {
        String c = canonical(stripQuery(url));
        int slash = c.lastIndexOf('/');
        return slash > "https://".length() ? c.substring(0, slash) : c;
    }

    /**
     * How strongly a URL/link text smells of a section: the FIRST (most
     * specific) hint that matches decides, so "pressemitteilungen" outranks a
     * bare "news" the way the single-hop scout's rank order did.
     */
    static int hintScore(String hay, String[] hints) {
        String lower = hay.toLowerCase(Locale.ROOT);
        for (int rank = 0; rank < hints.length; rank++) {
            if (lower.contains(hints[rank])) return Math.max(1, 100 - rank * 4);
        }
        return 0;
    }

    private static boolean containsAny(String hay, String[] needles) {
        for (String n : needles) {
            if (hay.contains(n)) return true;
        }
        return false;
    }

    /** A PDF report is a fine link and a hopeless page - link to it, never open it. */
    static boolean crawlable(String url) {
        return !BINARY_TAIL.matcher(stripQuery(url)).find();
    }

    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    /** Same page, different spelling: host case and a trailing slash don't count. */
    static String canonical(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    // ---- fetching ----

    private Callable<String> fetchTask(String url) {
        return () -> fetch(url);
    }

    private static String await(Future<String> f, long deadline) {
        long left = deadline - System.nanoTime();
        try {
            return f.get(Math.max(1, left), java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            f.cancel(true);
            return null;
        }
    }

    private String fetch(String url) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        try {
            WebResponse resp = fetcher.fetch(url, headers, PAGE_TIMEOUT);
            if (resp == null || resp.status() != 200 || resp.body() == null) return null;
            String body = resp.body();
            return body.length() > MAX_HTML_CHARS ? body.substring(0, MAX_HTML_CHARS) : body;
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] site crawl fetch failed {}: {}", url, e.getMessage());
            return null;
        }
    }

    // ---- HTML primitives (shared with CompanyPressScout) ----

    static List<Anchor> anchors(String html) {
        List<Anchor> out = new ArrayList<>();
        Matcher m = ANCHOR.matcher(html);
        while (m.find()) out.add(new Anchor(m.group(1).strip(), flatten(m.group(2))));
        return out;
    }

    static String flatten(String inner) {
        String text = WS.matcher(TAG.matcher(inner).replaceAll(" ")).replaceAll(" ").strip();
        return decodeEntities(text);
    }

    /**
     * HTML entities out of anchor text - a headline reading
     * "Gesch&auml;ftsbericht" is a headline the model has to guess at, and it
     * would carry the raw markup into the report. Numeric references are
     * decoded generically; the named ones are the handful HTML inherited from
     * SGML plus the German umlauts corporate CMS still emit.
     */
    static String decodeEntities(String text) {
        if (text.indexOf('&') < 0) return text;
        Matcher m = NUMERIC_ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp;
            try {
                cp = m.group(1) != null
                        ? Integer.parseInt(m.group(1))
                        : Integer.parseInt(m.group(2), 16);
            } catch (NumberFormatException e) {
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : ""));
        }
        m.appendTail(sb);
        String out = sb.toString();
        for (int i = 0; i < NAMED_ENTITIES.length; i += 2) {
            if (out.indexOf('&') < 0) break;
            out = out.replace(NAMED_ENTITIES[i], NAMED_ENTITIES[i + 1]);
        }
        return out;
    }

    private static final Pattern NUMERIC_ENTITY =
            Pattern.compile("&#(?:(\\d{1,7})|[xX]([0-9a-fA-F]{1,6}));");

    private static final String[] NAMED_ENTITIES = {
            "&auml;", "ä", "&ouml;", "ö", "&uuml;", "ü", "&Auml;", "Ä", "&Ouml;", "Ö",
            "&Uuml;", "Ü", "&szlig;", "ß", "&nbsp;", " ", "&quot;", "\"", "&apos;", "'",
            "&laquo;", "«", "&raquo;", "»", "&bdquo;", "„", "&ldquo;", "“", "&rdquo;", "”",
            "&ndash;", "-", "&mdash;", "-", "&hellip;", "…", "&euro;", "€", "&lt;", "<",
            "&gt;", ">", "&amp;", "&",
    };

    private static String uncdata(String s) {
        Matcher m = CDATA.matcher(s);
        return m.find() ? m.group(1) : s;
    }

    /** A scheme-less profile URL still resolves ({@code www.sap.com} → https). */
    static URI normalize(String website) {
        String w = website.strip();
        if (!w.startsWith("http://") && !w.startsWith("https://")) w = "https://" + w;
        try {
            URI uri = URI.create(w);
            return uri.getHost() == null ? null : uri;
        } catch (Exception e) {
            return null;
        }
    }

    static String resolve(URI base, String href) {
        try {
            if (href.isEmpty() || href.startsWith("javascript:") || href.startsWith("mailto:")
                    || href.startsWith("tel:")) {
                return null;
            }
            URI resolved = base.resolve(href);
            if (resolved.getHost() == null) return null;
            String scheme = resolved.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
            return resolved.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code news.sap.com} belongs to {@code www.sap.com} — compare the registrable tail. */
    static boolean sameHostFamily(URI base, String url) {
        try {
            String a = tail(base.getHost());
            String b = tail(URI.create(url).getHost());
            return a != null && a.equals(b);
        } catch (Exception e) {
            return false;
        }
    }

    private static String tail(String host) {
        if (host == null) return null;
        String[] parts = host.toLowerCase(Locale.ROOT).split("\\.");
        if (parts.length < 2) return host.toLowerCase(Locale.ROOT);
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }
}
