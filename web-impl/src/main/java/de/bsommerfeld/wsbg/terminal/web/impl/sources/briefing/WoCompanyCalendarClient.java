package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * German company dates in the depth no other keyless source reaches
 * (live-verified 2026-08-03): {@code wallstreet-online.de/_rpc/json/news/
 * calendar/getCalendarTable} with {@code X-Requested-With: XMLHttpRequest}.
 * The page issues it as a POST, but the endpoint answers a plain GET with the
 * identical payload (byte-for-byte on the same day) — which keeps this client
 * on the house's GET-only transport seam.
 *
 * <p>What it carries that our other calendars do not: the street's EPS estimate
 * with its currency, and whether the company reports {@code vor Eröffnung} or
 * {@code nach Schluss}. Measured over five days (2,030 rows): US 1238/1166 with
 * an estimate, JP 269/152, CA 211/139, GB 50/18, IT 19/12, CH 17/8, NL 15/7,
 * FI 14/14 — and <b>DE 83/0</b>. So this is NOT the German earnings-estimate
 * source it looks like: for German issuers it delivers the date and the label
 * only (EQS has those, ISIN-exact). Its unique value is the estimate and the
 * bell slot for the non-US world, where EarningsWhispers does not reach.
 *
 * <p>Two limits, both measured: there is NO per-company page
 * ({@code /aktien/<slug>/termine} → 404) and no filter parameter, so this is a
 * day sweep, cached here to keep it one fetch per day and process; and the rows
 * carry NO ISIN — only a display name and its {@code /aktien/<slug>}. Callers
 * that need an ISIN must join on the name. Volume is real: 175 rows on
 * 2026-08-03, 455 on 2026-08-04, so nothing truncates at a fixed row count.
 */
@Singleton
public class WoCompanyCalendarClient {

    private static final Logger LOG = LoggerFactory.getLogger(WoCompanyCalendarClient.class);

    private static final String URL =
            "https://www.wallstreet-online.de/_rpc/json/news/calendar/getCalendarTable";
    private static final String REFERER = "https://www.wallstreet-online.de/unternehmenstermine";
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L;
    /** A sweep costs ~260 KB per day — never let a caller ask for the year. */
    private static final int MAX_SWEEP_DAYS = 21;

    // The markup is hand-spaced ("<div  class=") and the spacing is not a
    // contract — every structural pattern here tolerates any run of whitespace.
    private static final Pattern ROW = Pattern.compile(
            "<div\\s+class=\"wrapper body mainRow mainRow\\d+\".*?"
                    + "(?=<div\\s+class=\"wrapper body mainRow|<div\\s+class=\"mainwrapper\"|\\z)",
            Pattern.DOTALL);
    private static final Pattern STAMP = Pattern.compile("<span rel=\"([\\d-]{10} [\\d:]{8})\"");
    private static final Pattern COUNTRY = Pattern.compile("<img alt=\"([a-z]{2})\"");
    private static final Pattern EVENT_ID = Pattern.compile(
            "<span class=\"event_id[^\"]*\">([^<]+)</span>");
    private static final Pattern CELL = Pattern.compile(
            "class=\"[^\"]*\\b(instrument_display_name|symbol|consensus_plus|actual_plus"
                    + "|beforeAfterMarket)\\b[^\"]*\"[^>]*>(.*?)(?=<div\\s+class=\"|\\z)",
            Pattern.DOTALL);
    private static final Pattern SLUG = Pattern.compile("href=\"(/aktien/[^\"]+)\"");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final DateTimeFormatter WO_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /**
     * One company date. {@code epsEstimate} is the street's number for that
     * report (null where the calendar has none), {@code slot} the German
     * before/after-the-bell wording verbatim, {@code company} the display name
     * (NO ISIN — see the class note).
     */
    public record CompanyDate(long whenEpochSeconds, String eventId, String company,
            String slug, String country, String event,
            Double epsEstimate, String currency, String slot) {
    }

