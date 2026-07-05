package de.bsommerfeld.wsbg.terminal.yahoofinance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure JSON→domain parsers for the three Yahoo endpoints the client speaks to:
 * {@code v1/finance/search} (quotes + news), {@code v8/finance/chart} (one
 * snapshot) and {@code v7/finance/spark} (many snapshots). All static, no
 * state — extracted from {@code YahooFinanceClient} so the client is left with
 * transport + orchestration and the parsing stays independently unit-testable.
 */
final class YahooResponseParser {

    private static final Logger LOG = LoggerFactory.getLogger(YahooResponseParser.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Upper bound on sparkline points handed to the UI. The intraday series is
     * downsampled to at most this many evenly-spaced points — enough for a
     * legible micro-chart, small enough to keep the headline payload tidy.
     */
    private static final int SPARK_MAX_POINTS = 48;

    private YahooResponseParser() {
    }

    static SearchResult parseSearch(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            List<YahooQuote> quotes = new ArrayList<>();
            JsonNode quotesNode = root.path("quotes");
            if (quotesNode.isArray()) {
                for (JsonNode q : quotesNode) {
                    String symbol = q.path("symbol").asText("");
                    if (symbol.isEmpty()) continue;
                    quotes.add(new YahooQuote(
                            symbol,
                            text(q, "shortname"),
                            text(q, "longname"),
                            text(q, "quoteType"),
                            text(q, "exchange"),
                            text(q, "exchDisp"),
                            text(q, "sector"),
                            text(q, "industry"),
                            q.path("regularMarketPrice").isNumber()
                                    ? q.path("regularMarketPrice").asDouble() : Double.NaN,
                            q.path("regularMarketPercentChange").isNumber()
                                    ? q.path("regularMarketPercentChange").asDouble() : Double.NaN,
                            q.path("score").asDouble(0.0)));
                }
            }

            List<RawNewsItem> news = new ArrayList<>();
            JsonNode newsNode = root.path("news");
            if (newsNode.isArray()) {
                for (JsonNode n : newsNode) {
                    String title = text(n, "title");
                    if (title.isEmpty()) continue;
                    long ts = n.path("providerPublishTime").asLong(0L);
                    List<String> related = new ArrayList<>();
                    JsonNode rt = n.path("relatedTickers");
                    if (rt.isArray()) {
                        for (JsonNode r : rt) {
                            String s = r.asText("");
                            if (!s.isEmpty()) related.add(s);
                        }
                    }
                    news.add(new RawNewsItem(
                            text(n, "uuid"),
                            title,
                            text(n, "publisher"),
                            text(n, "link"),
                            ts > 0 ? Instant.ofEpochSecond(ts) : null,
                            Collections.unmodifiableList(related)));
                }
            }
            return new SearchResult(
                    Collections.unmodifiableList(quotes),
                    Collections.unmodifiableList(news), false);
        } catch (Exception e) {
            LOG.warn("Failed to parse Yahoo search response: {}", e.getMessage());
            return SearchResult.empty();
        }
    }

