package de.bsommerfeld.wsbg.terminal.nasdaq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * NASDAQ.com quote client — a US-source for the after-hours window (and to take
 * load off Yahoo). Keyed on the Yahoo ticker. Returns a <b>USD</b> snapshot; the
 * price-source chain converts it to EUR via the live EUR/USD rate.
 *
 * <p>{@code api.nasdaq.com/api/quote/<symbol>/info?assetclass=stocks} bot-walls bare
 * clients (HTTP 418/403/timeout), so this rides the shared browser-joker
 * {@link WebFetcher}. The response carries {@code data.primaryData} (regular session)
 * and {@code data.secondaryData} (pre/after-hours, null intraday) — the after-hours
 * block is preferred when present, which is exactly the 22:00–02:00 CET window L&amp;S
 * doesn't fully cover. Returns {@link Optional#empty()} on any failure.
 */
@Singleton
public class NasdaqClient {

    private static final Logger LOG = LoggerFactory.getLogger(NasdaqClient.class);

    private static final String URL_PREFIX = "https://api.nasdaq.com/api/quote/";
    private static final String URL_SUFFIX = "/info?assetclass=stocks";
    // Intraday chart incl. pre/after-hours → the sparkline. No fromdate (that's for
    // historical and returns empty for intraday).
    private static final String CHART_SUFFIX = "/chart?assetclass=stocks";
    private static final int SPARK_POINTS = 40;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(10);

    /** Test/default: plain direct transport (hits the wall live — joker needed). */
    public NasdaqClient() {
        this(new DirectWebFetcher());
    }

    /** Production: rides the shared browser-joker {@link WebFetcher} chain. */
    @Inject
    public NasdaqClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Live USD snapshot for a ticker, or empty. */
    public Optional<MarketSnapshot> fetchSnapshot(String ticker) {
        if (ticker == null || ticker.isBlank()) return Optional.empty();
        // NASDAQ symbols are bare (no Yahoo suffix); a dotted/suffixed symbol isn't a US listing.
        String sym = ticker.trim().toUpperCase(java.util.Locale.ROOT);
        if (sym.indexOf('.') >= 0 || sym.indexOf('-') >= 0 || sym.indexOf('=') >= 0) return Optional.empty();
        try {
            WebResponse resp = fetcher.fetch(URL_PREFIX + sym + URL_SUFFIX,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp.status() != 200) {
                LOG.debug("NASDAQ returned HTTP {} for {}", resp.status(), sym);
                return Optional.empty();
            }
            Optional<MarketSnapshot> base = parse(resp.body(), sym);
            if (base.isEmpty()) return base;
            List<Double> spark = fetchSpark(sym); // best-effort intraday chart
            return Optional.of(withSpark(base.get(), spark));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("NASDAQ request failed for {}: {}", sym, e.getMessage());
            return Optional.empty();
        }
    }

