package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When the cage is shut, and when it shuts early (live-verified 2026-08-03).
 * Three keyless sources, because no single one covers both the horizon and the
 * early closes:
 * <ul>
 *   <li>Xetra/FWB's own table — the German venue, <b>2026 through 2032</b>,
 *       full-day closures only.</li>
 *   <li>Eurex's indicative CSV — <b>2027 through 2036</b> across 15 country
 *       calendars ({@code EXCH}, {@code TUSA}, {@code TUK}, …), i.e. the long
 *       horizon and the non-German venues.</li>
 *   <li>Tradegate — current year only, but the ONLY German source that spells
 *       out the <b>half days</b> with their closing time ("trading from 7:30
 *       'til 2pm"), which is what actually surprises somebody holding a
 *       position on 30 December.</li>
 * </ul>
 *
 * <p>Deliberately NOT built on {@code date.nager.at} / {@code openholidaysapi}
 * even though both answer keylessly: those are CIVIL holidays and have no
 * notion of an early close, so they would quietly call a half day a normal day.
 *
 * <p>Source-data wart worth knowing: the Xetra table's 2032 New Year cell reads
 * {@code 01.01.2031}. The parser therefore takes every date from the cell text
 * itself and never infers a year from the column header — a wrong date is
 * dropped into the wrong year rather than silently invented for the right one.
 */
@Singleton
public class TradingCalendarClient {

    private static final Logger LOG = LoggerFactory.getLogger(TradingCalendarClient.class);

    private static final String XETRA =
            "https://www.cashmarket.deutsche-boerse.com/cash-de/Handel/handelskalender-und-zeiten";
    private static final String EUREX = "https://www.eurex.com/resource/blob/5098674/"
            + "ecf1bc8a3930b953bb90cc2b0a95b1e1/data/indicative-trading-calendars.csv";
    private static final String TRADEGATE =
            "https://www.tradegatebsx.com/handelskalender.php?lang=en";

    /** Holidays move once a year; a day of cache is generous. */
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L;

    private static final Pattern DE_DATE = Pattern.compile("(\\d{2})\\.(\\d{2})\\.(\\d{4})");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    // After tag-stripping the page reads "|01/01/2026| |New Year's Day: no
    // trading|" — pipes and spaces separate the date from its note.
    private static final Pattern TG_ROW = Pattern.compile(
            "(\\d{2}/\\d{2}/\\d{4})[|\\s]+([^|]{3,})");
    private static final Pattern TG_CLOSE = Pattern.compile(
            "'til\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter TG_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    /**
     * A day the venue does not trade normally. {@code closesAtHour} is null on
     * a full closure and carries the local closing hour on a half day.
     */
    public record TradingBreak(LocalDate date, String label, Integer closesAtHour) {

        public boolean closed() {
            return closesAtHour == null;
        }
    }

    /** Transport order of the old {@code @DirectFirst} seam: direct first, browser joker as fallback. */
    private static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);

    private volatile Map<LocalDate, TradingBreak> german = Map.of();
    private volatile Map<String, java.util.Set<LocalDate>> eurex = Map.of();
    private volatile long germanAtMs;
    private volatile long eurexAtMs;

    /** Production: the shared house fetcher; {@code MODES} carries the old {@code @DirectFirst} order. */
    @Inject
    public TradingCalendarClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The German trading breaks: Xetra's closures merged with Tradegate's list,
     * <b>Tradegate winning</b> where both know a date. That is deliberate and
     * it is not the same question — on Ascension Day and Whit Monday Xetra is
     * shut while Tradegate trades a short session, and Tradegate is the venue
     * this house actually prices and trades on. So a day marked with a closing
     * hour means "you can still trade, just not all day", even where the
     * reference exchange is dark. Empty on failure.
     */
    public Map<LocalDate, TradingBreak> germanBreaks() {
        long now = System.currentTimeMillis();
        if (!german.isEmpty() && now - germanAtMs < CACHE_TTL_MS) return german;
        Map<LocalDate, TradingBreak> merged = new TreeMap<>();
        for (TradingBreak b : parseXetra(get(XETRA))) merged.put(b.date(), b);
        // Tradegate knows the early closes Xetra's table does not carry, so it
        // overwrites — a half day is a different fact from a closed day.
        for (TradingBreak b : parseTradegate(get(TRADEGATE))) merged.put(b.date(), b);
        if (!merged.isEmpty()) {
            german = Map.copyOf(merged);
            germanAtMs = now;
        }
        return merged;
    }

    /** Eurex's country calendars, keyed by their code ({@code EXCH}, {@code TUSA}, …). */
    public Map<String, java.util.Set<LocalDate>> eurexCalendars() {
        long now = System.currentTimeMillis();
        if (!eurex.isEmpty() && now - eurexAtMs < CACHE_TTL_MS) return eurex;
        Map<String, java.util.Set<LocalDate>> parsed = parseEurex(get(EUREX));
        if (!parsed.isEmpty()) {
            eurex = parsed;
            eurexAtMs = now;
        }
        return parsed;
    }

    /** The break on that German day, or null when it trades normally. */
    public TradingBreak germanBreakOn(LocalDate day) {
        return day == null ? null : germanBreaks().get(day);
    }

    /**
     * The next day the German venue trades at all, weekends and closures
     * skipped. A half day counts as trading — it is open, just shorter.
     */
    public LocalDate nextGermanTradingDay(LocalDate from) {
        if (from == null) return null;
        Map<LocalDate, TradingBreak> breaks = germanBreaks();
        LocalDate day = from.plusDays(1);
        for (int guard = 0; guard < 14; guard++, day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY
                    || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            TradingBreak b = breaks.get(day);
            if (b == null || !b.closed()) return day;
        }
        return day;
    }

    private String get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of(
                            "Accept", "text/html,text/csv,*/*"),
                    requestTimeout, MODES);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("[Handelskalender] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Handelskalender] fetch {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /**
     * Package-private for tests: the Xetra matrix → full closures. The row's
     * first cell names the holiday; every date in the row is one occurrence of
     * it, whatever column it sits in.
     */
    static List<TradingBreak> parseXetra(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<TradingBreak> out = new ArrayList<>();
        Matcher rows = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL).matcher(html);
        while (rows.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cell = Pattern.compile("<t[dh][^>]*>(.*?)</t[dh]>", Pattern.DOTALL)
                    .matcher(rows.group(1));
            while (cell.find()) cells.add(clean(cell.group(1)));
            if (cells.size() < 2) continue;
            String label = cells.get(0);
            // The matrix carries footnote markers in their own rows ("@", "*").
            if (label.length() < 3 || label.chars().noneMatch(Character::isLetter)) continue;
            for (int i = 1; i < cells.size(); i++) {
                LocalDate date = germanDate(cells.get(i));
                if (date != null) out.add(new TradingBreak(date, label, null));
            }
        }
        return out;
    }

    /** Package-private for tests: Tradegate's list → closures AND half days. */
    static List<TradingBreak> parseTradegate(String html) {
        if (html == null || html.isBlank()) return List.of();
        String text = TAG.matcher(html).replaceAll("|").replaceAll("[ \\t]+", " ");
        List<TradingBreak> out = new ArrayList<>();
        Matcher rows = TG_ROW.matcher(text);
        while (rows.find()) {
            LocalDate date;
            try {
                date = LocalDate.parse(rows.group(1), TG_DATE);
            } catch (Exception e) {
                continue;
            }
            String note = rows.group(2).trim();
            // The page footer carries a stray date next to an "@" — a note
            // without a word in it is not a calendar entry.
            if (!note.matches("(?s).*\\p{L}{3,}.*")) continue;
            String label = note.contains(":") ? note.substring(0, note.indexOf(':')).trim() : note;
            out.add(new TradingBreak(date, label, closingHour(note)));
        }
        return out;
    }

    /** Package-private for tests: the Eurex CSV → one date set per calendar code. */
    static Map<String, java.util.Set<LocalDate>> parseEurex(String csv) {
        if (csv == null || csv.isBlank()) return Map.of();
        Map<String, java.util.Set<LocalDate>> out = new LinkedHashMap<>();
        DateTimeFormatter us = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ROOT);
        for (String line : csv.split("\\R")) {
            String[] parts = line.split(",");
            if (parts.length < 3 || parts[0].startsWith("Date")) continue;
            try {
                LocalDate date = LocalDate.parse(parts[0].trim(), us);
                out.computeIfAbsent(parts[2].trim(), k -> new TreeSet<>()).add(date);
            } catch (Exception e) {
                // A malformed row is one holiday, not a reason to lose the file.
            }
        }
        return out;
    }

    /** "trading from 7:30 'til 2pm" → 14. Null when the day is fully closed. */
    private static Integer closingHour(String note) {
        Matcher m = TG_CLOSE.matcher(note);
        if (!m.find()) return null;
        int hour = Integer.parseInt(m.group(1));
        boolean pm = m.group(3).toLowerCase(Locale.ROOT).startsWith("p");
        if (pm && hour < 12) hour += 12;
        if (!pm && hour == 12) hour = 0;
        return hour;
    }

    private static LocalDate germanDate(String cell) {
        Matcher m = DE_DATE.matcher(cell);
        if (!m.find()) return null;
        try {
            return LocalDate.of(Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        } catch (Exception e) {
            return null;
        }
    }

    private static String clean(String html) {
        return TAG.matcher(html).replaceAll(" ")
                .replace("&nbsp;", " ").replace(' ', ' ')
                .replaceAll("\\s{2,}", " ").trim();
    }
}
