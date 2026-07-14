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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Macro calendar via ForexFactory's keyless week file (live-verified
 * 2026-07-13): {@code nfs.faireconomy.media/ff_calendar_thisweek.json} —
 * title, currency, ISO date, impact rating, forecast/previous. Deliberately
 * consumed as "what was/is scheduled" (the file carries NO actuals —
 * Destatis/ifo RSS deliver the German actuals, TradingView the numeric
 * outcomes); for the evening report that means today's docket and tomorrow's
 * outlook. ONLY the this-week file exists: {@code _nextweek}/{@code _today}/
 * {@code _tomorrow} answer 404 since mid-2026 (re-probed 2026-07-13), so the
 * former {@code nextWeek()} leg was removed — the cross-week tomorrow gap is
 * TradingView's job now.
 */
@Singleton
public class EconCalendarClient {

    private static final Logger LOG = LoggerFactory.getLogger(EconCalendarClient.class);

    private static final String THIS_WEEK = "https://nfs.faireconomy.media/ff_calendar_thisweek.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One scheduled macro event. {@code country} is the affected currency
     * ("USD", "EUR", …), {@code impact} ForexFactory's rating ("High" /
     * "Medium" / "Low"); forecast/previous verbatim strings (units vary).
     */
    public record EconEvent(String title, String country, long whenEpochSeconds,
            String impact, String forecast, String previous) {
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

    /** This week's scheduled events, chronological. Empty on any failure. */
    public List<EconEvent> thisWeek() {
        return fetchWeek(THIS_WEEK);
    }

    private List<EconEvent> fetchWeek(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return parse(resp.body());
            LOG.debug("[EconCalendar] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[EconCalendar] fetch {} failed: {}", url, e.getMessage());
        }
        return List.of();
    }

    /** Package-private for tests: week JSON → events, network-free, garbage-tolerant. */
    static List<EconEvent> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<EconEvent> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isArray()) return List.of();
            for (JsonNode n : root) {
                String title = text(n, "title");
                String date = text(n, "date");
                if (title.isEmpty() || date.isEmpty()) continue;
                long when;
                try {
                    when = OffsetDateTime.parse(date).toEpochSecond();
                } catch (Exception e) {
                    continue;
                }
                out.add(new EconEvent(title, text(n, "country"), when,
                        text(n, "impact"), text(n, "forecast"), text(n, "previous")));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }
}
