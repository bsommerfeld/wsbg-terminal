package de.bsommerfeld.wsbg.terminal.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two rate-setters' own meeting calendars (live-verified 2026-07-13, both
 * keyless): the ECB Governing Council's monetary-policy meetings from the
 * inline {@code <dt>/<dd>} definition list on ecb.europa.eu (dates years
 * ahead; the DECISION day is the "Day 2" entry, the separate press-conference
 * rows are duplicates and skipped), and the FOMC from the Fed's whole-site
 * {@code calendar.json} (single JSON, UTF-8 BOM at the body start — parsed
 * BOM-tolerant; "FOMC Meeting" entries carry {@code month} + {@code days} =
 * the decision day). Both are near-static, so one fetch is cached for hours.
 *
 * <p>A third leg was added 2026-08-03 for the REST of the world (live-verified):
 * cbrates.com's meetings table — one fetch, ~20 central banks, decisions out to
 * mid-2027, from the RBA and the BoJ to Banxico, the SARB and the TCMB. It is a
 * hand-made table on a hobby site, so it is treated as the secondary it is: the
 * ECB and Fed rows it also carries are DROPPED in favour of the primary sources
 * above, and every failure mode is silent.
 *
 * <p>Two parsing traps in that table, both measured. The rows carry NO year —
 * it lives in bare year separator rows between the months, so a row is dated by
 * carrying the year forward and rolling it when the month goes backwards. And
 * the page is Latin-1 but does NOT declare a charset, so the transport decodes
 * it as UTF-8 and every non-ASCII letter arrives as U+FFFD, unrecoverably: the
 * byte is gone by the time we hold a String. Those characters are stripped from
 * the labels rather than shipped as boxes — today that costs exactly one umlaut
 * in the Turkish bank's name, while its abbreviation (which is what the reports
 * lead with) is ASCII and intact.
 */
@Singleton
public class CentralBankCalendarClient {

    private static final Logger LOG = LoggerFactory.getLogger(CentralBankCalendarClient.class);

    private static final String ECB_URL =
            "https://www.ecb.europa.eu/press/calendars/mgcgc/html/index.en.html";
    private static final String FED_URL = "https://www.federalreserve.gov/json/calendar.json";
    private static final String WORLD_URL = "https://www.cbrates.com/meetings.htm";
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern ECB_ENTRY =
            Pattern.compile("<dt[^>]*>\\s*(\\d{2}/\\d{2}/\\d{4})\\s*</dt>\\s*<dd[^>]*>(.*?)</dd>",
                    Pattern.DOTALL);
    private static final DateTimeFormatter ECB_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Pattern WORLD_ROW =
            Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern WORLD_CELL =
            Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern WORLD_DATE =
            Pattern.compile("^([A-Z][a-z]{2})\\s+(\\d{1,2})$");
    private static final Pattern WORLD_YEAR = Pattern.compile("^(20\\d{2})$");
    private static final Pattern WORLD_BANK =
            Pattern.compile("^([^:]{2,40}):\\s*(.+)$");
    /** The primary legs above own these two — the hobby table must not duplicate them. */
    private static final Pattern WORLD_SKIP =
            Pattern.compile("\\b(ECB|EZB|Federal Reserve|FOMC)\\b", Pattern.CASE_INSENSITIVE);

    /** One rate-decision date; {@code bank} is {@code "EZB"}, {@code "Fed"} or the world abbreviation. */
    public record CbMeeting(String bank, String title, LocalDate date) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    private volatile List<CbMeeting> cache = List.of();
    private volatile long cachedAtMs;

    /** Test/default: plain direct transport. */
    public CentralBankCalendarClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public CentralBankCalendarClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Upcoming rate decisions from {@code from} (inclusive), at most
     * {@code limitPerBank} per bank, chronological. Empty on total failure;
     * one bank failing costs only its half.
     */
    public List<CbMeeting> upcomingDecisions(LocalDate from, int limitPerBank) {
        // Sort BEFORE the per-bank cap: the Fed file ships newest-first, so an
        // unsorted cap would keep December and drop the meeting two weeks out
        // (live-observed 2026-07-13).
        List<CbMeeting> all = new ArrayList<>(allMeetings());
        all.sort(java.util.Comparator.comparing(CbMeeting::date));
        List<CbMeeting> out = new ArrayList<>();
        Map<String, Integer> perBank = new java.util.HashMap<>();
        for (CbMeeting m : all) {
            if (m.date().isBefore(from)) continue;
            int seen = perBank.getOrDefault(m.bank(), 0);
            if (seen >= limitPerBank) continue;
            perBank.put(m.bank(), seen + 1);
            out.add(m);
        }
        return out;
    }

