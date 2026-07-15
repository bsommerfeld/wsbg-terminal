package de.bsommerfeld.wsbg.terminal.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The German holiday calendar — two keyless legs (both live-probed 2026-07-14):
 *
 * <ul>
 *   <li><b>Public holidays</b> via date.nager.at:
 *       {@code /api/v3/PublicHolidays/<year>/DE}. Per holiday: {@code date}
 *       (ISO), {@code localName}, {@code global} (nationwide) plus
 *       {@code counties} ({@code DE-BW}…) for the state-specific ones.</li>
 *   <li><b>School-holiday windows</b> via ferien-api.de:
 *       {@code /api/v1/holidays/<BL>/<year>} — <b>PER STATE</b> (the
 *       country-wide path 400s), so the 16 state codes are iterated lazily and
 *       cached hard. Per window: {@code start}/{@code end} (ISO
 *       {@code YYYY-MM-DDThh:mm}), {@code name}, {@code stateCode}.</li>
 * </ul>
 *
 * <p>Both cache 7 days (holiday tables are near-static within a year). Every
 * fetch is best-effort empty.
 *
 * <p><b>Probe oddity (2026-07-14):</b> ferien-api.de rate-limits datacenter IPs
 * hard (HTTP 429 from this network); it answers normally from a residential
 * install. The parser + fixture are pinned to the documented schema; a 429
 * simply yields an empty (or the last cached) window list.
 */
@Singleton
public class HolidayCalendarClient {

    private static final Logger LOG = LoggerFactory.getLogger(HolidayCalendarClient.class);

    private static final String NAGER = "https://date.nager.at/api/v3/PublicHolidays/";
    private static final String FERIEN = "https://ferien-api.de/api/v1/holidays/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CACHE_TTL = Duration.ofDays(7);

    /** The 16 German state codes ferien-api.de keys on. */
    public static final List<String> STATE_CODES = List.of(
            "BW", "BY", "BE", "BB", "HB", "HH", "HE", "MV",
            "NI", "NW", "RP", "SL", "SN", "ST", "SH", "TH");

    /** One public holiday: date, German name, whether nationwide, and (if not) the state codes it applies to. */
    public record PublicHoliday(LocalDate date, String name, boolean nationwide,
            List<String> counties) {
    }

    /** One school-holiday window: state code, German name, start/end dates (end inclusive as ferien-api reports it). */
    public record SchoolHoliday(String stateCode, String name, LocalDate start, LocalDate end) {
    }

    private record Cached<T>(Instant at, List<T> items) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);
    private final Map<String, Cached<PublicHoliday>> publicCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<SchoolHoliday>> schoolCache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public HolidayCalendarClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam — both holiday APIs are wall-less. */
    @Inject
    public HolidayCalendarClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** All German public holidays for {@code year}, ascending by date. */
    public List<PublicHoliday> publicHolidays(int year) {
        String key = String.valueOf(year);
        Cached<PublicHoliday> hit = publicCache.get(key);
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.items();
        }
        List<PublicHoliday> parsed = fetchJson(NAGER + year + "/DE",
                HolidayCalendarClient::parsePublic);
        if (parsed != null) {
            publicCache.put(key, new Cached<>(Instant.now(), parsed));
            return parsed;
        }
        return hit != null ? hit.items() : List.of();
    }

    /** The next public holiday(s) from today, up to {@code limit}, across all states. */
    public List<PublicHoliday> upcomingPublic(int limit) {
        LocalDate today = LocalDate.now();
        List<PublicHoliday> all = new ArrayList<>(publicHolidays(today.getYear()));
        all.addAll(publicHolidays(today.getYear() + 1));
        List<PublicHoliday> out = new ArrayList<>();
        for (PublicHoliday h : all) {
            if (h.date() != null && !h.date().isBefore(today)) out.add(h);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** All school-holiday windows for one state in {@code year}. */
    public List<SchoolHoliday> schoolHolidays(String stateCode, int year) {
        String key = stateCode + ":" + year;
        Cached<SchoolHoliday> hit = schoolCache.get(key);
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.items();
        }
        List<SchoolHoliday> parsed = fetchJson(FERIEN + stateCode + "/" + year,
                HolidayCalendarClient::parseSchool);
        if (parsed != null) {
            schoolCache.put(key, new Cached<>(Instant.now(), parsed));
            return parsed;
        }
        return hit != null ? hit.items() : List.of();
    }

    /** The current or next school-holiday window for one state, or null when none is upcoming. */
    public SchoolHoliday currentOrNextSchoolHoliday(String stateCode) {
        LocalDate today = LocalDate.now();
        List<SchoolHoliday> all = new ArrayList<>(schoolHolidays(stateCode, today.getYear()));
        all.addAll(schoolHolidays(stateCode, today.getYear() + 1));
        SchoolHoliday best = null;
        for (SchoolHoliday h : all) {
            if (h.end() == null || h.end().isBefore(today)) continue; // fully past
            if (best == null || (h.start() != null && best.start() != null
                    && h.start().isBefore(best.start()))) {
                best = h;
            }
        }
        return best;
    }

    /** null return signals a fetch/parse failure (keep the stale cache); an empty list is a real answer. */
    private <T> List<T> fetchJson(String url, java.util.function.Function<String, List<T>> parser) {
        try {
            WebResponse resp = fetcher.fetch(url, Map.of("Accept", "application/json"), requestTimeout);
            if (resp != null && resp.status() == 200) {
                return parser.apply(resp.body());
            }
            LOG.debug("[Holiday] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Holiday] {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /** Package-private for tests: nager PublicHolidays JSON → holidays, ascending. */
    static List<PublicHoliday> parsePublic(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<PublicHoliday> out = new ArrayList<>();
        try {
            for (JsonNode n : JSON.readTree(json)) {
                LocalDate date = parseDate(n.path("date").asText(""));
                String name = n.path("localName").asText("").strip();
                if (date == null || name.isEmpty()) continue;
                List<String> counties = new ArrayList<>();
                if (n.path("counties").isArray()) {
                    for (JsonNode c : n.path("counties")) counties.add(c.asText());
                }
                out.add(new PublicHoliday(date, name,
                        n.path("global").asBoolean(false), List.copyOf(counties)));
            }
        } catch (Exception e) {
            LOG.debug("[Holiday] public parse failed: {}", e.getMessage());
        }
        out.sort((a, b) -> a.date().compareTo(b.date()));
        return out;
    }

    /** Package-private for tests: ferien-api holidays JSON → windows. */
    static List<SchoolHoliday> parseSchool(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<SchoolHoliday> out = new ArrayList<>();
        try {
            for (JsonNode n : JSON.readTree(json)) {
                LocalDate start = parseDate(n.path("start").asText(""));
                LocalDate end = parseDate(n.path("end").asText(""));
                if (start == null && end == null) continue;
                out.add(new SchoolHoliday(
                        n.path("stateCode").asText("").strip(),
                        n.path("name").asText("").strip(),
                        start, end));
            }
        } catch (Exception e) {
            LOG.debug("[Holiday] school parse failed: {}", e.getMessage());
        }
        return out;
    }

    /** Accepts a bare ISO date or an ISO date-time ({@code 2026-02-16T00:00}); the date part is taken. */
    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            int t = s.indexOf('T');
            return LocalDate.parse(t > 0 ? s.substring(0, t) : s);
        } catch (Exception e) {
            return null;
        }
    }
}
