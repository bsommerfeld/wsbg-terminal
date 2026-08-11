package de.bsommerfeld.wsbg.terminal.wienerboerse;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import de.bsommerfeld.wsbg.terminal.wienerboerse.WienerBoerseHistory.WienerBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Vienna Stock Exchange website's own historical-data CSV export
 * ({@code wienerborse.at/.../historische-daten/?c<id>[DOWNLOAD]=csv}, probed
 * 2026-08-05): the home venue's daily OHLC for AT listings plus Geld- and
 * Stueckumsatz, roughly 26 years deep - no JSON API, but an open keyless
 * download behind every instrument page.
 *
 * <p>Two moving parts have to be read off the page first, and the site itself
 * answers both: (1) the canonical page path - a GET on
 * {@code /aktien-prime-market/x-<ISIN>/historische-daten/} with ANY slug
 * 302-redirects to the true {@code <slug>-<ISIN>} page whatever the segment,
 * so the ISIN alone finds the instrument; (2) the TYPO3 module id
 * ({@code c48840}) that keys the download query - it can vary per page, so it
 * is scraped from the page's own CSV link (the same regex also yields the
 * canonical path, since the redirect's final URL is not surfaced by the
 * fetcher). Both are cached per ISIN. The redirect DROPS query strings, which
 * is why the CSV can only be pulled from the canonical path.
 *
 * <p>The CSV itself is German through and through: semicolon-separated,
 * double-quoted fields, BOM, {@code dd.MM.yyyy} dates, comma decimals and dot
 * thousands - parsed defensively, broken lines skipped.
 *
 * <p>AT ISINs only: anything else is a network-free no-op - Vienna is the
 * home tape for Austrian paper, not a lookup service for the world.
 */
@Singleton
public class WienerBoerseClient {

    private static final Logger LOG = LoggerFactory.getLogger(WienerBoerseClient.class);

    static final String BASE = "https://www.wienerborse.at";
    /** Any slug redirects to the canonical page - the ISIN does the finding. */
    static final String DISCOVERY_URL = BASE + "/aktien-prime-market/x-%s/historische-daten/";

    /** Austrian ISIN - the only paper this venue is home tape for. */
    private static final Pattern AT_ISIN = Pattern.compile("AT[0-9A-Z]{9}[0-9]");
    /** The page's own CSV link: canonical path AND module id in one read. */
    private static final Pattern DOWNLOAD_HREF = Pattern.compile(
            "href=\"(/[^\"]*?-(AT[0-9A-Z]{9}[0-9])/historische-daten/)\\?c(\\d{4,6})(?:%5B|\\[)DOWNLOAD");
    /** Fallback path source when the CSV link moved: the canonical tag. */
    private static final Pattern CANONICAL_HREF = Pattern.compile(
            "rel=\"canonical\"\\s+href=\"https?://[^\"/]+(/[^\"]*?/historische-daten/)\"");
    /** Fallback module id when none is scrapable (probed 2026-08-05). */
    static final String FALLBACK_MODULE_ID = "48840";

    private static final DateTimeFormatter DE_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    /** A decade of daily bars is a few hundred KB - give the export room. */
    private final Duration requestTimeout = Duration.ofSeconds(30);
    /** ISIN → resolved historische-daten path + module id, found once per run. */
    private final Map<String, PageKey> pageCache = new ConcurrentHashMap<>();

    private record PageKey(String path, String moduleId) {}

