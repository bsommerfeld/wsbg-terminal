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
 */
@Singleton
public class CentralBankCalendarClient {

    private static final Logger LOG = LoggerFactory.getLogger(CentralBankCalendarClient.class);

    private static final String ECB_URL =
            "https://www.ecb.europa.eu/press/calendars/mgcgc/html/index.en.html";
    private static final String FED_URL = "https://www.federalreserve.gov/json/calendar.json";
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern ECB_ENTRY =
            Pattern.compile("<dt[^>]*>\\s*(\\d{2}/\\d{2}/\\d{4})\\s*</dt>\\s*<dd[^>]*>(.*?)</dd>",
                    Pattern.DOTALL);
    private static final DateTimeFormatter ECB_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** One rate-decision date; {@code bank} is {@code "EZB"} or {@code "Fed"}. */
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
        int ecb = 0, fed = 0;
        for (CbMeeting m : all) {
            if (m.date().isBefore(from)) continue;
            if ("EZB".equals(m.bank()) && ecb < limitPerBank) {
                out.add(m);
                ecb++;
            } else if ("Fed".equals(m.bank()) && fed < limitPerBank) {
                out.add(m);
                fed++;
            }
        }
        return out;
    }

    private List<CbMeeting> allMeetings() {
        long now = System.currentTimeMillis();
        if (!cache.isEmpty() && now - cachedAtMs < CACHE_TTL_MS) return cache;
        List<CbMeeting> out = new ArrayList<>();
        out.addAll(fetchLeg("ECB", ECB_URL, CentralBankCalendarClient::parseEcb));
        out.addAll(fetchLeg("Fed", FED_URL, CentralBankCalendarClient::parseFed));
        if (!out.isEmpty()) {
            cache = List.copyOf(out);
            cachedAtMs = now;
        }
        return out;
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
