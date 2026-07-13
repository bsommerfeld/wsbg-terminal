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
import java.util.Optional;

/**
 * CoinGecko's keyless public API for the crypto day-overview (live-verified
 * 2026-07-13): {@code /api/v3/global} — total market cap, its 24h move, BTC
 * dominance — and {@code /api/v3/search/trending} — the coins the crowd is
 * chasing (memecoin +5552 % is cage content). Public rate limit is per-IP and
 * generous for the 2 calls/day this makes at report time.
 */
@Singleton
public class CoinGeckoClient {

    private static final Logger LOG = LoggerFactory.getLogger(CoinGeckoClient.class);

    private static final String GLOBAL_URL = "https://api.coingecko.com/api/v3/global";
    private static final String TRENDING_URL = "https://api.coingecko.com/api/v3/search/trending";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The crypto market at a glance. Fields are NaN when the reply lacked them. */
    public record CryptoGlobal(double marketCapUsd, double mcapChange24hPercent,
            double btcDominancePercent) {
    }

    /** One trending coin; {@code change24hPercent} NaN when absent. */
    public record TrendingCoin(String name, String symbol, int marketCapRank,
            double change24hPercent) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public CoinGeckoClient() {
        this(new DirectWebFetcher());
    }

    /** Production: direct-first chain. */
    @Inject
    public CoinGeckoClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Global market snapshot, or empty on any failure. */
    public Optional<CryptoGlobal> global() {
        String body = get(GLOBAL_URL);
        return parseGlobal(body);
    }

    /** Currently trending coins, capped. Empty on any failure. */
    public List<TrendingCoin> trending(int limit) {
        String body = get(TRENDING_URL);
        List<TrendingCoin> out = parseTrending(body);
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private String get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("[CoinGecko] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[CoinGecko] fetch {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /** Package-private for tests: /global JSON → snapshot, network-free. */
    static Optional<CryptoGlobal> parseGlobal(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode data = JSON.readTree(body).get("data");
            if (data == null || data.isNull()) return Optional.empty();
            double mcap = doubleAt(data.path("total_market_cap"), "usd");
            double change = data.path("market_cap_change_percentage_24h_usd").asDouble(Double.NaN);
            double btc = doubleAt(data.path("market_cap_percentage"), "btc");
            if (Double.isNaN(mcap) && Double.isNaN(change) && Double.isNaN(btc)) {
                return Optional.empty();
            }
            return Optional.of(new CryptoGlobal(mcap, change, btc));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Package-private for tests: /search/trending JSON → coins, network-free. */
    static List<TrendingCoin> parseTrending(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<TrendingCoin> out = new ArrayList<>();
        try {
            JsonNode coins = JSON.readTree(body).get("coins");
            if (coins == null || !coins.isArray()) return List.of();
            for (JsonNode c : coins) {
                JsonNode item = c.get("item");
                if (item == null) continue;
                String name = item.path("name").asText("");
                if (name.isEmpty()) continue;
                // The 24h move sits in the nested "data" block on newer replies.
                double change = doubleAt(item.path("data").path("price_change_percentage_24h"), "usd");
                out.add(new TrendingCoin(name,
                        item.path("symbol").asText("").toUpperCase(java.util.Locale.ROOT),
                        item.path("market_cap_rank").asInt(-1), change));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    private static double doubleAt(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) return Double.NaN;
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? Double.NaN : v.asDouble(Double.NaN);
    }
}
