package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The first-party leg: the company's OWN website (Consorsbank delivers
 * the official URL with every profile) carries the announcements the wire
 * services paraphrase — restructurings, quarterly releases, regulatory
 * statements — plus the report archive and the financial calendar. The
 * {@link CompanySiteCrawler} walks that site; this scout reads what it brought
 * back: headline-shaped links become news candidates (publisher = the
 * company's own site) and report-shaped links become IR entries. Both legs
 * share ONE walk, and both stay best-effort — a corporate site that resists
 * costs its leg, never the report.
 */
final class CompanyPressScout {

    private static final Logger LOG = LoggerFactory.getLogger(CompanyPressScout.class);

    /**
     * Headline-shaped anchor text: long enough to be a title, short enough to
     * be one. Deliberately the crawler's item/section threshold - what the
     * frontier declines to open because it is already a story is exactly what
     * this harvest picks up.
     */
    private static final int MIN_TITLE_CHARS = CompanySiteCrawler.ITEM_TITLE_CHARS;
    private static final int MAX_TITLE_CHARS = 180;

    /**
     * The score of a page nothing vouched for - the homepage. Measured live on
     * siemens.com 2026-08-03: harvesting it flat put "Your Platinum Partner
     * for Siemens software success" ahead of three actual press releases, so a
     * page WITHOUT its own section context only contributes links that carry
     * the smell themselves, and every page hands over its best-smelling links
     * first.
     */
    private static final int FLOOR_SCORE = 1;

    /** How strongly one harvested link carries a section's vocabulary itself. */
    private static int smell(String url, String title, String[] hints) {
        return CompanySiteCrawler.hintScore(url + " " + title, hints);
    }

    /**
     * Link vocabulary that is navigation chrome, never editorial - matched
     * against the URL as well as the text, because a career teaser rarely says
     * "Karriere" in the words a visitor reads ("HelloFresh als Arbeitsplatz",
     * measured 2026-08-03) while its URL always does.
     */
    private static final String[] NAV_NOISE = {
            "cookie", "impressum", "datenschutz", "privacy", "kontakt", "contact",
            "login", "anmelden", "karriere", "career", "sitemap", "agb", "terms",
            "newsletter", "mehr erfahren", "read more", "weiterlesen", "startseite",
    };

    private final CompanySiteCrawler crawler;

    CompanyPressScout(WebFetcher fetcher, String userAgent) {
        this(new CompanySiteCrawler(fetcher, userAgent));
    }

    CompanyPressScout(CompanySiteCrawler crawler) {
        this.crawler = crawler;
    }

    /** One walk of the company site, to be handed to both harvest modes. */
    CompanySiteCrawler.Crawl crawl(String website) {
        return crawler == null ? CompanySiteCrawler.Crawl.empty() : crawler.crawl(website);
    }

    /**
     * Lifts up to {@code limit} headline candidates from everything the walk
     * touched — the declared feed first (finished headlines, no heuristics),
     * then the crawled pages in press-relevance order, so a newsroom listing
     * fills the slots before a stray section page ever gets a look in.
     */
    List<Article> pressItems(CompanySiteCrawler.Crawl crawl, int limit) {
        if (crawl == null || crawl.isEmpty()) return List.of();
        String publisher = crawl.home().getHost() + " (IR/Presse)";
        List<Article> out = new ArrayList<>();
        Set<String> sections = sectionUrls(crawl);
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        for (CompanySiteCrawler.FeedItem item : crawl.feedItems()) {
            if (out.size() >= limit) break;
            String title = item.title();
            if (title.length() < 8 || title.length() > MAX_TITLE_CHARS) continue;
            if (!seenUrls.add(item.url())
                    || !seenTitles.add(title.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(new Article(item.url(), title, publisher, item.url(), null, List.of()));
        }
        for (CompanySiteCrawler.Page page : byScore(crawl, CompanySiteCrawler.Page::pressScore)) {
            if (out.size() >= limit) break;
            List<Headline> found = extractHeadlines(page.html(), URI.create(page.url()));
            found.sort(Comparator.comparingInt(
                    (Headline h) -> smell(h.url(), h.title(), CompanySiteCrawler.PRESS_HINTS))
                    .reversed());
            boolean ownSmellRequired = page.pressScore() <= FLOOR_SCORE;
            for (Headline h : found) {
                if (out.size() >= limit) break;
                if (ownSmellRequired
                        && smell(h.url(), h.title(), CompanySiteCrawler.PRESS_HINTS) == 0) {
                    continue;
                }
                if (sections.contains(CompanySiteCrawler.canonical(h.url()))) continue;
                if (!seenUrls.add(h.url()) || !seenTitles.add(h.title().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                out.add(new Article(h.url(), h.title(), publisher, h.url(), null, List.of()));
            }
        }
        if (!out.isEmpty()) {
            LOG.info("[FIRMENSITE] press scout lifted {} item(s) from {} ({} page(s) walked)",
                    out.size(), crawl.home().getHost(), crawl.pages().size());
        }
        return out;
    }

    /** Convenience for callers that want the press leg alone (smokes, tests). */
    List<Article> pressItems(String website, int limit) {
        return pressItems(crawl(website), limit);
    }

    // ---- the IR ARCHIVE mode (first-party reports + financial calendar) ----

    /** A report-shaped anchor: it must smell like a filing, call or calendar entry. */
    private static final Pattern IR_ENTRY_TOKEN = Pattern.compile(
            "(?i)quartal|zwischenmitteilung|halbjahr|geschäftsbericht|geschaeftsbericht"
                    + "|jahresbericht|finanzbericht|hauptversammlung|kapitalmarkttag"
                    + "|\\bq[1-4]\\b|quarter|half-year|annual report|interim|report|results"
                    + "|earnings|call|webcast|präsentation|praesentation|presentation"
                    + "|statement|10-k|10-q|20-f");
    private static final Pattern IR_DATE_ISO = Pattern.compile("(20\\d{2})-(\\d{2})-(\\d{2})");
    private static final Pattern IR_DATE_DOTTED =
            Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(20\\d{2})");
    private static final Pattern IR_YEAR = Pattern.compile("\\b(20\\d{2})\\b");

    /** One dated entry of the company's IR archive (date null when undatable). */
    record IrEntry(String title, String dateIso, String url) {
    }

    /**
     * Lifts up to {@code limit} report/calendar entries from the walk - the
     * FIRST-PARTY record of past quarters and coming dates the press only
     * paraphrases (user mandate 2026-07-16 "Ist, Soll UND vergangener
     * Stand"). Pages are read in IR-relevance order, so the report archive
     * fills the slots before anything else.
     */
    List<IrEntry> irEntries(CompanySiteCrawler.Crawl crawl, int limit) {
        if (crawl == null || crawl.isEmpty()) return List.of();
        List<IrEntry> out = new ArrayList<>();
        Set<String> sections = sectionUrls(crawl);
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        for (CompanySiteCrawler.Page page : byScore(crawl, CompanySiteCrawler.Page::irScore)) {
            if (out.size() >= limit) break;
            boolean ownSmellRequired = page.irScore() <= FLOOR_SCORE;
            for (IrEntry e : extractIrEntries(page.html(), URI.create(page.url()), limit)) {
                if (out.size() >= limit) break;
                if (ownSmellRequired
                        && smell(e.url(), e.title(), CompanySiteCrawler.IR_HINTS) == 0) {
                    continue;
                }
                if (!isArchiveWorthy(e)) continue;
                if (sections.contains(CompanySiteCrawler.canonical(e.url()))) continue;
                if (!seenUrls.add(e.url())
                        || !seenTitles.add(e.title().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                out.add(e);
            }
        }
        if (!out.isEmpty()) {
            LOG.info("[FIRMENSITE] IR scout lifted {} entry(ies) from {} ({} page(s) walked)",
                    out.size(), crawl.home().getHost(), crawl.pages().size());
        }
        return out;
    }

    /** Convenience for callers that want the IR leg alone (smokes, tests). */
    List<IrEntry> irEntries(String website, int limit) {
        return irEntries(crawl(website), limit);
    }

    /**
     * The pages the walk itself opened - navigation into a section, never an
     * item OF one. "Alle Pressemitteilungen 2026" is a link to a listing, not
     * a headline, and "Finanzberichte" is not a report; both would otherwise
     * ride along as content once the whole site is harvested instead of a
     * single listing page.
     */
    private static Set<String> sectionUrls(CompanySiteCrawler.Crawl crawl) {
        Set<String> out = new HashSet<>();
        for (CompanySiteCrawler.Page p : crawl.pages()) {
            out.add(CompanySiteCrawler.canonical(p.url()));
        }
        return out;
    }

    /**
     * The walked pages that carry this mode's smell at all, most relevant
     * first. The filter is what keeps the modes apart: a report archive holds
     * no headlines and a newsroom holds no filings, and harvesting both from
     * both would file every quarterly PDF twice. The homepage scores 1/1 and
     * therefore always stays in - it is the honest last resort of both modes.
     */
    private static List<CompanySiteCrawler.Page> byScore(
            CompanySiteCrawler.Crawl crawl,
            java.util.function.ToIntFunction<CompanySiteCrawler.Page> score) {
        List<CompanySiteCrawler.Page> pages = new ArrayList<>();
        for (CompanySiteCrawler.Page p : crawl.pages()) {
            if (score.applyAsInt(p) > 0) pages.add(p);
        }
        pages.sort(Comparator.comparingInt(score).reversed()
                .thenComparingInt(CompanySiteCrawler.Page::depth));
        return pages;
    }

    /**
     * Report-shaped links of an IR listing: shorter titles than headlines
     * ("Q1 2026 Report" is a valid entry), but every entry must carry a
     * filing/call/calendar token; dates parsed from anchor text or href
     * (ISO, dotted German, bare year as the honest fallback).
     */
    static List<IrEntry> extractIrEntries(String html, URI base, int limit) {
        List<IrEntry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CompanySiteCrawler.Anchor a : CompanySiteCrawler.anchors(html)) {
            if (out.size() >= limit) break;
            String title = a.text();
            if (title.length() < 8 || title.length() > MAX_TITLE_CHARS) continue;
            if (!IR_ENTRY_TOKEN.matcher(title).find()) continue;
            String lower = title.toLowerCase(Locale.ROOT);
            String url = CompanySiteCrawler.resolve(base, a.href());
            if (url == null || !CompanySiteCrawler.sameHostFamily(base, url)) continue;
            if (isNavNoise(lower + " " + url.toLowerCase(Locale.ROOT))) continue;
            if (!seen.add(url) && !seen.add(lower)) continue;
            out.add(new IrEntry(title, irDate(title, url), url));
        }
        return out;
    }

    /** ISO date from title or href - full date first, dotted German, bare year last. */
    static String irDate(String title, String url) {
        String hay = title + " " + url;
        Matcher iso = IR_DATE_ISO.matcher(hay);
        if (iso.find()) return iso.group();
        Matcher dot = IR_DATE_DOTTED.matcher(hay);
        if (dot.find()) {
            return String.format(Locale.ROOT, "%s-%02d-%02d", dot.group(3),
                    Integer.parseInt(dot.group(2)), Integer.parseInt(dot.group(1)));
        }
        Matcher year = IR_YEAR.matcher(hay);
        return year.find() ? year.group(1) : null;
    }

    record Headline(String title, String url) {
    }

    /**
     * Headline-shaped links of a listing page: anchor text long enough to be a
     * title, on the company's host family, navigation noise skipped, deduped by
     * URL and by title (menus repeat links).
     */
    static List<Headline> extractHeadlines(String html, URI base) {
        List<Headline> out = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        for (CompanySiteCrawler.Anchor a : CompanySiteCrawler.anchors(html)) {
            String title = a.text();
            if (title.length() < MIN_TITLE_CHARS || title.length() > MAX_TITLE_CHARS) continue;
            String lower = title.toLowerCase(Locale.ROOT);
            String url = CompanySiteCrawler.resolve(base, a.href());
            if (url == null || !CompanySiteCrawler.sameHostFamily(base, url)) continue;
            if (isNavNoise(lower + " " + url.toLowerCase(Locale.ROOT))) continue;
            if (!seenUrls.add(url) || !seenTitles.add(lower)) continue;
            out.add(new Headline(title, url));
        }
        return out;
    }

    private static boolean isNavNoise(String lowerHaystack) {
        for (String bad : NAV_NOISE) {
            if (lowerHaystack.contains(bad)) return true;
        }
        return false;
    }

    /**
     * An IR entry is a DOCUMENT or a DATED event - nothing else belongs on the
     * archive shelf. Measured on nagarro.com 2026-08-03: without this the
     * section links themselves ("Financial Reports and Publications", undated,
     * pointing at a listing) were filed as reports.
     */
    private static boolean isArchiveWorthy(IrEntry e) {
        return e.dateIso() != null || !CompanySiteCrawler.crawlable(e.url());
    }

    /** A scheme-less profile URL still resolves ({@code www.sap.com} → https). */
    static URI normalize(String website) {
        return CompanySiteCrawler.normalize(website);
    }

    /** {@code news.sap.com} belongs to {@code www.sap.com} — compare the registrable tail. */
    static boolean sameHostFamily(URI base, String url) {
        return CompanySiteCrawler.sameHostFamily(base, url);
    }
}
