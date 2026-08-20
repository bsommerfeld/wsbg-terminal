package de.bsommerfeld.wsbg.terminal.web.impl.sources.nordic;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nasdaq's own Nordic market service ({@code api.nasdaq.com/api/nordic/...},
 * probed 2026-08-05) - the exchange's numbers for Stockholm, Helsinki,
 * Copenhagen and Iceland. The service keys instruments by ORDERBOOK ID
 * ({@code TX100}), not ticker or ISIN, so this client first drinks the
 * main-market screener ONCE per JVM (~253 rows carrying isin, symbol and
 * orderbookId) and resolves ISIN → orderbook id from that book; the quote
 * ({@code /instruments/<id>/info}) and the daily chart
 * ({@code /instruments/<id>/chart}, roughly TEN YEARS deep) then answer
 * keyless.
 *
 * <p>Numbers arrive as currency-prefixed, comma-grouped STRINGS
 * ({@code "SEK 366.40"}, {@code "SEK 365.40 x 3,957"}, {@code "2,987,025"}),
 * parsed defensively. A browser User-Agent is MANDATORY - without one the
 * host resets the connection without a word; the transport sets it centrally.
 *
 * <p>Nordic ISINs only ({@code SE/FI/DK/IS}): anything else is a network-free
 * no-op and never even triggers the screener bootstrap, same gate the NYSE
 * client keeps for its tickers.
 */
@Singleton
public class NordicMarketClient {

    private static final Logger LOG = LoggerFactory.getLogger(NordicMarketClient.class);

    static final String SCREENER_URL =
            "https://api.nasdaq.com/api/nordic/screener/shares?category=MAIN_MARKET&tableonly=false";
    static final String INFO_URL =
            "https://api.nasdaq.com/api/nordic/instruments/%s/info?assetClass=SHARES";
    static final String CHART_URL =
            "https://api.nasdaq.com/api/nordic/instruments/%s/chart?assetClass=SHARES&fromDate=%s&toDate=%s";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** A Nordic home ISIN - Sweden, Finland, Denmark or Iceland. */
    private static final Pattern NORDIC_ISIN =
            Pattern.compile("(SE|FI|DK|IS)[0-9A-Z]{9}[0-9]");
    /** The service's currency prefix on money strings ({@code "SEK 366.40"}). */
    private static final Pattern CURRENCY_PREFIX = Pattern.compile("^([A-Z]{3})\\s");

    private static final FetchUtil[] MODES = {FetchUtil.BROWSER, FetchUtil.DIRECT};

    private final WebFetcher fetcher;
    /** Screener ~200 KB, a ten-year chart ~400 KB - give them room. */
    private final Duration requestTimeout = Duration.ofSeconds(20);

    /** ISIN → orderbook id, drunk from the screener once. Null until then. */
    private volatile Map<String, String> book;

    @Inject
    public NordicMarketClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The live quote for one Nordic ISIN.
     *
     * @return the quote, or empty when the ISIN is not Nordic-shaped, unknown
     *         to the main-market screener or the endpoint failed - never
     *         {@code null}
     */
    public Optional<NordicQuote> quoteByIsin(String isin) {
        Optional<String> id = resolve(isin);
        if (id.isEmpty()) return Optional.empty();
        Optional<NordicQuote> quote = get(String.format(INFO_URL, id.get()))
                .flatMap(NordicMarketClient::parseQuote);
        quote.ifPresent(q -> LOG.info(
                "[Nordic] {} ({}, {}) → last={} {} bid={}x{} ask={}x{} vol={}",
                q.symbol(), q.exchange(), q.isin(), q.last(), q.currency(),
                q.bid(), q.bidSize(), q.ask(), q.askSize(), q.volume()));
        return quote;
    }

    /**
     * The daily tape for one Nordic ISIN, from {@code from} (or the service's
     * roughly ten-year horizon when {@code null}) up to today.
     *
     * @return the history, or empty under the same conditions as
     *         {@link #quoteByIsin(String)} - never {@code null}
     */
    public Optional<NordicHistory> historyByIsin(String isin, LocalDate from) {
        Optional<String> id = resolve(isin);
        if (id.isEmpty()) return Optional.empty();
        LocalDate to = LocalDate.now();
        LocalDate start = from != null ? from : to.minusYears(10);
        Optional<NordicHistory> history =
                get(String.format(CHART_URL, id.get(), start, to))
                        .flatMap(NordicMarketClient::parseHistory);
        history.ifPresent(h -> LOG.info("[Nordic] {} ({}) → {} bars in {}",
                h.symbol(), h.isin(), h.bars().size(), h.currency()));
        return history;
    }

    /**
     * ISIN → orderbook id through the screener book, bootstrapped lazily and
     * at most once per JVM. The Nordic gate sits BEFORE the bootstrap: a
     * non-Nordic ISIN never touches the network at all.
     */
    private Optional<String> resolve(String isin) {
        if (isin == null) return Optional.empty();
        String key = isin.trim().toUpperCase(Locale.ROOT);
        if (!NORDIC_ISIN.matcher(key).matches()) return Optional.empty();
        Map<String, String> b = book;
        if (b == null) {
            synchronized (this) {
                b = book;
                if (b == null) {
                    b = get(SCREENER_URL).map(NordicMarketClient::parseScreener)
                            .orElse(Map.of());
                    if (b.isEmpty()) return Optional.empty(); // retry next call
                    book = b;
                    LOG.info("[Nordic] screener book loaded: {} listings", b.size() / 2);
                }
            }
        }
        return Optional.ofNullable(b.get(key));
    }