    private List<CbMeeting> allMeetings() {
        long now = System.currentTimeMillis();
        if (!cache.isEmpty() && now - cachedAtMs < CACHE_TTL_MS) return cache;
        List<CbMeeting> out = new ArrayList<>();
        out.addAll(fetchLeg("ECB", ECB_URL, CentralBankCalendarClient::parseEcb));
        out.addAll(fetchLeg("Fed", FED_URL, CentralBankCalendarClient::parseFed));
        out.addAll(fetchLeg("World", WORLD_URL, CentralBankCalendarClient::parseWorld));
        if (!out.isEmpty()) {
            cache = List.copyOf(out);
            cachedAtMs = now;
        }
        return out;
    }

    /**
     * Package-private for tests: the cbrates meetings table → decisions.
     * Network-free, garbage-tolerant, ECB/Fed rows dropped (we hold better
     * sources for those two).
     */
    static List<CbMeeting> parseWorld(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<CbMeeting> out = new ArrayList<>();
        int year = -1;
        int lastMonth = 0;
        Matcher rows = WORLD_ROW.matcher(html);
        while (rows.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cell = WORLD_CELL.matcher(rows.group(1));
            while (cell.find()) {
                String text = cell.group(1).replaceAll("<[^>]+>", " ")
                        .replace("&nbsp;", " ").replace('\u00a0', ' ')
                        .replace("&amp;", "&").replaceAll("\\s{2,}", " ").trim();
                if (!text.isEmpty()) cells.add(text);
            }
            if (cells.isEmpty()) continue;
            if (cells.size() <= 2) {
                Matcher y = WORLD_YEAR.matcher(cells.get(0));
                if (y.matches()) {
                    year = Integer.parseInt(y.group(1));
                    lastMonth = 0;
                }
                // "Last update: 30 July 2026" seeds the year before any separator.
                if (year < 0 && cells.get(0).startsWith("Last update")) {
                    Matcher seed = Pattern.compile("(20\\d{2})").matcher(cells.get(0));
                    if (seed.find()) year = Integer.parseInt(seed.group(1));
                }
                continue;
            }
            CbMeeting meeting = parseWorldRow(cells, year, lastMonth);
            if (meeting == null) continue;
            // Months only ever move forward inside a year block.
            if (meeting.date().getMonthValue() < lastMonth) {
                year = meeting.date().getYear() + 1;
                meeting = new CbMeeting(meeting.bank(), meeting.title(),
                        meeting.date().withYear(year));
            }
            lastMonth = meeting.date().getMonthValue();
            if (year < 0) year = meeting.date().getYear();
            out.add(meeting);
        }
        return out;
    }

    private static CbMeeting parseWorldRow(List<String> cells, int year, int lastMonth) {
        String dateCell = null;
        String bankCell = null;
        for (String c : cells) {
            if (dateCell == null && WORLD_DATE.matcher(c).matches()) {
                dateCell = c;
            } else if (dateCell != null && bankCell == null && c.contains(":")) {
                bankCell = c;
            }
        }
        if (dateCell == null || bankCell == null) return null;
        // The label carries a navigation tail ("/ chart & historical Rates").
        int tail = bankCell.indexOf(" / ");
        if (tail > 0) bankCell = bankCell.substring(0, tail).trim();
        if (WORLD_SKIP.matcher(bankCell).find()) return null;
        Matcher bank = WORLD_BANK.matcher(bankCell);
        if (!bank.matches()) return null;
        String country = bank.group(1).trim();
        String name = bank.group(2).trim();
        String abbrev = name;
        Matcher paren = Pattern.compile("\\(([^)]{2,10})\\)").matcher(name);
        if (paren.find()) {
            abbrev = paren.group(1).trim();
            name = name.substring(0, paren.start()).trim();
        }
        Matcher d = WORLD_DATE.matcher(dateCell);
        if (!d.matches()) return null;
        int month;
        try {
            month = java.time.Month.valueOf(d.group(1).toUpperCase(Locale.ROOT)
                    + monthTail(d.group(1))).getValue();
        } catch (Exception e) {
            return null;
        }
        int useYear = year > 0 ? year : LocalDate.now().getYear();
        if (month < lastMonth) useYear++;
        try {
            return new CbMeeting(sanitize(abbrev), sanitize(name + " (" + country + ")"),
                    LocalDate.of(useYear, month, Integer.parseInt(d.group(2))));
        } catch (Exception e) {
            return null;
        }
    }