    /** Transport order of the old {@code @DirectFirst} seam: direct first, browser joker as fallback. */
    private static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);

    private final Map<LocalDate, List<CompanyDate>> cache =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<LocalDate, Long> cachedAtMs =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    /** Production: the shared house fetcher; {@code MODES} carries the old {@code @DirectFirst} order. */
    @Inject
    public WoCompanyCalendarClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Every company date on that day. Cached for six hours. Empty on any failure. */
    public List<CompanyDate> day(LocalDate day) {
        if (day == null) return List.of();
        Long at = cachedAtMs.get(day);
        List<CompanyDate> hit = cache.get(day);
        if (hit != null && at != null && System.currentTimeMillis() - at < CACHE_TTL_MS) {
            return hit;
        }
        long stamp = day.atStartOfDay(BERLIN).toEpochSecond();
        String url = URL + "?formtype=company&range=" + stamp
                + "&offset=0&speaker=all&audience=all&organisation=all";
        List<CompanyDate> parsed = List.of();
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of(
                            "X-Requested-With", "XMLHttpRequest",
                            "Referer", REFERER),
                    requestTimeout, MODES);
            if (resp != null && resp.status() == 200) {
                parsed = parse(resp.body());
            } else {
                LOG.debug("[WO-Kalender] {} answered status {}", day,
                        resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[WO-Kalender] fetch {} failed: {}", day, e.getMessage());
        }
        if (!parsed.isEmpty()) {
            cache.put(day, parsed);
            cachedAtMs.put(day, System.currentTimeMillis());
        }
        return parsed;
    }

    /**
     * The next {@code days} days from today, chronological. Capped at three
     * weeks — every extra day is another quarter-megabyte off the wire.
     */
    public List<CompanyDate> upcoming(int days) {
        LocalDate today = LocalDate.now(BERLIN);
        List<CompanyDate> out = new ArrayList<>();
        for (int i = 0; i < Math.min(Math.max(days, 0), MAX_SWEEP_DAYS); i++) {
            out.addAll(day(today.plusDays(i)));
        }
        return out;
    }

    /** Package-private for tests: the RPC envelope → dates, network-free, garbage-tolerant. */
    static List<CompanyDate> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        String html;
        try {
            JsonNode root = JSON.readTree(body);
            html = root.path("data").path("html").asText("");
        } catch (Exception e) {
            return List.of();
        }
        if (html.isBlank()) return List.of();
        List<CompanyDate> out = new ArrayList<>();
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            CompanyDate date = parseRow(rows.group());
            if (date != null) out.add(date);
        }
        return out;
    }

    private static CompanyDate parseRow(String row) {
        Matcher stamp = STAMP.matcher(row);
        if (!stamp.find()) return null;
        long when;
        try {
            when = LocalDateTime.parse(stamp.group(1), WO_STAMP).atZone(BERLIN).toEpochSecond();
        } catch (Exception e) {
            return null;
        }
        Map<String, String> cells = new LinkedHashMap<>();
        Matcher cell = CELL.matcher(row);
        while (cell.find()) cells.putIfAbsent(cell.group(1), cell.group(2));

        String companyCell = cells.getOrDefault("instrument_display_name", "");
        String company = text(companyCell);
        if (company.isEmpty()) return null;
        String eventCell = cells.getOrDefault("symbol", "");
        String event = text(eventCell.replaceAll("<span class=\"event_id[^\"]*\">[^<]*</span>", ""));

        Matcher slug = SLUG.matcher(companyCell);
        Matcher country = COUNTRY.matcher(row);
        Matcher eventId = EVENT_ID.matcher(row);

        String consensus = text(cells.getOrDefault("consensus_plus", ""))
                .replace("EPS Schätzung", "").trim();
        return new CompanyDate(when,
                eventId.find() ? eventId.group(1) : "",
                company,
                slug.find() ? slug.group(1) : "",
                country.find() ? country.group(1).toUpperCase(Locale.ROOT) : "",
                event,
                germanNumber(consensus),
                currencyOf(consensus),
                text(cells.getOrDefault("beforeAfterMarket", "")));
    }

    /** German decimals with a trailing currency glyph: "0,28$" → 0.28. */
    private static Double germanNumber(String cell) {
        Matcher m = Pattern.compile("(-?[\\d.]+,\\d+|-?\\d+)").matcher(cell);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1).replace(".", "").replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }

    private static String currencyOf(String cell) {
        if (cell.contains("$")) return "USD";
        if (cell.contains("€")) return "EUR";
        if (cell.contains("£")) return "GBP";
        return "";
    }

    private static String text(String html) {
        return TAG.matcher(html).replaceAll(" ")
                .replace("&nbsp;", " ").replace(" ", " ")
                .replaceAll("\\s{2,}", " ").trim();
    }
}
