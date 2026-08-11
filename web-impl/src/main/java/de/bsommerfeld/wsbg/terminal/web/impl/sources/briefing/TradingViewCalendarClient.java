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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TradingView's economic calendar (live-verified 2026-07-13, keyless): the one
 * probed macro calendar that carries ACTUAL released values as clean NUMBERS
 * beside forecast and previous — the ForexFactory week file is forecast-only,
 * so "wie ist es ausgegangen" can only be answered from here. Quirk pinned by
 * probe: the {@code Origin: https://www.tradingview.com} header is REQUIRED
 * (403 nginx without it). {@code importance} is -1 / 0 / 1 = low / medium /
 * high; values ride both display ({@code actual}) and raw fields — the raw
 * ones are consumed. Past ranges work, so the day's outcomes are fetchable
 * at freeze time regardless of when the events fired.
 */
@Singleton
public class TradingViewCalendarClient {

    private static final Logger LOG = LoggerFactory.getLogger(TradingViewCalendarClient.class);

    private static final String EVENTS_URL = "https://economic-calendar.tradingview.com/events";
    /** The rooms the cage actually trades against; ISO-2, EU = euro-area aggregates. */
    private static final String COUNTRIES = "US,DE,EU,GB,JP,CN";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One calendar event. {@code importance}: -1 low, 0 medium, 1 high (the
     * TradingView scale); {@code actual}/{@code forecast}/{@code previous} are
     * numbers in {@code unit} (may each be null — a speech has no figures, a
     * future release no actual); {@code source} is TradingView's attribution
     * of the releasing body ("Census Bureau"), may be empty.
     */
    public record TvEvent(String title, String indicator, String country, String currency,
            Instant when, int importance, Double actual, Double forecast, Double previous,
            String unit, String period, String source) {
    }

    /** Transport order of the old {@code @DirectFirst} seam: direct first, browser joker as fallback. */
    private static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Production: the shared house fetcher; {@code MODES} carries the old {@code @DirectFirst} order. */
    @Inject
    public TradingViewCalendarClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Events in {@code [from, to)} for the cage-relevant countries, chronological. Empty on failure. */
    public List<TvEvent> events(Instant from, Instant to) {
        try {
            String url = EVENTS_URL + "?from=" + from + "&to=" + to + "&countries=" + COUNTRIES;
            WebResponse resp = fetcher.fetch(url,
                    Map.of(
                            "Accept", "application/json",
                            "Origin", "https://www.tradingview.com"),
                    requestTimeout, MODES);
            if (resp != null && resp.status() == 200) return parse(resp.body());
            LOG.debug("[TradingView] calendar answered status {}",
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[TradingView] calendar fetch failed: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * Package-private for tests: reply JSON → events, network-free. Shape pinned
     * by probe: {@code {"status":"ok","result":[{title,country,date,importance,
     * actualRaw,forecastRaw,previousRaw,unit,…}]}}.
     */
    static List<TvEvent> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<TvEvent> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(body);
            if (!"ok".equals(root.path("status").asText())) return List.of();
            for (JsonNode e : root.path("result")) {
                String title = e.path("title").asText("");
                String date = e.path("date").asText("");
                if (title.isEmpty() || date.isEmpty()) continue;
                Instant when;
                try {
                    when = Instant.parse(date);
                } catch (Exception ex) {
                    continue;
                }
                out.add(new TvEvent(title, e.path("indicator").asText(""),
                        e.path("country").asText(""), e.path("currency").asText(""),
                        when, e.path("importance").asInt(-1),
                        number(e, "actualRaw"), number(e, "forecastRaw"),
                        number(e, "previousRaw"), e.path("unit").asText(""),
                        e.path("period").asText(""), e.path("source").asText("")));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    private static Double number(JsonNode e, String field) {
        JsonNode v = e.get(field);
        return v == null || !v.isNumber() ? null : v.asDouble();
    }
}
