package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The KI-DD's first-party leg: the company's OWN website (Consorsbank delivers
 * the official URL with every profile) carries a press/IR section with the
 * announcements the wire services paraphrase — restructurings, quarterly
 * releases, regulatory statements. This scout fetches the homepage, locates the
 * press/news/IR section by link heuristics, and lifts that page's headline
 * links as news candidates (publisher = the company's own site). Everything
 * best-effort with tight budgets: two fetches, one host, a handful of items —
 * the finds still pass the relevance triage and the article digester like any
 * other source.
 */
final class CompanyPressScout {

    private static final Logger LOG = LoggerFactory.getLogger(CompanyPressScout.class);

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(12);
    private static final int MAX_HTML_CHARS = 400_000;
    /** Headline-shaped anchor text: long enough to be a title, short enough to be one. */
    private static final int MIN_TITLE_CHARS = 25;
    private static final int MAX_TITLE_CHARS = 180;

    private static final Pattern ANCHOR =
            Pattern.compile("(?is)<a\\s[^>]*?href=[\"']([^\"'#>]+)[\"'][^>]*>(.*?)</a>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WS = Pattern.compile("\\s+");

    /**
     * Press-section hints in priority order — href or anchor text, German and
     * English corporate conventions.
     */
    private static final String[] PRESS_HINTS = {
            "pressemitteilung", "press-release", "pressrelease", "newsroom",
            "presse", "press", "media-center", "mediacenter", "investor-relations",
            "investor", "mitteilungen", "aktuelles", "news",
    };

    /** Anchor texts that are navigation chrome, never headlines. */
    private static final String[] NAV_NOISE = {
            "cookie", "impressum", "datenschutz", "privacy", "kontakt", "contact",
            "login", "anmelden", "karriere", "career", "sitemap", "agb", "terms",
            "newsletter", "mehr erfahren", "read more", "weiterlesen", "startseite",
    };

    private final WebFetcher fetcher;
    private final String userAgent;

    CompanyPressScout(WebFetcher fetcher, String userAgent) {
        this.fetcher = fetcher;
        this.userAgent = userAgent;
    }

    /**
     * Lifts up to {@code limit} headline candidates from the company's press
     * section. Empty on any failure — a corporate site that resists two plain
     * fetches costs its leg, never the report.
     */
    List<RawNewsItem> pressItems(String website, int limit) {
        if (fetcher == null || website == null || website.isBlank()) return List.of();
        try {
            URI home = normalize(website);
            if (home == null) return List.of();
            String homeHtml = fetch(home.toString());
            if (homeHtml == null) return List.of();
            String pressUrl = bestPressLink(homeHtml, home);
            String listingHtml = homeHtml;
            URI listingBase = home;
            if (pressUrl != null && !pressUrl.equals(home.toString())) {
                String fetched = fetch(pressUrl);
                if (fetched != null) {
                    listingHtml = fetched;
                    listingBase = URI.create(pressUrl);
                }
            }
            List<RawNewsItem> out = new ArrayList<>();
            String publisher = home.getHost() + " (IR/Presse)";
            for (Headline h : extractHeadlines(listingHtml, listingBase)) {
                if (out.size() >= limit) break;
                out.add(new RawNewsItem(h.url(), h.title(), publisher, h.url(),
                        null, List.of()));
            }
            if (!out.isEmpty()) {
                LOG.info("[DEEPDIVE] press scout lifted {} item(s) from {} (listing {})",
                        out.size(), home.getHost(), pressUrl == null ? "homepage" : pressUrl);
            }
            return out;
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] press scout failed for '{}': {}", website, e.getMessage());
            return List.of();
        }
    }

    // ---- the IR ARCHIVE mode (first-party reports + financial calendar) ----

    /**
     * IR-archive hints in priority order - the financial-reports/calendar
     * section, NOT the press stream (its own hints above). German and English
     * corporate conventions.
     */
    private static final String[] IR_HINTS = {
            "finanzberichte", "quartalsbericht", "geschaeftsbericht", "financial-report",
            "quarterly-report", "quarterly-result", "financial-result", "finanzkalender",
            "financial-calendar", "ir-kalender", "publikationen", "publications",
            "berichte", "reports", "results", "investor-relations", "investor",
    };

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
     * Lifts up to {@code limit} report/calendar entries from the company's IR
     * archive - the FIRST-PARTY record of past quarters and coming dates the
     * press only paraphrases (user mandate 2026-07-16 "Ist, Soll UND
     * vergangener Stand"). Same tight budget as the press mode: two fetches,
     * one host family, empty on any failure.
     */
    List<IrEntry> irEntries(String website, int limit) {
        if (fetcher == null || website == null || website.isBlank()) return List.of();
        try {
            URI home = normalize(website);
            if (home == null) return List.of();
            String homeHtml = fetch(home.toString());
            if (homeHtml == null) return List.of();
            String irUrl = bestLink(homeHtml, home, IR_HINTS);
            String listingHtml = homeHtml;
            URI listingBase = home;
            if (irUrl != null && !irUrl.equals(home.toString())) {
                String fetched = fetch(irUrl);
                if (fetched != null) {
                    listingHtml = fetched;
                    listingBase = URI.create(irUrl);
                }
            }
            List<IrEntry> out = extractIrEntries(listingHtml, listingBase, limit);
            if (!out.isEmpty()) {
                LOG.info("[DEEPDIVE] IR scout lifted {} entry(ies) from {} (listing {})",
                        out.size(), home.getHost(), irUrl == null ? "homepage" : irUrl);
            }
            return out;
        } catch (Exception e) {
            LOG.debug("[DEEPDIVE] IR scout failed for '{}': {}", website, e.getMessage());
            return List.of();
        }
    }

    /**
     * Report-shaped links of an IR listing: shorter titles than headlines
     * ("Q1 2026 Report" is a valid entry), but every entry must carry a
     * filing/call/calendar token; dates parsed from anchor text or href
     * (ISO, dotted German, bare year as the honest fallback).
     */
    static List<IrEntry> extractIrEntries(String html, URI base, int limit) {
        List<IrEntry> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Matcher m = ANCHOR.matcher(html);
        while (m.find() && out.size() < limit) {
            String title = flatten(m.group(2));
            if (title.length() < 8 || title.length() > MAX_TITLE_CHARS) continue;
            if (!IR_ENTRY_TOKEN.matcher(title).find()) continue;
            String lower = title.toLowerCase(Locale.ROOT);
            boolean noise = false;
            for (String bad : NAV_NOISE) {
                if (lower.contains(bad)) {
                    noise = true;
                    break;
                }
            }
            if (noise) continue;
            String url = resolve(base, m.group(1).strip());
            if (url == null || !sameHostFamily(base, url)) continue;
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

    private String fetch(String url) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Accept", "text/html,application/xhtml+xml");
        WebResponse resp = fetcher.fetch(url, headers, FETCH_TIMEOUT);
        if (resp.status() != 200 || resp.body() == null) return null;
        String body = resp.body();
        return body.length() > MAX_HTML_CHARS ? body.substring(0, MAX_HTML_CHARS) : body;
    }

    /** A scheme-less profile URL still resolves ("www.sap.com" → https). */
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

    /**
     * The most press-like link of a page, resolved absolute — hint priority
     * decides (a "Pressemitteilungen" link beats a generic "News" one), the
     * link must stay on the company's own host family.
     */
    static String bestPressLink(String html, URI base) {
        return bestLink(html, base, PRESS_HINTS);
    }

    /** The best-ranked section link of a page for the given hint list. */
    static String bestLink(String html, URI base, String[] hints) {
        String bestUrl = null;
        int bestRank = Integer.MAX_VALUE;
        Matcher m = ANCHOR.matcher(html);
        while (m.find()) {
            String href = m.group(1).strip();
            String text = flatten(m.group(2)).toLowerCase(Locale.ROOT);
            String hrefLower = href.toLowerCase(Locale.ROOT);
            for (int rank = 0; rank < hints.length && rank < bestRank; rank++) {
                String hint = hints[rank];
                if (!hrefLower.contains(hint) && !text.contains(hint)) continue;
                String absolute = resolve(base, href);
                if (absolute == null || !sameHostFamily(base, absolute)) continue;
                bestRank = rank;
                bestUrl = absolute;
                break;
            }
        }
        return bestUrl;
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
        java.util.Set<String> seenUrls = new java.util.HashSet<>();
        java.util.Set<String> seenTitles = new java.util.HashSet<>();
        Matcher m = ANCHOR.matcher(html);
        while (m.find()) {
            String title = flatten(m.group(2));
            if (title.length() < MIN_TITLE_CHARS || title.length() > MAX_TITLE_CHARS) continue;
            String lower = title.toLowerCase(Locale.ROOT);
            boolean noise = false;
            for (String bad : NAV_NOISE) {
                if (lower.contains(bad)) {
                    noise = true;
                    break;
                }
            }
            if (noise) continue;
            String url = resolve(base, m.group(1).strip());
            if (url == null || !sameHostFamily(base, url)) continue;
            if (!seenUrls.add(url) || !seenTitles.add(lower)) continue;
            out.add(new Headline(title, url));
        }
        return out;
    }

    private static String flatten(String anchorInner) {
        return WS.matcher(TAG.matcher(anchorInner).replaceAll(" ")).replaceAll(" ").strip();
    }

    private static String resolve(URI base, String href) {
        try {
            if (href.startsWith("javascript:") || href.startsWith("mailto:")
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
