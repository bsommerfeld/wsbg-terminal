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
     * Response shape:
     * <pre>{@code
     * { "fear_and_greed": { "score": 63.27, "rating": "greed",
     *                       "timestamp": "...", "previous_close": 60.1, ... }, ... }
     * }</pre>
     */
    Optional<FearGreedIndex> parse(String body) {
        try {
            JsonNode fg = JSON.readTree(body).path("fear_and_greed");
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
            return Optional.of(new FearGreedIndex(s, rating, prevClose, Instant.now()));
        } catch (Exception e) {
            LOG.warn("Fear&Greed parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
