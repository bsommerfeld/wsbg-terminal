package de.bsommerfeld.wsbg.terminal.currency;

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
import java.util.Map;
import java.util.Optional;

/**
 * Fetches the EUR&rarr;USD rate from two unauthenticated public APIs.
 *
 * <p>
 * Both endpoints return the rate in EUR-base form, i.e. "1 EUR = X USD",
 * so the value falls through directly into {@link EurUsdQuote#rate}.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li><b>Primary</b> — Yahoo Finance
 *       {@code https://query1.finance.yahoo.com/v8/finance/chart/EURUSD=X}.
 *       Intraday updates throughout the FX trading week (Sun 22:00 UTC &rarr;
 *       Fri 22:00 UTC). Free, no API key, undocumented but stable in
 *       practice — relied on by yfinance and dozens of mirrors. Sends a
 *       browser-shaped User-Agent to avoid the bot block.
 *       <p>
 *       Note: the older {@code /v7/finance/quote} endpoint was deliberately
 *       <em>not</em> used. Since Yahoo's mid-2024 lockdown it hard-requires a
 *       session cookie + crumb token and returns {@code 401} on every
 *       unauthenticated request — regardless of poll rate. The {@code /v8/chart}
 *       endpoint carries the same price in {@code meta.regularMarketPrice}
 *       without that handshake.</li>
 *   <li><b>Fallback</b> — Frankfurter
 *       {@code https://api.frankfurter.dev/v1/latest?base=EUR&symbols=USD}.
 *       Official ECB reference rate, updated once per business day around
 *       16:00 CET. Free, no API key, no rate limits, very stable. Slower
 *       update cadence but rock-solid availability for when Yahoo flakes.</li>
 * </ul>
 *
 * <h3>Failure model</h3>
 * Each call returns {@link Optional#empty()} on any failure (network, non-200,
 * parse error, missing field) and logs at WARN. The orchestrating
 * {@link EurUsdMonitorService} uses that to decide whether to fall through
 * from primary to fallback.
 */
@Singleton
public class EurUsdClient {

    private static final Logger LOG = LoggerFactory.getLogger(EurUsdClient.class);

    private static final String YAHOO_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/EURUSD=X";
    private static final String FRANKFURTER_URL =
            "https://api.frankfurter.dev/v1/latest?base=EUR&symbols=USD";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * A random, realistic browser User-Agent chosen once per process — Yahoo's
     * {@code v8/chart} endpoint bot-blocks bare HTTP-library agents, and a
     * single shared string is easy to block wholesale. See {@link BrowserUserAgent}.
     */
    private final String userAgent = BrowserUserAgent.random();

    private final WebFetcher fetcher;
    private final Duration requestTimeout;

    /** Test/default: plain direct transport. */
    public EurUsdClient() {
        this(new DirectWebFetcher(), 10);
    }

    /** Test convenience with a custom timeout (direct transport). */
    public EurUsdClient(int requestTimeoutSeconds) {
        this(new DirectWebFetcher(), requestTimeoutSeconds);
    }

    /**
     * Production: rides the shared {@link WebFetcher} chain (browser joker →
     * direct), same as the editorial Yahoo calls — so the EUR/USD quote carries
     * a real browser fingerprint and gets a live Yahoo {@code v8/chart} answer
     * instead of the bare-client 429 that forced the Frankfurter fallback. The
     * Frankfurter floor still stands when Yahoo is genuinely down.
     */
    @Inject
    public EurUsdClient(WebFetcher fetcher) {
        this(fetcher, 10);
    }

    private EurUsdClient(WebFetcher fetcher, int requestTimeoutSeconds) {
        this.fetcher = fetcher;
        this.requestTimeout = Duration.ofSeconds(Math.max(2, requestTimeoutSeconds));
    }

    /** Fetches the EUR/USD rate from Yahoo Finance. */
    public Optional<Double> fetchYahoo() {
        return fetch(YAHOO_URL, "Yahoo", this::parseYahoo);
    }

    /** Fetches the EUR/USD rate from Frankfurter (ECB reference). */
    public Optional<Double> fetchFrankfurter() {
        return fetch(FRANKFURTER_URL, "Frankfurter", this::parseFrankfurter);
    }

    private Optional<Double> fetch(String url, String label, Parser parser) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp.status() != 200) {
                LOG.warn("{} EUR/USD returned HTTP {}", label, resp.status());
                return Optional.empty();
            }
            return parser.parse(resp.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("{} EUR/USD request failed: {}", label, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Yahoo {@code /v8/chart} response shape:
     * <pre>{@code
     * {
     *   "chart": {
     *     "result": [{ "meta": { "regularMarketPrice": 1.0876, ... }, ... }],
     *     "error": null
     *   }
     * }
     * }</pre>
     */
    Optional<Double> parseYahoo(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode result = root.path("chart").path("result");
            if (!result.isArray() || result.isEmpty()) {
                LOG.warn("Yahoo EUR/USD: empty result array");
                return Optional.empty();
            }
            JsonNode price = result.get(0).path("meta").path("regularMarketPrice");
            if (!price.isNumber()) {
                LOG.warn("Yahoo EUR/USD: regularMarketPrice missing or non-numeric");
                return Optional.empty();
            }
            return validateRate(price.asDouble(), "Yahoo");
        } catch (Exception e) {
            LOG.warn("Yahoo EUR/USD parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Frankfurter response shape:
     * <pre>{@code
     * {
     *   "amount": 1.0,
     *   "base": "EUR",
     *   "date": "2024-05-23",
     *   "rates": { "USD": 1.0832 }
     * }
     * }</pre>
     */
    Optional<Double> parseFrankfurter(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode usd = root.path("rates").path("USD");
            if (!usd.isNumber()) {
                LOG.warn("Frankfurter EUR/USD: rates.USD missing or non-numeric");
                return Optional.empty();
            }
            return validateRate(usd.asDouble(), "Frankfurter");
        } catch (Exception e) {
            LOG.warn("Frankfurter EUR/USD parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Sanity-checks a rate value. EUR/USD has historically traded in the
     * 0.85–1.60 band — anything outside that range is almost certainly a
     * parsing accident (wrong field, inverted pair, etc.).
     */
    private Optional<Double> validateRate(double rate, String label) {
        if (!Double.isFinite(rate) || rate < 0.5 || rate > 2.0) {
            LOG.warn("{} EUR/USD: rate {} outside sanity band [0.5, 2.0]", label, rate);
            return Optional.empty();
        }
        return Optional.of(rate);
    }

    @FunctionalInterface
    private interface Parser {
        Optional<Double> parse(String body);
    }
}
