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
import java.util.Map;
import java.util.Optional;

/**
 * CBOE daily options market statistics — the put/call ratios, straight from
 * their public CDN as JSON (live-verified 2026-07-13, keyless; only the HTML
 * page around it is Cloudflare-walled, the CDN is open). The equity put/call
 * ratio is the classic crowd-positioning gauge: 0.55 means the crowd is
 * loading calls. The file is keyed by trading date and simply absent on
 * weekends/holidays, so the client walks back a few days to the last session.
 */
@Singleton
public class CboePutCallClient {

    private static final Logger LOG = LoggerFactory.getLogger(CboePutCallClient.class);

    private static final String BASE =
            "https://cdn.cboe.com/data/us/options/market_statistics/daily/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LOOKBACK_DAYS = 5;

    /** The day's headline ratios; NaN where the file lacked one. */
    public record PutCallRatios(String dateIso, double total, double equity, double index,
            double vix, double spx) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public CboePutCallClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public CboePutCallClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The latest available trading day's ratios, walking back from {@code today}. */
    public Optional<PutCallRatios> latest(LocalDate today) {
        for (int back = 0; back <= MAX_LOOKBACK_DAYS; back++) {
            LocalDate day = today.minusDays(back);
            try {
                WebResponse resp = fetcher.fetch(BASE + day + "_daily_options",
                        Map.of("User-Agent", userAgent, "Accept", "application/json"),
                        requestTimeout);
                if (resp != null && resp.status() == 200) {
                    Optional<PutCallRatios> parsed = parse(resp.body(), day.toString());
                    if (parsed.isPresent()) return parsed;
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
                LOG.debug("[CBOE] fetch {} failed: {}", day, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * Package-private for tests: file JSON → ratios, network-free. Shape pinned
     * by probe: {@code {"ratios":[{"name":"TOTAL PUT/CALL RATIO","value":"0.81"},…]}}.
     */
    static Optional<PutCallRatios> parse(String body, String dateIso) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode ratios = JSON.readTree(body).get("ratios");
            if (ratios == null || !ratios.isArray()) return Optional.empty();
            double total = Double.NaN, equity = Double.NaN, index = Double.NaN,
                    vix = Double.NaN, spx = Double.NaN;
            for (JsonNode r : ratios) {
                String name = r.path("name").asText("");
                double value;
                try {
                    value = Double.parseDouble(r.path("value").asText("").trim());
                } catch (Exception e) {
                    continue;
                }
                switch (name) {
                    case "TOTAL PUT/CALL RATIO" -> total = value;
                    case "EQUITY PUT/CALL RATIO" -> equity = value;
                    case "INDEX PUT/CALL RATIO" -> index = value;
                    case "CBOE VOLATILITY INDEX (VIX) PUT/CALL RATIO" -> vix = value;
                    case "SPX + SPXW PUT/CALL RATIO" -> spx = value;
                    default -> { /* the long tail of product ratios — not the briefing's business */ }
                }
            }
            if (Double.isNaN(total) && Double.isNaN(equity)) return Optional.empty();
            return Optional.of(new PutCallRatios(dateIso, total, equity, index, vix, spx));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