    /** Test/default: plain direct transport. */
    public WienerBoerseClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public WienerBoerseClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The home-venue daily history for one AT ISIN, from the given date to
     * today.
     *
     * @return the history, or empty when the ISIN is not AT-shaped, unknown
     *         to the venue or the export failed - never {@code null}
     */
    public Optional<WienerBoerseHistory> history(String isin, LocalDate from) {
        if (isin == null || from == null) return Optional.empty();
        String id = isin.trim().toUpperCase(Locale.ROOT);
        if (!AT_ISIN.matcher(id).matches()) return Optional.empty();
        try {
            PageKey key = pageCache.get(id);
            if (key == null) {
                key = discover(id);
                if (key == null) return Optional.empty();
                pageCache.put(id, key);
            }
            String url = key.path()
                    + "?c" + key.moduleId() + "%5BDOWNLOAD%5D=csv"
                    + "&c" + key.moduleId() + "%5BDATETIME_TZ_START_RANGE%5D=" + DE_DATE.format(from)
                    + "&c" + key.moduleId() + "%5BDATETIME_TZ_END_RANGE%5D=" + DE_DATE.format(LocalDate.now());
            WebResponse resp = get(BASE + url);
            if (resp == null || resp.status() != 200) {
                LOG.debug("[WienerBoerse] CSV status {} for {}",
                        resp == null ? "null" : resp.status(), id);
                return Optional.empty();
            }
            List<WienerBar> bars = parseCsv(resp.body());
            if (bars.isEmpty()) return Optional.empty();
            LOG.info("[WienerBoerse] {} → {} bars {}..{} via {}", id, bars.size(),
                    bars.get(0).date(), bars.get(bars.size() - 1).date(), key.path());
            return Optional.of(new WienerBoerseHistory(id, key.path(), List.copyOf(bars)));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[WienerBoerse] history for {} failed: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Lets the site's own redirect resolve the slug, then reads path and
     * module id off the landed page's CSV link.
     */
    private PageKey discover(String isin) throws Exception {
        WebResponse resp = get(String.format(DISCOVERY_URL, isin));
        if (resp == null || resp.status() != 200) {
            LOG.debug("[WienerBoerse] discovery status {} for {}",
                    resp == null ? "null" : resp.status(), isin);
            return null;
        }
        PageKey key = scrapePageKey(resp.body());
        if (key == null) LOG.debug("[WienerBoerse] no CSV link on page for {}", isin);
        return key;
    }

    private WebResponse get(String url) throws Exception {
        return fetcher.fetch(url,
                Map.of("User-Agent", userAgent,
                        "Accept", "text/html,application/xhtml+xml,text/csv;q=0.9,*/*;q=0.8"),
                requestTimeout);
    }

    /**
     * The instrument page's CSV link carries both the canonical path and the
     * module id; the canonical tag plus the fallback id covers a page whose
     * link moved. Package-private for tests.
     */
    static Optional<String[]> pageKey(String html) {
        if (html == null || html.isBlank()) return Optional.empty();
        Matcher m = DOWNLOAD_HREF.matcher(html);
        if (m.find()) return Optional.of(new String[]{m.group(1), m.group(3)});
        Matcher c = CANONICAL_HREF.matcher(html);
        if (c.find()) return Optional.of(new String[]{c.group(1), FALLBACK_MODULE_ID});
        return Optional.empty();
    }

    private static PageKey scrapePageKey(String html) {
        return pageKey(html).map(k -> new PageKey(k[0], k[1])).orElse(null);
    }

    /**
     * Parses the German CSV export ({@code Datum;Eroeffnungspreis;Tageshoch;
     * Tagestief;Schlusspreis;Diff.%;Geldumsatz;Stueckumsatz}) into bars,
     * oldest first, broken lines skipped. Package-private for tests.
     */
    static List<WienerBar> parseCsv(String csv) {
        List<WienerBar> bars = new ArrayList<>();
        if (csv == null || csv.isBlank()) return bars;
        for (String line : csv.split("\r?\n")) {
            String[] f = line.split(";", -1);
            if (f.length < 5) continue;
            LocalDate date = barDate(unquote(f[0]));
            if (date == null) continue; // header, blank or broken line
            double close = deNumber(field(f, 4));
            if (!Double.isFinite(close) || close <= 0) continue;
            bars.add(new WienerBar(date,
                    deNumber(field(f, 1)), deNumber(field(f, 2)), deNumber(field(f, 3)),
                    close, deNumber(field(f, 7)), deNumber(field(f, 6))));
        }
        bars.sort((a, b) -> a.date().compareTo(b.date()));
        return bars;
    }

    private static String field(String[] f, int i) {
        return i < f.length ? unquote(f[i]) : "";
    }

    private static String unquote(String s) {
        String t = s.trim();
        // The export opens with a BOM ahead of the header cell.
        if (!t.isEmpty() && t.charAt(0) == '\uFEFF') t = t.substring(1).trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    private static LocalDate barDate(String s) {
        try {
            return LocalDate.parse(s, DE_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    /** A German-formatted decimal (comma decimal, dot thousands), {@code NaN} when absent/unreadable. */
    private static double deNumber(String s) {
        if (s == null || s.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(s.replace(".", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
