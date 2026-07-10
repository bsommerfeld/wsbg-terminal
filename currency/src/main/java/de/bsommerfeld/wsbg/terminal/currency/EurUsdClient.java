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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches the EUR&rarr;USD rate from two unauthenticated public APIs.
 *
 * <p>
 * Both endpoints return the rate in EUR-base form, i.e. "1 EUR = X USD",
 * so the value falls through directly into {@link EurUsdQuote#rate}. Beyond
 * the bare rate this client also serves the detail widget: the Yahoo chart
 * call carries the intraday series + day/52-week ranges ({@link YahooFx}),
 * and a Frankfurter date-range query provides the ~1y official ECB daily
 * series ({@link EcbHistory}).
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

    /**
     * {@code range=1d&interval=5m} rides the SAME chart call the plain rate uses:
     * one response carries the live rate (meta), the day-change basis (previous
     * close), day high/low, the 52-week band and the intraday series for the
     * widget's sparkline — no second endpoint, no extra traffic.
     */
    private static final String YAHOO_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/EURUSD=X?range=1d&interval=5m";
    private static final String FRANKFURTER_URL =
            "https://api.frankfurter.dev/v1/latest?base=EUR&symbols=USD";
    /**
     * Frankfurter date-range query (official ECB reference rates, one fix per
     * business day): {@code /v1/<from>..?base=EUR&symbols=USD} returns the whole
     * daily series since {@code <from>} — the detail widget's 1y history chart
     * and the "EZB-Referenzkurs" figure. Keyless, no rate limits, no bot wall.
     */
    private static final String ECB_HISTORY_URL =
            "https://api.frankfurter.dev/v1/%s..?base=EUR&symbols=USD";

    /** Intraday spark cap — enough shape for a widget-width chart, small on the socket. */
    private static final int MAX_SPARK_POINTS = 96;

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

    /** Fetches the EUR/USD rate from Yahoo Finance (rate only). */
    public Optional<Double> fetchYahoo() {
        return fetchYahooDetailed().map(YahooFx::rate);
    }

    /**
     * Fetches the full Yahoo intraday picture in one call: live rate, previous
     * close, day high/low, 52-week band and the intraday spark series.
     */
    public Optional<YahooFx> fetchYahooDetailed() {
        String body = fetchBody(YAHOO_URL, "Yahoo");
        return body == null ? Optional.empty() : parseYahooDetailed(body);
    }

    /** Fetches the EUR/USD rate from Frankfurter (ECB reference). */
    public Optional<Double> fetchFrankfurter() {
        String body = fetchBody(FRANKFURTER_URL, "Frankfurter");
        return body == null ? Optional.empty() : parseFrankfurter(body);
    }

    /**
     * Fetches the ~1y daily ECB reference-rate series from Frankfurter. Slow-moving
     * data (one fix per business day) — the monitor refreshes it on a long cadence,
     * not per tick.
     */
    public Optional<EcbHistory> fetchEcbHistory() {
        String from = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(370).toString();
        String body = fetchBody(String.format(ECB_HISTORY_URL, from), "Frankfurter-history");
        return body == null ? Optional.empty() : parseEcbHistory(body);
    }

    private String fetchBody(String url, String label) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp.status() != 200) {
                LOG.warn("{} EUR/USD returned HTTP {}", label, resp.status());
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("{} EUR/USD request failed: {}", label, e.getMessage());
            return null;
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
        return parseYahooDetailed(body).map(YahooFx::rate);
    }

    /**
     * Full parse of the same response: the rate is mandatory (validated), every
     * other field is best-effort — a meta-only body (no series) still yields a
     * usable quote, missing extras stay {@code null}/empty.
     */
    Optional<YahooFx> parseYahooDetailed(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode result = root.path("chart").path("result");
            if (!result.isArray() || result.isEmpty()) {
                LOG.warn("Yahoo EUR/USD: empty result array");
                return Optional.empty();
            }
            JsonNode r0 = result.get(0);
            JsonNode meta = r0.path("meta");
            JsonNode price = meta.path("regularMarketPrice");
            if (!price.isNumber()) {
                LOG.warn("Yahoo EUR/USD: regularMarketPrice missing or non-numeric");
                return Optional.empty();
            }
            Optional<Double> rate = validateRate(price.asDouble(), "Yahoo");
            if (rate.isEmpty()) return Optional.empty();

            Double prevClose = numberOrNull(meta.path("previousClose"));
            if (prevClose == null) prevClose = numberOrNull(meta.path("chartPreviousClose"));

            List<double[]> spark = parseSpark(r0);
            Double dayHigh = numberOrNull(meta.path("regularMarketDayHigh"));
            Double dayLow = numberOrNull(meta.path("regularMarketDayLow"));
            if ((dayHigh == null || dayLow == null) && !spark.isEmpty()) {
                double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
                for (double[] p : spark) { hi = Math.max(hi, p[1]); lo = Math.min(lo, p[1]); }
                if (dayHigh == null) dayHigh = hi;
                if (dayLow == null) dayLow = lo;
            }

            return Optional.of(new YahooFx(rate.get(), prevClose, dayHigh, dayLow,
                    numberOrNull(meta.path("fiftyTwoWeekHigh")),
                    numberOrNull(meta.path("fiftyTwoWeekLow")),
                    spark));
        } catch (Exception e) {
            LOG.warn("Yahoo EUR/USD parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * {@code timestamp[]} + {@code indicators.quote[0].close[]} → sane
     * {@code [epochMs, rate]} pairs (null/out-of-band points skipped), stride-
     * downsampled to {@link #MAX_SPARK_POINTS} with the last point always kept.
     */
    private static List<double[]> parseSpark(JsonNode result) {
        JsonNode ts = result.path("timestamp");
        JsonNode close = result.path("indicators").path("quote").path(0).path("close");
        if (!ts.isArray() || !close.isArray()) return List.of();
        List<double[]> pts = new java.util.ArrayList<>();
        int n = Math.min(ts.size(), close.size());
        for (int i = 0; i < n; i++) {
            JsonNode c = close.get(i);
            if (c == null || !c.isNumber()) continue;
            double v = c.asDouble();
            if (!Double.isFinite(v) || v < 0.5 || v > 2.0) continue;
            pts.add(new double[] {ts.get(i).asLong() * 1000.0, v});
        }
        if (pts.size() <= MAX_SPARK_POINTS) return pts;
        List<double[]> out = new java.util.ArrayList<>(MAX_SPARK_POINTS);
        double stride = (double) pts.size() / MAX_SPARK_POINTS;
        for (int i = 0; i < MAX_SPARK_POINTS - 1; i++) out.add(pts.get((int) (i * stride)));
        out.add(pts.get(pts.size() - 1));
        return out;
    }

    private static Double numberOrNull(JsonNode n) {
        if (n == null || !n.isNumber()) return null;
        double v = n.asDouble();
        return Double.isFinite(v) ? v : null;
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
     * Frankfurter range response shape:
     * <pre>{@code
     * { "base": "EUR", "start_date": "...", "end_date": "...",
     *   "rates": { "2026-06-10": { "USD": 1.1539 }, ... } }
     * }</pre>
     * Dates sort chronologically as strings (ISO), points become
     * {@code [epochMs, rate]} pairs; the last one is the latest ECB fix.
     */
    Optional<EcbHistory> parseEcbHistory(String body) {
        try {
            JsonNode rates = JSON.readTree(body).path("rates");
            if (!rates.isObject() || rates.isEmpty()) {
                LOG.warn("Frankfurter EUR/USD history: no rates object");
                return Optional.empty();
            }
            java.util.TreeMap<String, Double> byDate = new java.util.TreeMap<>();
            rates.properties().forEach(e -> {
                JsonNode usd = e.getValue().path("USD");
                if (usd.isNumber()) {
                    double v = usd.asDouble();
                    if (Double.isFinite(v) && v >= 0.5 && v <= 2.0) byDate.put(e.getKey(), v);
                }
            });
            if (byDate.isEmpty()) return Optional.empty();
            List<double[]> points = new java.util.ArrayList<>(byDate.size());
            for (Map.Entry<String, Double> e : byDate.entrySet()) {
                long ms = java.time.LocalDate.parse(e.getKey())
                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
                points.add(new double[] {ms, e.getValue()});
            }
            return Optional.of(new EcbHistory(points, byDate.lastKey(), byDate.lastEntry().getValue()));
        } catch (Exception e) {
            LOG.warn("Frankfurter EUR/USD history parse failure: {}", e.getMessage());
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

    /**
     * One Yahoo intraday picture: the mandatory live {@code rate} plus best-effort
     * extras ({@code null}/empty when the response didn't carry them). {@code spark}
     * is the intraday series as {@code [epochMs, rate]} pairs, capped at
     * {@link #MAX_SPARK_POINTS}.
     */
    public record YahooFx(
            double rate,
            Double previousClose,
            Double dayHigh,
            Double dayLow,
            Double week52High,
            Double week52Low,
            List<double[]> spark) {}

    /**
     * The daily ECB reference-rate series: {@code [epochMs, rate]} pairs in
     * chronological order plus the latest fix (ISO date + rate).
     */
    public record EcbHistory(List<double[]> points, String latestDate, double latestRate) {}
}