    /**
     * Parses a {@code v8/chart} response into a {@link MarketSnapshot}.
     * Returns {@code null} when the response has no usable result block
     * (Yahoo wraps an {@code error} object instead of a result for
     * unknown symbols).
     *
     * <p>
     * Chart response shape (trimmed):
     * <pre>{@code
     * { "chart": { "result": [{
     *     "meta": { "regularMarketPrice": 214.25, "previousClose": 212.6,
     *               "regularMarketDayHigh": 215.5, "regularMarketDayLow": 211.2,
     *               "regularMarketVolume": 141557394,
     *               "fiftyTwoWeekHigh": 236.5, "fiftyTwoWeekLow": 132.9,
     *               "currency": "USD", "exchangeName": "NMS",
     *               "regularMarketTime": 1779998400, "symbol": "NVDA" },
     *     "timestamp": [ ... ],
     *     "indicators": { "quote": [{ "close": [213.2, 214.0, ... ] }] }
     * }], "error": null } }
     * }</pre>
     */
    static MarketSnapshot parseChart(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode result = root.path("chart").path("result");
            if (!result.isArray() || result.isEmpty()) {
                LOG.warn("Yahoo chart: empty result array");
                return null;
            }
            JsonNode r0 = result.get(0);
            JsonNode meta = r0.path("meta");

            double price = num(meta, "regularMarketPrice");
            double prevClose = num(meta, "previousClose", "chartPreviousClose");
            double change = (Double.isFinite(price) && Double.isFinite(prevClose) && prevClose != 0.0)
                    ? (price - prevClose) / prevClose * 100.0
                    : Double.NaN;

            long volume = meta.path("regularMarketVolume").isNumber()
                    ? meta.path("regularMarketVolume").asLong() : -1L;
            long marketTime = meta.path("regularMarketTime").isNumber()
                    ? meta.path("regularMarketTime").asLong() : 0L;

            List<Double> spark = extractSpark(r0);

            return new MarketSnapshot(
                    text(meta, "symbol"),
                    price,
                    prevClose,
                    change,
                    num(meta, "regularMarketDayHigh"),
                    num(meta, "regularMarketDayLow"),
                    volume,
                    num(meta, "fiftyTwoWeekHigh"),
                    num(meta, "fiftyTwoWeekLow"),
                    text(meta, "currency"),
                    text(meta, "exchangeName"),
                    marketTime,
                    spark);
        } catch (Exception e) {
            LOG.warn("Failed to parse Yahoo chart response: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parses a {@code v7/spark} response into per-symbol snapshots. Tolerant: any
     * symbol it can't read is simply omitted (the caller falls back to v8/chart).
     * The {@code close[]} series is carried through as the sparkline — same data
     * the UI already draws — so the chart keeps working.
     */
    static List<MarketSnapshot> parseSpark(String body) throws Exception {
        List<MarketSnapshot> out = new ArrayList<>();
        JsonNode result = JSON.readTree(body).path("spark").path("result");
        if (!result.isArray()) return out;
        for (JsonNode r : result) {
            JsonNode resp = r.path("response");
            JsonNode r0 = resp.isArray() && !resp.isEmpty() ? resp.get(0) : resp;
            JsonNode meta = r0.path("meta");
            String symbol = r.path("symbol").asText(meta.path("symbol").asText(""));
            if (symbol.isEmpty()) continue;

            List<Double> spark = new ArrayList<>();
            JsonNode quote = r0.path("indicators").path("quote");
            if (quote.isArray() && !quote.isEmpty()) {
                for (JsonNode c : quote.get(0).path("close")) {
                    if (c.isNumber()) spark.add(c.asDouble());
                }
            }
            double prevClose = meta.path("previousClose").isNumber()
                    ? meta.path("previousClose").asDouble()
                    : meta.path("chartPreviousClose").asDouble(Double.NaN);
            double price = meta.path("regularMarketPrice").isNumber()
                    ? meta.path("regularMarketPrice").asDouble()
                    : (spark.isEmpty() ? Double.NaN : spark.get(spark.size() - 1));
            double pct = Double.isFinite(price) && Double.isFinite(prevClose) && prevClose != 0.0
                    ? (price - prevClose) / prevClose * 100.0 : Double.NaN;

            out.add(new MarketSnapshot(
                    symbol, price, prevClose, pct,
                    Double.NaN, Double.NaN, -1L, Double.NaN, Double.NaN,
                    emptyToNull(meta.path("currency").asText("")),
                    emptyToNull(meta.path("exchangeName").asText("")),
                    meta.path("regularMarketTime").asLong(0L),
                    spark));
        }
        return out;
    }

    /**
     * Pulls the intraday close series and downsamples it to at most
     * {@link #SPARK_MAX_POINTS} evenly-spaced finite points. Yahoo
     * peppers the series with {@code null} gaps (halts, illiquid minutes);
     * those are dropped before downsampling so the line stays continuous.
     */
    private static List<Double> extractSpark(JsonNode result) {
        JsonNode closes = result.path("indicators").path("quote");
        if (!closes.isArray() || closes.isEmpty()) return List.of();
        JsonNode closeArr = closes.get(0).path("close");
        if (!closeArr.isArray()) return List.of();

        List<Double> clean = new ArrayList<>(closeArr.size());
        for (JsonNode c : closeArr) {
            if (c.isNumber()) {
                double v = c.asDouble();
                if (Double.isFinite(v)) clean.add(v);
            }
        }
        if (clean.size() <= SPARK_MAX_POINTS) return clean;

        // Even-stride downsample, always keeping the last point so the
        // sparkline ends on the latest price.
        List<Double> out = new ArrayList<>(SPARK_MAX_POINTS);
        double stride = (clean.size() - 1) / (double) (SPARK_MAX_POINTS - 1);
        for (int i = 0; i < SPARK_MAX_POINTS - 1; i++) {
            out.add(clean.get((int) Math.round(i * stride)));
        }
        out.add(clean.get(clean.size() - 1));
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return "";
        return v.asText("");
    }

    /**
     * First finite numeric field among {@code fields}, or {@link Double#NaN}.
     * Lets a primary key fall back to an alternate ({@code previousClose}
     * → {@code chartPreviousClose}).
     */
    private static double num(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.path(f);
            if (v.isNumber()) {
                double d = v.asDouble();
                if (Double.isFinite(d)) return d;
            }
        }
        return Double.NaN;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
