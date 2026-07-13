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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Polymarket's public gamma API (live-verified 2026-07-13, keyless) — "was die
 * Wetten sagen": the highest-volume open prediction markets with their traded
 * odds. One evening line ("Fed cut in September trades at 78 ¢") is real-money
 * information no poll carries. Quirk pinned by probe: {@code outcomes} and
 * {@code outcomePrices} arrive as JSON-STRING-encoded arrays inside the JSON
 * ({@code "[\"Yes\",\"No\"]"}), so they are decoded in a second pass.
 */
@Singleton
public class PolymarketClient {

    private static final Logger LOG = LoggerFactory.getLogger(PolymarketClient.class);

    private static final String MARKETS_URL =
            "https://gamma-api.polymarket.com/markets?active=true&closed=false"
                    + "&order=volume24hr&ascending=false&limit=";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One market's leading outcome: the question, the outcome label whose price
     * is quoted, its traded probability in percent, and the 24h volume in USD.
     */
    public record PredictionMarket(String question, String outcome,
            double probabilityPercent, double volume24hUsd) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public PolymarketClient() {
        this(new DirectWebFetcher());
    }

    /** Production: direct-first chain. */
    @Inject
    public PolymarketClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The busiest open markets by 24h volume. Empty on any failure. */
    public List<PredictionMarket> topByVolume(int limit) {
        try {
            WebResponse resp = fetcher.fetch(MARKETS_URL + Math.max(1, limit),
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return parse(resp.body());
            LOG.debug("[Polymarket] answered status {}", resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Polymarket] fetch failed: {}", e.getMessage());
        }
        return List.of();
    }

    /** Package-private for tests: markets JSON → leading outcomes, network-free. */
    static List<PredictionMarket> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<PredictionMarket> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isArray()) return List.of();
            for (JsonNode m : root) {
                String question = m.path("question").asText("");
                if (question.isEmpty()) continue;
                List<String> outcomes = decodeStringArray(m.get("outcomes"));
                List<String> prices = decodeStringArray(m.get("outcomePrices"));
                if (outcomes.isEmpty() || prices.isEmpty()) continue;
                int lead = leadingOutcome(outcomes, prices);
                double price = parseDouble(prices.get(lead));
                if (Double.isNaN(price)) continue;
                out.add(new PredictionMarket(question, outcomes.get(lead), price * 100.0,
                        m.path("volume24hr").asDouble(Double.NaN)));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /**
     * The outcome whose price the briefing quotes: "Yes" when the market is a
     * yes/no question (the natural reading), else the highest-priced outcome.
     */
    private static int leadingOutcome(List<String> outcomes, List<String> prices) {
        for (int i = 0; i < outcomes.size(); i++) {
            if ("yes".equalsIgnoreCase(outcomes.get(i)) && i < prices.size()) return i;
        }
        int best = 0;
        double bestPrice = -1;
        for (int i = 0; i < Math.min(outcomes.size(), prices.size()); i++) {
            double p = parseDouble(prices.get(i));
            if (p > bestPrice) {
                bestPrice = p;
                best = i;
            }
        }
        return best;
    }

    /** A field that is either a JSON array or a JSON-string-encoded array → strings. */
    static List<String> decodeStringArray(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        try {
            JsonNode arr = node.isArray() ? node : JSON.readTree(node.asText(""));
            if (!arr.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode v : arr) out.add(v.asText(""));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