    /** Undecodable Latin-1 letters (see the class note) leave rather than print as boxes. */
    private static String sanitize(String label) {
        return label.replace("\uFFFD", "").replaceAll("\\s{2,}", " ").trim();
    }

    /** "Jul" → the enum name's remainder; the table abbreviates to three letters. */
    private static String monthTail(String abbrev) {
        return switch (abbrev.toLowerCase(Locale.ROOT)) {
            case "jan" -> "UARY";
            case "feb" -> "RUARY";
            case "mar" -> "CH";
            case "apr" -> "IL";
            case "may" -> "";
            case "jun" -> "E";
            case "jul" -> "Y";
            case "aug" -> "UST";
            case "sep" -> "TEMBER";
            case "oct" -> "OBER";
            case "nov" -> "EMBER";
            case "dec" -> "EMBER";
            default -> "";
        };
    }

    private List<CbMeeting> fetchLeg(String what, String url,
            java.util.function.Function<String, List<CbMeeting>> parser) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "*/*"), requestTimeout);
            if (resp != null && resp.status() == 200) return parser.apply(resp.body());
            LOG.debug("[CB-Kalender] {} answered status {}", what,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[CB-Kalender] {} failed: {}", what, e.getMessage());
        }
        return List.of();
    }

    /**
     * Package-private for tests: the ECB page's dt/dd pairs → decision days.
     * Kept: monetary-policy "Day 2" rows (the day the rate call lands, always
     * "followed by press conference"). Skipped: Day 1, non-monetary meetings,
     * the duplicate press-conference rows.
     */
    static List<CbMeeting> parseEcb(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<CbMeeting> out = new ArrayList<>();
        Matcher m = ECB_ENTRY.matcher(html);
        while (m.find()) {
            String text = m.group(2).replaceAll("<[^>]+>", " ");
            if (!text.contains("monetary policy meeting") || text.contains("non-monetary")) {
                continue;
            }
            if (!text.contains("press conference")) continue; // Day 1 has none
            try {
                out.add(new CbMeeting("EZB", "EZB-Zinsentscheid",
                        LocalDate.parse(m.group(1), ECB_DATE)));
            } catch (Exception e) {
                // ignore a malformed row, keep the rest
            }
        }
        return out;
    }

    /**
     * Package-private for tests: the Fed's calendar.json → FOMC decision days.
     * Only {@code type=FOMC} entries titled "FOMC Meeting" count ({@code days}
     * is the meeting's LAST day = decision day); Minutes/press rows are echoes.
     */
    static List<CbMeeting> parseFed(String body) {
        if (body == null || body.isBlank()) return List.of();
        if (body.startsWith("\uFEFF")) body = body.substring(1);
        List<CbMeeting> out = new ArrayList<>();
        try {
            JsonNode events = JSON.readTree(body).path("events");
            for (JsonNode e : events) {
                if (!"FOMC".equals(e.path("type").asText(""))) continue;
                String title = e.path("title").asText("").strip();
                if (!title.equalsIgnoreCase("FOMC Meeting")
                        && !title.equalsIgnoreCase("FOMC meeting")) {
                    continue;
                }
                LocalDate date = fedDate(e.path("month").asText(""), e.path("days").asText(""));
                if (date != null) out.add(new CbMeeting("Fed", "FOMC-Zinsentscheid", date));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /** "2026-09" + "16" (or "15-16") → the last named day of that month. */
    static LocalDate fedDate(String month, String days) {
        try {
            String[] ym = month.split("-");
            Matcher dm = Pattern.compile("(\\d{1,2})\\s*$").matcher(days.strip());
            if (ym.length != 2 || !dm.find()) return null;
            return LocalDate.of(Integer.parseInt(ym[0]), Integer.parseInt(ym[1]),
                    Integer.parseInt(dm.group(1)));
        } catch (Exception e) {
            return null;
        }
    }
}
