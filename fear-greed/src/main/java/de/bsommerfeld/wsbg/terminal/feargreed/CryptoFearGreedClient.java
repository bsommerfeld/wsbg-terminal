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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches the crypto Fear &amp; Greed Index from alternative.me's free JSON API
 * ({@code https://api.alternative.me/fng/}) — keyless, documented, no bot wall
 * (verified live 2026-07-10 with a bare client). Rides the shared injected
 * {@link WebFetcher} chain (browser joker first, plain HTTP as the per-request
 * fallback — the 2026-07-14 joker-first mandate for all third-party outreach);
 * the no-arg ctor keeps a {@link DirectWebFetcher} for tests/CLI where no
 * embedded browser exists.
 *
 * <p>{@code limit=45} returns the newest ~6 weeks (newest first, values as JSON
 * strings) — enough for the widget's mini trend without bloating the socket.
 *
 * <p>Each call returns {@link Optional#empty()} on any failure (network,
 * non-200, parse, out-of-band score) and logs at WARN; the monitor keeps the
 * last good reading.
 */
@Singleton
public class CryptoFearGreedClient {

    private static final Logger LOG = LoggerFactory.getLogger(CryptoFearGreedClient.class);

    static final String URL = "https://api.alternative.me/fng/?limit=45";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final java.time.Duration requestTimeout = java.time.Duration.ofSeconds(10);

    /** Direct-HTTP variant for tests/CLI (no embedded browser available). */
    public CryptoFearGreedClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public CryptoFearGreedClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Fetches the current crypto index + its recent daily series, or empty on any failure. */
    public Optional<CryptoFearGreedIndex> fetch() {
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp.status() != 200) {
                LOG.warn("Crypto Fear&Greed returned HTTP {}", resp.status());
                return Optional.empty();
            }
            return parse(resp.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("Crypto Fear&Greed request failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Response shape (note: numbers arrive as JSON <em>strings</em>, newest first):
     * <pre>{@code
     * { "name": "Fear and Greed Index",
     *   "data": [ { "value": "23", "value_classification": "Extreme Fear",
     *               "timestamp": "1783641600" }, ... ] }
     * }</pre>
     */
    Optional<CryptoFearGreedIndex> parse(String body) {
        try {
            JsonNode data = JSON.readTree(body).path("data");
            if (!data.isArray() || data.isEmpty()) {
                LOG.warn("Crypto Fear&Greed: data array missing or empty");
                return Optional.empty();
            }
            // Newest first: samples become the chronological history, entry 0 the reading.
            List<FearGreedIndex.Point> history = new ArrayList<>(data.size());
            for (int i = data.size() - 1; i >= 0; i--) {
                JsonNode n = data.get(i);
                Double v = parseScore(n.path("value").asText(""));
                long epochSec = parseLong(n.path("timestamp").asText(""));
                if (v == null || epochSec <= 0) continue;
                history.add(new FearGreedIndex.Point(epochSec * 1000L, v));
            }
            JsonNode latest = data.get(0);
            Double score = parseScore(latest.path("value").asText(""));
            if (score == null) {
                LOG.warn("Crypto Fear&Greed: latest value missing or out of band");
                return Optional.empty();
            }
            String rating = latest.path("value_classification").asText("");
            Double prevClose = data.size() > 1
                    ? parseScore(data.get(1).path("value").asText("")) : null;
            return Optional.of(new CryptoFearGreedIndex(score, rating, prevClose,
                    Instant.now(), history));
        } catch (Exception e) {
            LOG.warn("Crypto Fear&Greed parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** A stringly-typed 0–100 score, or {@code null} when unparsable/out-of-band. */
    private static Double parseScore(String raw) {
        try {
            double v = Double.parseDouble(raw);
            return Double.isFinite(v) && v >= 0 && v <= 100 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseLong(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
