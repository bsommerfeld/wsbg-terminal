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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NASDAQ's earnings calendar (live-verified 2026-07-13, keyless — surprise of
 * the probe round: {@code api.nasdaq.com} answers plain HTTP as long as the
 * request carries browser-shaped {@code Accept}/{@code Origin}/{@code Referer}
 * headers; WITHOUT them the Akamai edge simply never answers, so the timeout
 * matters more than any status code). Feeds the "tomorrow reports" outlook
 * section: symbol, company, pre/after-market slot, EPS forecast.
 */
@Singleton
public class NasdaqCalendarClient {

    private static final Logger LOG = LoggerFactory.getLogger(NasdaqCalendarClient.class);

    private static final String EARNINGS_URL = "https://api.nasdaq.com/api/calendar/earnings?date=";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One scheduled earnings report. {@code slot} is NASDAQ's token
     * ({@code time-pre-market} / {@code time-after-hours} / {@code time-not-supplied});
     * {@code epsForecast} verbatim ("$5.52"), empty when no estimate exists.
     */
    public record EarningsEntry(String symbol, String name, String slot, String epsForecast,
            String marketCap) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public NasdaqCalendarClient() {
        this(new DirectWebFetcher());
    }

    /** Production: direct-first chain; the joker can still rescue a grown wall. */
    @Inject
    public NasdaqCalendarClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Earnings scheduled for {@code date}, biggest names first as NASDAQ ships them. */
    public List<EarningsEntry> earningsOn(LocalDate date, int limit) {
        try {
            WebResponse resp = fetcher.fetch(EARNINGS_URL + date,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/json",
                            "Origin", "https://www.nasdaq.com",
                            "Referer", "https://www.nasdaq.com/"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<EarningsEntry> out = parse(resp.body());
                return out.size() > limit ? out.subList(0, limit) : out;
            }
            LOG.debug("[NASDAQ] earnings {} answered status {}", date,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[NASDAQ] earnings {} failed: {}", date, e.getMessage());
        }
        return List.of();
    }

    /**
     * Package-private for tests: reply JSON → entries, network-free. Shape pinned
     * by probe: {@code data.rows[]} with symbol/name/time/epsForecast/marketCap.
     */
    static List<EarningsEntry> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<EarningsEntry> out = new ArrayList<>();
        try {
            JsonNode rows = JSON.readTree(body).path("data").path("rows");
            if (!rows.isArray()) return List.of();
            for (JsonNode r : rows) {
                String symbol = r.path("symbol").asText("");
                if (symbol.isEmpty()) continue;
                out.add(new EarningsEntry(symbol, r.path("name").asText(""),
                        r.path("time").asText(""), r.path("epsForecast").asText(""),
                        r.path("marketCap").asText("")));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }
}
