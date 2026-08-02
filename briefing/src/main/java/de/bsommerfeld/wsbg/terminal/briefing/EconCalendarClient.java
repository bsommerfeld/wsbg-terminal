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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Macro calendar via FTMO's public gateway (live-verified 2026-08-02):
 * {@code gw2.ftmo.com/public-api/v1/economic-calendar?dateFrom=&dateTo=} — no
 * headers required, and a strict superset of the ForexFactory week file we read
 * before. FTMO builds on ForexFactory data but adds three things that file does
 * not carry: the released {@code actual}, an {@code instrument} scope naming
 * what the print actually hits ("USD + US Indices + XAUUSD + DXY", "Crude Oil")
 * and {@code restriction} — the prop-firm no-trading window around the event,
 * i.e. an outside relevance verdict nobody else publishes.
 *
 * <p>Only {@code dateFrom}/{@code dateTo} are honoured — {@code from}/{@code to}
 * are silently ignored and answer with today alone; overshooting 14 days answers
 * {@code 400 DATE_RANGE_VIOLATED}. The 14-day cap is not the real horizon
 * though: re-probed 2026-08-02, a 12-day-ahead request answered with events out
 * to the coming Sunday only, i.e. the published forward window is about a week
 * and rolls — asking wider is harmless, it simply returns what exists. The one
 * day of backfill is what earns the {@code actual}s (49 of 66 events filled
 * across the preceding week).
 *
 * <p>ForexFactory stays wired as the fallback leg. Note that host rate-limits
 * hard (429 after ~2 requests), so it is only ever touched when the gateway
 * came back empty.
 */
@Singleton
public class EconCalendarClient {

    private static final Logger LOG = LoggerFactory.getLogger(EconCalendarClient.class);

    private static final String FTMO = "https://gw2.ftmo.com/public-api/v1/economic-calendar";
    private static final String FF_THIS_WEEK = "https://nfs.faireconomy.media/ff_calendar_thisweek.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Yesterday, so a print released overnight is still on the board. */
    private static final int DAYS_BACK = 1;
    /** Inclusive with {@link #DAYS_BACK} this stays inside the gateway's 14-day cap. */
    private static final int DAYS_AHEAD = 12;

    /**
     * One scheduled macro event. {@code country} is the affected currency
     * ("USD", "EUR", …), {@code impact} the rating ("High" / "Medium" / "Low" /
     * "Holiday"); forecast/previous/actual verbatim strings (units vary, empty
     * while unreleased). {@code instrument} is FTMO's scope line — richer than
     * the currency where it exists, empty on the fallback leg. {@code restricted}
     * marks events prop firms forbid trading through.
     */
    public record EconEvent(String title, String country, long whenEpochSeconds,
            String impact, String forecast, String previous,
            String actual, String instrument, boolean restricted) {

        /** Fallback-shaped event: schedule only, no actual and no FTMO verdict. */
        public EconEvent(String title, String country, long whenEpochSeconds,
                String impact, String forecast, String previous) {
            this(title, country, whenEpochSeconds, impact, forecast, previous, "", "", false);
        }
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public EconCalendarClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public EconCalendarClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The dated docket around today (yesterday through +12 days), chronological.
     * Falls back to ForexFactory's this-week file when the gateway is silent,
     * and to an empty list when both are.
     */
    public List<EconEvent> docket() {
        LocalDate today = LocalDate.now();
        String url = FTMO + "?dateFrom=" + today.minusDays(DAYS_BACK)
                + "&dateTo=" + today.plusDays(DAYS_AHEAD);
        List<EconEvent> events = fetch(url, EconCalendarClient::parseFtmo);
        if (!events.isEmpty()) return events;
        LOG.debug("[EconCalendar] gateway empty - falling back to ForexFactory");
        return fetch(FF_THIS_WEEK, EconCalendarClient::parse);
    }

    private List<EconEvent> fetch(String url, java.util.function.Function<String, List<EconEvent>> parser) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return parser.apply(resp.body());
            LOG.debug("[EconCalendar] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[EconCalendar] fetch {} failed: {}", url, e.getMessage());
        }
        return List.of();
    }

    /** Package-private for tests: gateway JSON → events, network-free, garbage-tolerant. */
    static List<EconEvent> parseFtmo(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<EconEvent> out = new ArrayList<>();
        try {
            JsonNode items = JSON.readTree(body).path("items");
            if (!items.isArray()) return List.of();
            for (JsonNode n : items) {
                String title = text(n, "title");
                long when = epochOf(text(n, "date"));
                if (title.isEmpty() || when == 0L) continue;
                String instrument = text(n, "instrument");
                out.add(new EconEvent(title, currencyOf(instrument), when,
                        impactOf(text(n, "impact")), text(n, "forecast"), text(n, "previous"),
                        text(n, "actual"), instrument, n.path("restriction").asBoolean(false)));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /** Package-private for tests: ForexFactory week JSON → events, network-free, garbage-tolerant. */
    static List<EconEvent> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<EconEvent> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isArray()) return List.of();
            for (JsonNode n : root) {
                String title = text(n, "title");
                long when = epochOf(text(n, "date"));
                if (title.isEmpty() || when == 0L) continue;
                out.add(new EconEvent(title, text(n, "country"), when,
                        impactOf(text(n, "impact")), text(n, "forecast"), text(n, "previous")));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /**
     * The affected currency out of FTMO's scope line: it is a bare code often
     * enough ("NZD"), a lead code on a compound ("USD + US Indices + XAUUSD +
     * DXY"), and sometimes no currency at all ("Crude Oil") — empty then, the
     * scope line carries the meaning in that case.
     */
    private static String currencyOf(String instrument) {
        if (instrument.isEmpty()) return "";
        String head = instrument.split("[+,/ ]", 2)[0].trim();
        return head.length() == 3 && head.chars().allMatch(Character::isUpperCase) ? head : "";
    }

    /** "high" → "High": downstream compares against capitalised ratings. */
    private static String impactOf(String impact) {
        if (impact.isEmpty()) return "";
        return Character.toUpperCase(impact.charAt(0))
                + impact.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Epoch seconds, or 0 when the stamp is unparseable. */
    private static long epochOf(String date) {
        if (date.isEmpty()) return 0L;
        try {
            return OffsetDateTime.parse(date).toEpochSecond();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }
}
