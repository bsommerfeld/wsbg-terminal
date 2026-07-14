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
import java.util.concurrent.ConcurrentHashMap;

/**
 * EarningsWhispers' day calendar (live-verified 2026-07-13, keyless with a
 * browser UA + the calendar page as {@code Referer}): consensus EPS and
 * revenue estimates per scheduled US report, plus whether the company itself
 * confirmed the date — the "tomorrow's earnings, and what the street expects"
 * leg. US names only; an unofficial site-internal endpoint, so it is cached
 * generously per day and every consumer degrades gracefully when it vanishes.
 */
@Singleton
public class EarningsWhispersClient {

    private static final Logger LOG = LoggerFactory.getLogger(EarningsWhispersClient.class);

    private static final String CAL_URL = "https://www.earningswhispers.com/api/caldata/";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long CACHE_TTL_MS = 30 * 60 * 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One scheduled report with the street's numbers. {@code slot} is a stable
     * token ({@code pre-market} / {@code after-hours}, null when unknown);
     * {@code confirmed} = the company itself fixed the date (vs. an estimate).
     */
    public record EarningsEstimate(String ticker, String company, Double epsEstimate,
            Double revenueEstimate, String slot, boolean confirmed) {
    }

    private record CacheEntry(List<EarningsEstimate> estimates, long atMs) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);
    private final Map<LocalDate, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public EarningsWhispersClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public EarningsWhispersClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The street's estimates for reports scheduled on {@code day}. Empty on any failure. */
    public List<EarningsEstimate> estimatesOn(LocalDate day) {
        CacheEntry hit = cache.get(day);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.atMs() < CACHE_TTL_MS) return hit.estimates();
        try {
            WebResponse resp = fetcher.fetch(CAL_URL + DAY.format(day),
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/json",
                            "Referer", "https://www.earningswhispers.com/calendar"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<EarningsEstimate> out = parse(resp.body());
                if (!out.isEmpty()) cache.put(day, new CacheEntry(out, now));
                return out;
            }
            LOG.debug("[EarningsWhispers] {} answered status {}", day,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[EarningsWhispers] {} failed: {}", day, e.getMessage());
        }
        return List.of();
    }

    /**
     * Package-private for tests: reply JSON → estimates, network-free. Shape
     * pinned by probe: array of {@code {ticker,company,q1EstEPS,q1RevEst,
     * releaseTime,confirmDate,…}}; {@code releaseTime} 1 = before the open,
     * 2 = after the close.
     */
    static List<EarningsEstimate> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<EarningsEstimate> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isArray()) return List.of();
            for (JsonNode r : root) {
                String ticker = r.path("ticker").asText("");
                if (ticker.isEmpty()) continue;
                out.add(new EarningsEstimate(ticker, r.path("company").asText(""),
                        number(r, "q1EstEPS"), number(r, "q1RevEst"),
                        slot(r.path("releaseTime").asInt(0)),
                        !r.path("confirmDate").asText("").isBlank()));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    private static String slot(int releaseTime) {
        return switch (releaseTime) {
            case 1 -> "pre-market";
            case 2 -> "after-hours";
            default -> null;
        };
    }

    private static Double number(JsonNode r, String field) {
        JsonNode v = r.get(field);
        return v == null || !v.isNumber() ? null : v.asDouble();
    }
}
