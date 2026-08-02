package de.bsommerfeld.wsbg.terminal.tradingview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * TradingView's screener back-end - the whole listed universe of a market in
 * ONE keyless POST ({@code scanner.tradingview.com/<market>/scan},
 * live-probed 2026-08-02).
 *
 * <p>Scale, measured live: {@code germany} answers {@code totalCount: 33678}
 * across LSX/SWB/FWB/XETR/HAN/HAM/MUN with {@code currency: EUR},
 * {@code america} answers 13401. The interesting part is not the count but
 * that the SERVER does the work: arbitrary column selection, server-side
 * filters, server-side sorting and {@code range} paging, so a caller can ask
 * "the 50 largest German caps reporting next week, with their analyst mark"
 * and pay for one request.
 *
 * <p><b>Deliberately generic.</b> No canned queries live here: the caller names
 * the market, the columns, the filters, the sort and the range
 * ({@link #scan}). A handful of hardcoded questions would be exactly the
 * curated list this house does not keep - the column NAMES below are
 * documentation of what was verified live, not a menu.
 *
 * <p><b>Header gotcha:</b> see {@link TradingViewApi} - without the
 * Origin/Referer pair this host answers 403. This is the module's only POST
 * surface, so it drives {@link java.net.http.HttpClient} directly instead of
 * the GET-only shared {@code WebFetcher} seam; the {@link JsonPoster} seam
 * keeps that swappable (and testable) all the same.
 *
 * <p><b>Answer shape:</b> {@code {"totalCount":N,"data":[{"s":"XETR:NVD",
 * "d":[…]}]}} - {@code d} is POSITIONAL, one value per requested column in
 * request order, which is why {@link ScanResult} carries the column list and
 * {@link ScanRow} resolves values by name.
 *
 * <p>Nothing in the terminal calls this today; it stands ready as the general,
 * instrument-free market door (house mandate 2026-08-02: every source is wired
 * per instrument AND generally).
 */
@Singleton
public class TradingViewScanner {

    private static final Logger LOG = LoggerFactory.getLogger(TradingViewScanner.class);

    static final String SCAN_URL = "https://scanner.tradingview.com/%s/scan";

    /** Verified live 2026-08-02: 33.678 symbols, EUR-quoted German venues. */
    public static final String MARKET_GERMANY = "germany";
    /** Verified live 2026-08-02: 13.401 symbols. */
    public static final String MARKET_AMERICA = "america";

    /**
     * Columns pinned live 2026-08-02 - handy names, not an allow-list: any
     * column the screener knows may be passed to {@link #scan}.
     */
    public static final String COL_NAME = "name";
    public static final String COL_DESCRIPTION = "description";
    public static final String COL_CLOSE = "close";
    public static final String COL_CURRENCY = "currency";
    public static final String COL_CHANGE = "change";
    public static final String COL_VOLUME = "volume";
    public static final String COL_MARKET_CAP = "market_cap_basic";
    public static final String COL_PE = "price_earnings_ttm";
    public static final String COL_SECTOR = "sector";
    /** Analyst consensus, roughly 1 (strong buy) … 5 (strong sell). */
    public static final String COL_RECOMMENDATION = "recommendation_mark";
    /** Unix SECONDS of the next scheduled report - the earnings calendar leg. */
    public static final String COL_EARNINGS_NEXT = "earnings_release_next_date";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One server-side filter. {@code operation} is the screener's own verb
     * ({@code greater}, {@code less}, {@code in_range}, {@code equal},
     * {@code nempty}, {@code match}, …); {@code right} is the comparand and
     * may be {@code null} for the argument-less ones ({@code nempty}).
     */
    public record Filter(String left, String operation, Object right) {
        public static Filter greater(String left, Number value) {
            return new Filter(left, "greater", value);
        }

        public static Filter less(String left, Number value) {
            return new Filter(left, "less", value);
        }

        /** Inclusive range - {@code right} goes out as a two-element array. */
        public static Filter inRange(String left, Number low, Number high) {
            return new Filter(left, "in_range", List.of(low, high));
        }

        /** "has a value at all" - the cheapest way to drop empty shells. */
        public static Filter notEmpty(String left) {
            return new Filter(left, "nempty", null);
        }
    }

    /** Server-side sort; {@code descending} false sorts ascending. */
    public record Sort(String sortBy, boolean descending) {}

    /**
     * One scanned instrument. {@code values} is positional - use the typed
     * accessors, which resolve a column NAME against the requested column list.
     */
    public record ScanRow(String tvSymbol, List<String> columns, List<JsonNode> values) {

        private JsonNode raw(String column) {
            int i = columns.indexOf(column);
            return i < 0 || i >= values.size() ? null : values.get(i);
        }

        /** Text value of a column, or {@code null} when absent/null. */
        public String text(String column) {
            JsonNode n = raw(column);
            if (n == null || n.isNull()) return null;
            String s = n.asText("").trim();
            return s.isEmpty() ? null : s;
        }

        /** Numeric value of a column; empty when absent, null or non-numeric. */
        public OptionalDouble number(String column) {
            JsonNode n = raw(column);
            return n == null || !n.isNumber() ? OptionalDouble.empty() : OptionalDouble.of(n.asDouble());
        }

        /**
         * A unix-SECONDS column (e.g. {@link #COL_EARNINGS_NEXT}) as an instant;
         * {@code null} when the row carries none.
         */
        public Instant instant(String column) {
            OptionalDouble v = number(column);
            return v.isPresent() ? Instant.ofEpochSecond((long) v.getAsDouble()) : null;
        }

        /** The venue part of {@code EXCHANGE:SYM} ({@code XETR}), or null. */
        public String exchange() {
            int c = tvSymbol.indexOf(':');
            return c > 0 ? tvSymbol.substring(0, c) : null;
        }

        /** The ticker part of {@code EXCHANGE:SYM} ({@code NVD}). */
        public String ticker() {
            int c = tvSymbol.indexOf(':');
            return c >= 0 ? tvSymbol.substring(c + 1) : tvSymbol;
        }
    }

    /**
     * @param totalCount how many instruments matched server-side, BEFORE paging
     * @param columns    the columns as requested, in {@code values} order
     * @param rows       the requested {@code range} slice
     */
    public record ScanResult(int totalCount, List<String> columns, List<ScanRow> rows) {
        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    /**
     * The POST seam. Returns the response body for a 200, {@code null} for any
     * other status or a transport failure - never throws at the caller.
     */
    interface JsonPoster {
        String post(String url, String jsonBody, Map<String, String> headers, Duration timeout);
    }

    private final String userAgent = BrowserUserAgent.random();
    private final JsonPoster poster;
    private final Duration requestTimeout = Duration.ofSeconds(15);

    /**
     * Production and default: plain direct HTTP. Guice needs no extra binding -
     * the scanner brings its own transport because the shared {@code WebFetcher}
     * seam is GET-only.
     */
    @Inject
    public TradingViewScanner() {
        this(new HttpClientPoster());
    }

    /** The POST seam, for tests. */
    TradingViewScanner(JsonPoster poster) {
        this.poster = poster;
    }

    /**
     * One screener query.
     *
     * @param market  {@link #MARKET_GERMANY}, {@link #MARKET_AMERICA} or any
     *                other TradingView market slug
     * @param columns the columns to fetch, in the order the values come back
     * @param filters server-side filters, ANDed; may be empty
     * @param sort    server-side sort, or {@code null} for the API's own order
     * @param from    first row index (inclusive) - the {@code range} lower bound
     * @param to      last row index (exclusive)
     * @return the page, or an empty result on any failure - never null
     */
    public ScanResult scan(String market, List<String> columns, List<Filter> filters,
                           Sort sort, int from, int to) {
        if (market == null || market.isBlank() || columns == null || columns.isEmpty()) {
            return new ScanResult(0, List.of(), List.of());
        }
        if (to <= from) return new ScanResult(0, List.copyOf(columns), List.of());
        List<String> cols = List.copyOf(columns);
        String url = String.format(SCAN_URL, market.trim().toLowerCase(Locale.ROOT));
        String body = buildRequestBody(market, cols, filters, sort, from, to);
        String answer = poster.post(url, body, TradingViewApi.headers(userAgent), requestTimeout);
        if (answer == null) {
            LOG.debug("TradingView scanner {} returned no body", market);
            return new ScanResult(0, cols, List.of());
        }
        ScanResult parsed = parse(answer, cols);
        return parsed == null ? new ScanResult(0, cols, List.of()) : parsed;
    }

    /** Convenience: the first {@code limit} rows, unfiltered, in API order. */
    public ScanResult scan(String market, List<String> columns, int limit) {
        return scan(market, columns, List.of(), null, 0, Math.max(limit, 0));
    }

    /**
     * Builds the screener POST body. Package-private for tests - the shape is
     * the contract: {@code columns}, {@code filter[]}, {@code sort},
     * {@code range:[from,to]}, {@code markets[]}.
     */
    static String buildRequestBody(String market, List<String> columns, List<Filter> filters,
                                   Sort sort, int from, int to) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode cols = root.putArray("columns");
        for (String c : columns) cols.add(c);

        ArrayNode filterNode = root.putArray("filter");
        if (filters != null) {
            for (Filter f : filters) {
                if (f == null || f.left() == null || f.operation() == null) continue;
                ObjectNode fn = filterNode.addObject();
                fn.put("left", f.left());
                fn.put("operation", f.operation());
                if (f.right() != null) fn.set("right", JSON.valueToTree(f.right()));
            }
        }
        if (sort != null && sort.sortBy() != null && !sort.sortBy().isBlank()) {
            ObjectNode s = root.putObject("sort");
            s.put("sortBy", sort.sortBy());
            s.put("sortOrder", sort.descending() ? "desc" : "asc");
        }
        ArrayNode range = root.putArray("range");
        range.add(from);
        range.add(to);
        root.putArray("markets").add(market);
        return root.toString();
    }

    /**
     * The scanner answer → rows. {@code d} is positional against the REQUESTED
     * columns, so the column list has to be handed in. Garbage or a body
     * without a {@code data} array yields {@code null}. Package-private for tests.
     */
    static ScanResult parse(String body, List<String> columns) {
        if (body == null || body.isBlank()) return null;
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
        JsonNode data = root.path("data");
        if (!data.isArray()) return null;
        List<ScanRow> rows = new ArrayList<>();
        for (JsonNode n : data) {
            String s = n.path("s").asText("").trim();
            if (s.isEmpty()) continue;
            List<JsonNode> values = new ArrayList<>();
            for (JsonNode v : n.path("d")) values.add(v);
            rows.add(new ScanRow(s, columns, List.copyOf(values)));
        }
        return new ScanResult(root.path("totalCount").asInt(0), columns, List.copyOf(rows));
    }

    /** The default {@link JsonPoster} - one shared {@link HttpClient}, no cookies. */
    static final class HttpClientPoster implements JsonPoster {

        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public String post(String url, String jsonBody, Map<String, String> headers, Duration timeout) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
                headers.forEach(b::header);
                HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) return resp.body();
                LOG.debug("TradingView scanner answered status {}", resp.statusCode());
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                LOG.debug("TradingView scanner POST failed: {}", e.getMessage());
            }
            return null;
        }
    }
}