    /** Best-effort intraday sparkline from the NASDAQ chart endpoint; empty on any failure. */
    private List<Double> fetchSpark(String sym) {
        try {
            WebResponse resp = fetcher.fetch(URL_PREFIX + sym + CHART_SUFFIX,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"), requestTimeout);
            if (resp.status() != 200) { LOG.info("[NASDAQ] chart {} → HTTP {}", sym, resp.status()); return List.of(); }
            List<Double> spark = parseChartSpark(resp.body());
            String b = resp.body() == null ? "" : resp.body();
            LOG.info("[NASDAQ] chart {} → {} spark points{}", sym, spark.size(),
                    spark.isEmpty() ? " (body: " + (b.length() > 200 ? b.substring(0, 200) : b) + ")" : "");
            return spark;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /** Chart shape: {@code {"data":{"chart":[{"x":<ts>,"y":<price>,"z":{"value":"..."}},…]}}}. */
    static List<Double> parseChartSpark(String body) {
        try {
            JsonNode chart = JSON.readTree(body).path("data").path("chart");
            if (!chart.isArray() || chart.isEmpty()) return List.of();
            List<Double> prices = new ArrayList<>();
            for (JsonNode pt : chart) {
                double y = pt.path("y").isNumber() ? pt.path("y").asDouble()
                        : parseMoney(pt.path("z").path("value").asText(""));
                if (Double.isFinite(y)) prices.add(y);
            }
            return downsample(prices);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<Double> downsample(List<Double> prices) {
        if (prices.size() <= SPARK_POINTS) return prices;
        List<Double> out = new ArrayList<>(SPARK_POINTS);
        double step = (prices.size() - 1) / (double) (SPARK_POINTS - 1);
        for (int i = 0; i < SPARK_POINTS; i++) out.add(prices.get((int) Math.round(i * step)));
        return out;
    }

    private static MarketSnapshot withSpark(MarketSnapshot s, List<Double> spark) {
        if (spark == null || spark.isEmpty()) return s;
        return new MarketSnapshot(s.symbol(), s.price(), s.previousClose(), s.dayChangePercent(),
                s.dayHigh(), s.dayLow(), s.volume(), s.fiftyTwoWeekHigh(), s.fiftyTwoWeekLow(),
                s.currency(), s.exchangeName(), s.marketTimeEpochSeconds(), spark);
    }

    /**
     * Shape: {@code {"data":{"primaryData":{...},"secondaryData":{...}|null}}}.
     * <b>primaryData</b> is the LIVE price — the regular session, or, after the close,
     * the after-hours print ({@code isRealTime:true}); <b>secondaryData</b> is usually
     * the prior session's CLOSE ({@code isRealTime:false}). We pick the real-time block
     * (primaryData wins ties); a non-real-time fallback is marked stale via a zero
     * market-time. (An earlier version preferred secondaryData and showed the close.)
     */
    Optional<MarketSnapshot> parse(String body, String symbol) {
        try {
            JsonNode data = JSON.readTree(body).path("data");
            JsonNode primary = data.path("primaryData");
            JsonNode secondary = data.path("secondaryData");
            JsonNode block = chooseBlock(primary, secondary);

            double price = parseMoney(block.path("lastSalePrice").asText(""));
            if (!Double.isFinite(price)) return Optional.empty();
            double pct = parsePercent(block.path("percentageChange").asText(""));
            boolean realTime = block.path("isRealTime").asBoolean(false);
            LOG.info("[NASDAQ] {} → {} USD (isRealTime={}, ts='{}')", symbol, price, realTime,
                    block.path("lastTradeTimestamp").asText(""));
            return Optional.of(new MarketSnapshot(
                    symbol, price, Double.NaN, pct, Double.NaN, Double.NaN, -1,
                    Double.NaN, Double.NaN, "USD", "NASDAQ",
                    realTime ? Instant.now().getEpochSecond() : 0, List.of()));
        } catch (Exception e) {
            LOG.debug("NASDAQ parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Picks the live block: a real-time one with a price (primaryData wins ties), else whichever has a price. */
    private static JsonNode chooseBlock(JsonNode primary, JsonNode secondary) {
        boolean pPrice = hasSalePrice(primary), sPrice = hasSalePrice(secondary);
        if (primary.path("isRealTime").asBoolean(false) && pPrice) return primary;
        if (secondary.path("isRealTime").asBoolean(false) && sPrice) return secondary;
        if (pPrice) return primary;
        if (sPrice) return secondary;
        return primary;
    }

    private static boolean hasSalePrice(JsonNode block) {
        return Double.isFinite(parseMoney(block.path("lastSalePrice").asText("")));
    }

    /** "$214.25" / "214.25" → 214.25; "N/A" → NaN. */
    static double parseMoney(String s) {
        if (s == null) return Double.NaN;
        String c = s.replaceAll("[^0-9.\\-]", "");
        if (c.isEmpty() || c.equals("-") || c.equals(".")) return Double.NaN;
        try {
            return Double.parseDouble(c);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** "+0.99%" → 0.99; "-1.2%" → -1.2; "" → NaN. */
    static double parsePercent(String s) {
        if (s == null) return Double.NaN;
        String c = s.replace("%", "").replace("+", "").trim();
        if (c.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(c);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
