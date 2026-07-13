package de.bsommerfeld.wsbg.terminal.feargreed;

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

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches CNN's Fear &amp; Greed Index from its undocumented-but-stable dataviz
 * JSON endpoint ({@code https://production.dataviz.cnn.io/index/fearandgreed/graphdata}),
 * the same one dozens of trackers rely on.
 *
 * <p><b>Bot wall.</b> CNN answers bare HTTP clients with {@code 418 I'm a teapot}.
 * In production this rides the shared {@link WebFetcher} chain (browser joker →
 * direct), so the request carries a real browser TLS fingerprint + cookies and
 * clears the wall — exactly like the Reddit/Yahoo calls. A browser-shaped
 * User-Agent is sent regardless.
 *
 * <p>Each call returns {@link Optional#empty()} on any failure (network, non-200,
 * parse, out-of-band score) and logs at WARN; the monitor keeps the last good reading.
 */
@Singleton
public class FearGreedClient {

    private static final Logger LOG = LoggerFactory.getLogger(FearGreedClient.class);

    static final String URL = "https://production.dataviz.cnn.io/index/fearandgreed/graphdata";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final java.time.Duration requestTimeout = java.time.Duration.ofSeconds(10);

    /** Test/default: plain direct transport (will hit the 418 wall — joker needed live). */
    public FearGreedClient() {
        this(new DirectWebFetcher());
    }

    /** Production: rides the shared browser-joker {@link WebFetcher} chain. */
    @Inject
    public FearGreedClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Fetches the current index, or empty on any failure. */
    public Optional<FearGreedIndex> fetch() {
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp.status() != 200) {
                LOG.warn("Fear&Greed returned HTTP {}", resp.status());
                return Optional.empty();
            }
            return parse(resp.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("Fear&Greed request failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The seven sub-indicator blocks CNN folds into the composite, in CNN's own
     * display order. The response also carries {@code market_momentum_sp125} and
     * {@code market_volatility_vix_50}, which duplicate their siblings' scores —
     * deliberately skipped.
     */
    private static final java.util.List<String> COMPONENT_KEYS = java.util.List.of(
            "market_momentum_sp500", "stock_price_strength", "stock_price_breadth",
            "put_call_options", "market_volatility_vix", "junk_bond_demand",
            "safe_haven_demand");

    /**
     * Response shape:
     * <pre>{@code
     * { "fear_and_greed": { "score": 63.27, "rating": "greed", "timestamp": "...",
     *                       "previous_close": 60.1, "previous_1_week": 55, ... },
     *   "fear_and_greed_historical": { "data": [ {"x": 1719000000000, "y": 55.2}, ... ] },
     *   "market_momentum_sp500": { "score": 61.2, "rating": "greed", "data": [...] }, ... }
     * }</pre>
     */
    Optional<FearGreedIndex> parse(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode fg = root.path("fear_and_greed");
            JsonNode score = fg.path("score");
            if (!score.isNumber()) {
                LOG.warn("Fear&Greed: score missing or non-numeric");
                return Optional.empty();
            }
            double s = score.asDouble();
            if (!Double.isFinite(s) || s < 0 || s > 100) {
                LOG.warn("Fear&Greed: score {} outside [0,100]", s);
                return Optional.empty();
            }
            String rating = fg.path("rating").asText("");
            double prevClose = fg.path("previous_close").isNumber()
                    ? fg.path("previous_close").asDouble() : s;
            return Optional.of(new FearGreedIndex(s, rating, prevClose,
                    scoreOrNull(fg.path("previous_1_week")),
                    scoreOrNull(fg.path("previous_1_month")),
                    scoreOrNull(fg.path("previous_1_year")),
                    Instant.now(),
                    parseHistory(root.path("fear_and_greed_historical").path("data")),
                    parseComponents(root)));
        } catch (Exception e) {
            LOG.warn("Fear&Greed parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** A 0–100 score field, or {@code null} when absent/out-of-band (best-effort extras). */
    private static Double scoreOrNull(JsonNode n) {
        if (n == null || !n.isNumber()) return null;
        double v = n.asDouble();
        return Double.isFinite(v) && v >= 0 && v <= 100 ? v : null;
    }

    /**
     * The seven sub-indicator blocks (score + rating each). Best-effort: a block
     * that's missing or malformed is skipped, never fails the composite reading.
     */
    private static java.util.List<FearGreedIndex.Component> parseComponents(JsonNode root) {
        java.util.List<FearGreedIndex.Component> out = new java.util.ArrayList<>(COMPONENT_KEYS.size());
        for (String key : COMPONENT_KEYS) {
            JsonNode block = root.path(key);
            Double v = scoreOrNull(block.path("score"));
            if (v == null) continue;
            out.add(new FearGreedIndex.Component(key, v, block.path("rating").asText("")));
        }
        return out;
    }

    /**
     * The ~1y daily series ({@code [{x: epochMs, y: score}, ...]}). Malformed or
     * out-of-band samples are skipped rather than failing the whole reading; the
     * series is capped by stride-sampling (keeping the newest point) so the
     * websocket payload stays small no matter what CNN ships.
     */
    private static java.util.List<FearGreedIndex.Point> parseHistory(JsonNode data) {
        if (!data.isArray() || data.isEmpty()) return java.util.List.of();
        java.util.List<FearGreedIndex.Point> pts = new java.util.ArrayList<>(data.size());
        for (JsonNode n : data) {
            if (!n.path("x").isNumber() || !n.path("y").isNumber()) continue;
            double y = n.path("y").asDouble();
            if (!Double.isFinite(y) || y < 0 || y > 100) continue;
            pts.add(new FearGreedIndex.Point(n.path("x").asLong(), y));
        }
        pts.sort(java.util.Comparator.comparingLong(FearGreedIndex.Point::epochMs));
        int max = 400;
        if (pts.size() <= max) return pts;
        java.util.List<FearGreedIndex.Point> sampled = new java.util.ArrayList<>(max);
        double stride = (pts.size() - 1) / (double) (max - 1);
        for (int i = 0; i < max; i++) sampled.add(pts.get((int) Math.round(i * stride)));
        return sampled;
    }
}