    /** One GET against the service; empty on any failure. */
    private Optional<String> get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("Accept", "application/json"),
                    requestTimeout, MODES);
            if (resp == null || resp.status() != 200) {
                LOG.debug("[Nordic] status {} for {}",
                        resp == null ? "null" : resp.status(), url);
                return Optional.empty();
            }
            return Optional.ofNullable(resp.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Nordic] fetch {} failed: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The screener's rows as a resolver book: every listing under BOTH its
     * ISIN and its symbol. Package-private for tests.
     */
    static Map<String, String> parseScreener(String json) {
        Map<String, String> book = new HashMap<>();
        if (json == null || json.isBlank()) return book;
        try {
            JsonNode rows = MAPPER.readTree(json)
                    .path("data").path("instrumentListing").path("rows");
            for (JsonNode r : rows) {
                String id = text(r, "orderbookId");
                if (id == null) continue;
                String isin = text(r, "isin");
                String symbol = text(r, "symbol");
                if (isin != null) book.put(isin.toUpperCase(Locale.ROOT), id);
                if (symbol != null) book.put(symbol.toUpperCase(Locale.ROOT), id);
            }
        } catch (Exception e) {
            LOG.warn("[Nordic] unparseable screener payload: {}", e.getMessage());
        }
        return book;
    }

    /** Parses the info reply's {@code qdHeader} branch. Package-private for tests. */
    static Optional<NordicQuote> parseQuote(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode q = MAPPER.readTree(json).path("data").path("qdHeader");
            String symbol = text(q, "symbol");
            String lastRaw = text(q.path("primaryData"), "lastSalePrice");
            double last = money(lastRaw);
            if (symbol == null || !Double.isFinite(last) || last <= 0) {
                return Optional.empty();
            }
            String currency = text(q, "currency");
            if (currency == null) currency = currencyOf(lastRaw);
            JsonNode stats = q.path("keyStats");
            String bid = statValue(stats, "bid");
            String ask = statValue(stats, "ask");
            double[] day = range(statValue(stats, "dayRange"));
            double[] year = range(statValue(stats, "fiftyTwoWeekHighLow"));
            return Optional.of(new NordicQuote(symbol,
                    text(q, "isin"), text(q, "companyName"),
                    text(q, "exchange"), text(q, "segment"), currency,
                    last,
                    money(text(q.path("primaryData"), "netChange")),
                    money(text(q.path("primaryData"), "percentageChange")),
                    bookPrice(bid), bookPrice(ask),
                    bookSize(bid), bookSize(ask),
                    count(statValue(stats, "volume")),
                    day[1], day[0], year[1], year[0]));
        } catch (Exception e) {
            LOG.warn("[Nordic] unparseable info payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Parses the chart reply's {@code CP} branch. Package-private for tests. */
    static Optional<NordicHistory> parseHistory(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode data = MAPPER.readTree(json).path("data");
            JsonNode meta = data.path("chartData");
            String symbol = text(meta, "symbol");
            if (symbol == null) return Optional.empty();
            List<NordicHistory.NordicBar> bars = new ArrayList<>();
            for (JsonNode p : data.path("CP")) {
                JsonNode z = p.path("z");
                LocalDate date = barDate(text(z, "dateTime"));
                double close = money(text(z, "close"));
                if (date == null || !Double.isFinite(close) || close <= 0) continue;
                bars.add(new NordicHistory.NordicBar(date,
                        money(text(z, "open")), money(text(z, "high")),
                        money(text(z, "low")), close, money(text(z, "volume"))));
            }
            if (bars.isEmpty()) return Optional.empty();
            bars.sort((a, b) -> a.date().compareTo(b.date()));
            return Optional.of(new NordicHistory(symbol, text(meta, "isin"),
                    currencyOf(text(meta, "lastSalePrice")), List.copyOf(bars)));
        } catch (Exception e) {
            LOG.warn("[Nordic] unparseable chart payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static LocalDate barDate(String s) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** The {@code value} of one {@code keyStats} entry, null when absent. */
    private static String statValue(JsonNode stats, String field) {
        return text(stats.path(field), "value");
    }

    /** The leading three-letter currency of a money string, null when bare. */
    private static String currencyOf(String s) {
        if (s == null) return null;
        Matcher m = CURRENCY_PREFIX.matcher(s.trim());
        return m.find() ? m.group(1) : null;
    }

    /**
     * A currency-prefixed, comma-grouped decimal ({@code "SEK 366.40"},
     * {@code "-1.37%"}), {@code NaN} when absent/unreadable.
     */
    private static double money(String s) {
        if (s == null) return Double.NaN;
        try {
            String bare = s.replaceAll("[^0-9.\\-]", "");
            return bare.isEmpty() ? Double.NaN : Double.parseDouble(bare);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** A comma-grouped count, {@code -1} when absent/unreadable. */
    private static long count(String s) {
        double v = money(s);
        return Double.isFinite(v) && v >= 0 ? Math.round(v) : -1;
    }

    /** The price of a {@code "SEK 365.40 x 3,957"} book side. */
    private static double bookPrice(String s) {
        if (s == null) return Double.NaN;
        int x = s.indexOf(" x ");
        return money(x < 0 ? s : s.substring(0, x));
    }

    /** The size of a {@code "SEK 365.40 x 3,957"} book side. */
    private static long bookSize(String s) {
        if (s == null) return -1;
        int x = s.indexOf(" x ");
        return x < 0 ? -1 : count(s.substring(x + 3));
    }

    /** A {@code "362.50-374.50"} range as {@code [low, high]}, NaN when absent. */
    private static double[] range(String s) {
        if (s != null) {
            int dash = s.indexOf('-', 1);
            if (dash > 0) {
                return new double[]{money(s.substring(0, dash)),
                        money(s.substring(dash + 1))};
            }
        }
        return new double[]{Double.NaN, Double.NaN};
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }
}
