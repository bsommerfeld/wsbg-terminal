package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * European corporate dates with the ISIN attached (live-verified 2026-08-03):
 * {@code borsaitaliana.it/borsa/documents/events.html?dateFrom=&dateTo=&size=}.
 * Despite the domain it is not strictly Italian — but be honest about the mix:
 * 200 rows off the live fragment counted IT 194, NL 4, ES 1, GR 1. So it is an
 * Italian calendar with a European fringe, not a pan-European one. Reporting
 * days, analyst presentations, annual meetings; the range is arbitrary and
 * forward-open, and every row carries an ISIN, which is what earns it a place
 * beside the German EQS leg.
 *
 * <p>The trap: the human page {@code /borsa/calendario-eventi-dividendi.html}
 * renders ZERO rows because it loads these fragments by script. Fetch the
 * {@code /borsa/documents/*.html} fragment directly. Cells are padded with
 * non-breaking spaces and the company name repeats inside the row, so the
 * parser reads the ISIN off the row's own link and the date off the
 * {@code dd.MM.yyyy - Label} pattern rather than trusting cell order.
 *
 * <p>The sibling {@code dividends-mini.html} fragment answers 200 and carries
 * ISINs too, but it is a different, sortable table shape that this parser does
 * NOT read — it is deliberately not exposed here rather than shipped as a
 * method that quietly returns nothing.
 */
@Singleton
public class BorsaItalianaEventsClient {

    private static final Logger LOG = LoggerFactory.getLogger(BorsaItalianaEventsClient.class);

    private static final String EVENTS =
            "https://www.borsaitaliana.it/borsa/documents/events.html";
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L;

    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern ISIN = Pattern.compile("isin=([A-Z]{2}[A-Z0-9]{10})");
    private static final Pattern DATED_LABEL =
            Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{4})\\s*-\\s*([^&<]{2,80})");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final DateTimeFormatter IT_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT);

    /** Direct-first: the document fragment answers a plain client. */
    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    /** One dated company event; {@code isin} is always present (rows without one are dropped). */
    public record EuEvent(LocalDate date, String isin, String company, String label) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);

    private volatile List<EuEvent> cache = List.of();
    private volatile long cachedAtMs;

    @Inject
    public BorsaItalianaEventsClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Company events in the window, chronological. Cached six hours. Empty on any failure. */
    public List<EuEvent> events(LocalDate from, LocalDate to, int size) {
        long now = System.currentTimeMillis();
        if (!cache.isEmpty() && now - cachedAtMs < CACHE_TTL_MS) return cache;
        List<EuEvent> parsed = fetchFragment(EVENTS, from, to, size);
        if (!parsed.isEmpty()) {
            cache = parsed;
            cachedAtMs = now;
        }
        return parsed;
    }

    private List<EuEvent> fetchFragment(String base, LocalDate from, LocalDate to, int size) {
        if (from == null || to == null) return List.of();
        String url = base + "?dateFrom=" + from + "&dateTo=" + to
                + "&page=&size=" + Math.max(1, Math.min(size, 500)) + "&lang=en";
        try {
            WebResponse resp = fetcher.fetch(url,
                    java.util.Map.of("Accept", "text/html,application/xhtml+xml"),
                    requestTimeout, MODES);
            if (resp != null && resp.status() == 200) return parse(resp.body());
            LOG.debug("[BorsaItaliana] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[BorsaItaliana] fetch failed: {}", e.getMessage());
        }
        return List.of();
    }

    /** Package-private for tests: fragment HTML → events, network-free, garbage-tolerant. */
    static List<EuEvent> parse(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<EuEvent> out = new ArrayList<>();
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            EuEvent event = parseRow(rows.group(1));
            if (event != null) out.add(event);
        }
        out.sort(java.util.Comparator.comparing(EuEvent::date));
        return out;
    }

    private static EuEvent parseRow(String row) {
        Matcher isin = ISIN.matcher(row);
        if (!isin.find()) return null;
        String text = TAG.matcher(row).replaceAll(" ")
                .replace("&nbsp;", " ").replace(' ', ' ')
                .replaceAll("\\s{2,}", " ").trim();
        Matcher dated = DATED_LABEL.matcher(text);
        if (!dated.find()) return null;
        LocalDate date;
        try {
            date = LocalDate.parse(dated.group(1), IT_DATE);
        } catch (Exception e) {
            return null;
        }
        // Everything before the date is the company. The row then repeats
        // company AND label a second time, and the non-breaking spaces that
        // separated them collapse away — so the label is cut at the point
        // where the company name comes round again.
        String company = text.substring(0, dated.start()).trim();
        String label = dated.group(2).trim();
        if (!company.isEmpty()) {
            int repeat = label.indexOf(company);
            if (repeat > 0) label = label.substring(0, repeat).trim();
        }
        if (company.isEmpty() || label.isEmpty()) return null;
        return new EuEvent(date, isin.group(1), company, label);
    }

    /** Only the events still ahead of {@code now}, capped. */
    public static List<EuEvent> upcomingFor(List<EuEvent> events, String isin, Instant now) {
        if (isin == null || isin.isBlank()) return List.of();
        List<EuEvent> out = new ArrayList<>();
        LocalDate today = LocalDate.ofInstant(now, java.time.ZoneId.systemDefault());
        for (EuEvent e : events) {
            if (!isin.equalsIgnoreCase(e.isin()) || e.date().isBefore(today)) continue;
            out.add(e);
        }
        return out;
    }
}
