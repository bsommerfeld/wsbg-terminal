package de.bsommerfeld.wsbg.terminal.web.impl.sources.tmx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The TMX website's own market service
 * ({@code app-money.tmx.com/graphql}, probed 2026-08-05): keyless GraphQL
 * answering the live quote (price, day range, volume, 52-week range, market
 * cap - the field is literally {@code MarketCap} - and the listing currency)
 * plus a DECADE of daily OHLCV bars per symbol - the Canadian home tape,
 * sibling of the NYSE quote client on the US side.
 *
 * <p>GraphQL is a POST with a JSON body, so the calls ride the house
 * {@link WebFetcher#post} seam. The queries are built as plain strings; the
 * symbol gate below is what keeps interpolation safe.
 *
 * <p>Bare TMX symbols only ({@code SHOP}, {@code RY}, class shares like
 * {@code CTC.A}) - a Yahoo-suffixed ({@code SHOP.TO}), index or crypto symbol
 * is a network-free no-op. An unknown symbol answers 200 with a GraphQL
 * {@code errors} array and a null quote; that stays a quiet empty.
 */
@Singleton
public class TmxMarketClient {

    private static final Logger LOG = LoggerFactory.getLogger(TmxMarketClient.class);

    static final String GRAPHQL_URL = "https://app-money.tmx.com/graphql";

    private static final FetchUtil[] MODES = {FetchUtil.BROWSER, FetchUtil.DIRECT};

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Bare TMX symbol, class shares included ({@code CTC.A}, {@code BAM.A}) - nothing Yahoo-suffixed. */
    private static final Pattern TMX_SYMBOL = Pattern.compile("[A-Z]{1,5}(\\.[A-Z]{1,2})?");
    /**
     * Yahoo's Canadian venue codes ({@code SHOP.TO}, {@code XYZ.V}, ...) share the
     * class-share shape but name a venue, not a share class - a closed enum of
     * exchange codes, rejected so the caller's contract (bare TMX symbol) holds.
     */
    private static final Pattern YAHOO_VENUE_SUFFIX = Pattern.compile(".+\\.(TO|V|CN|NE)");
    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

    private final WebFetcher fetcher;
    /** A decade of bars is a quarter-megabyte reply - give it room. */
    private final Duration requestTimeout = Duration.ofSeconds(20);

    @Inject
    public TmxMarketClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The live quote for one bare TMX symbol.
     *
     * @return the quote, or empty when the symbol is not TMX-shaped, unknown
     *         or the endpoint failed - never {@code null}
     */
    public Optional<TmxQuote> quote(String symbol) {
        String t = gate(symbol);
        if (t == null) return Optional.empty();
        String query = "{ getQuoteBySymbol(symbol: \"" + t + "\", locale: \"en\") {"
                + " symbol name exchangeName currency price priceChange percentChange"
                + " openPrice dayHigh dayLow prevClose volume"
                + " weeks52high weeks52low MarketCap } }";
        Optional<TmxQuote> quote = post(t, query).flatMap(TmxMarketClient::parseQuote);
        quote.ifPresent(q -> LOG.info(
                "[TMX] {} ({}) → price={} {} vol={} 52w={}..{} mcap={}",
                q.symbol(), q.exchange(), q.price(), q.currency(), q.volume(),
                q.yearLow(), q.yearHigh(), q.marketCap()));
        return quote;
    }

    /**
     * Daily bars for one bare TMX symbol, {@code from} until today, OLDEST first.
     *
     * @return the history, or empty when the symbol is not TMX-shaped, unknown
     *         or the endpoint failed - never {@code null}
     */
    public Optional<TmxHistory> history(String symbol, LocalDate from) {
        String t = gate(symbol);
        if (t == null || from == null) return Optional.empty();
        String query = "{ getTimeSeriesData(symbol: \"" + t + "\", freq: \"day\", interval: 1,"
                + " start: \"" + from + "\", end: \"" + LocalDate.now(TORONTO) + "\") {"
                + " dateTime open high low close volume } }";
        Optional<TmxHistory> history = post(t, query).flatMap(json -> parseHistory(t, json));
        history.ifPresent(h -> LOG.info("[TMX] {} → {} daily bars since {}",
                h.symbol(), h.bars().size(), h.bars().get(0).date()));
        return history;
    }

    /** The gated, normalized symbol - {@code null} when it is not TMX-shaped. Package-private for tests. */
    static String gate(String symbol) {
        if (symbol == null) return null;
        String t = symbol.trim().toUpperCase(Locale.ROOT);
        if (!TMX_SYMBOL.matcher(t).matches()) return null;
        return YAHOO_VENUE_SUFFIX.matcher(t).matches() ? null : t;
    }

    /** POSTs one GraphQL query, body wrapped as {@code {"query": ...}}. */
    private Optional<String> post(String symbol, String query) {
        try {
            String body = MAPPER.writeValueAsString(MAPPER.createObjectNode().put("query", query));
            WebResponse resp = fetcher.post(GRAPHQL_URL,
                    Map.of("Accept", "application/json"),
                    body, "application/json", requestTimeout, MODES);
            if (resp == null || resp.status() != 200) {
                LOG.debug("[TMX] status {} for {}",
                        resp == null ? "null" : resp.status(), symbol);
                return Optional.empty();
            }
            return Optional.ofNullable(resp.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[TMX] fetch for {} failed: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    /** Parses the quote reply. Package-private for tests. */
    static Optional<TmxQuote> parseQuote(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode q = MAPPER.readTree(json).path("data").path("getQuoteBySymbol");
            String symbol = text(q, "symbol");
            double price = num(q, "price");
            if (symbol == null || !Double.isFinite(price) || price <= 0) {
                return Optional.empty();
            }
            return Optional.of(new TmxQuote(symbol,
                    text(q, "name"), text(q, "exchangeName"), text(q, "currency"),
                    price, num(q, "priceChange"), num(q, "percentChange"),
                    num(q, "openPrice"), num(q, "dayHigh"), num(q, "dayLow"),
                    num(q, "prevClose"),
                    count(q, "volume"),
                    num(q, "weeks52high"), num(q, "weeks52low"),
                    num(q, "MarketCap")));
        } catch (Exception e) {
            LOG.warn("[TMX] unparseable quote payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Parses the time-series reply, turning the tape OLDEST first. Package-private for tests. */
    static Optional<TmxHistory> parseHistory(String symbol, String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            List<TmxHistory.TmxBar> bars = new ArrayList<>();
            for (JsonNode b : MAPPER.readTree(json).path("data").path("getTimeSeriesData")) {
                LocalDate date = barDate(text(b, "dateTime"));
                double close = num(b, "close");
                if (date == null || !Double.isFinite(close) || close <= 0) continue;
                bars.add(new TmxHistory.TmxBar(date, num(b, "open"), num(b, "high"),
                        num(b, "low"), close, num(b, "volume")));
            }
            if (bars.isEmpty()) return Optional.empty();
            bars.sort((a, b) -> a.date().compareTo(b.date()));
            return Optional.of(new TmxHistory(symbol, List.copyOf(bars)));
        } catch (Exception e) {
            LOG.warn("[TMX] unparseable history payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** The service writes bar stamps as offset datetimes ({@code 2026-08-05T16:00:00-04:00}). */
    private static LocalDate barDate(String s) {
        if (s == null) return null;
        try {
            return OffsetDateTime.parse(s).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    /** A number-typed decimal, {@code NaN} when absent/unreadable. */
    private static double num(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return Double.NaN;
        if (v.isNumber()) return v.asDouble();
        try {
            String s = v.asText("").trim().replace(",", "");
            return s.isEmpty() ? Double.NaN : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** A number-typed count, {@code -1} when absent/unreadable. */
    private static long count(JsonNode n, String field) {
        double v = num(n, field);
        return Double.isFinite(v) && v >= 0 ? Math.round(v) : -1;
    }
}
