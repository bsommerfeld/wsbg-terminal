package de.bsommerfeld.wsbg.terminal.yahoofinance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.YahooFinanceConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.util.HostReachability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin client for Yahoo Finance's unauthenticated JSON endpoints:
 * {@code /v1/finance/search} (quote + news in one shot) and
 * {@code /v8/finance/chart/{symbol}} (live quote snapshot + intraday
 * price series in one shot).
 *
 * <p>
 * Both endpoints are undocumented but stable in practice — yfinance and a
 * dozen other libraries depend on them. No crumb/cookie dance is required,
 * just a browser-shaped User-Agent. (The richer {@code /v10/quoteSummary}
 * and {@code /v7/quote} endpoints are deliberately avoided — since Yahoo's
 * 2024 lockdown they hard-require a cookie+crumb session and 401 on every
 * unauthenticated call. {@code /v8/chart} carries the same scalar quote
 * fields in its {@code meta} block without that handshake.)
 *
 * <p>
 * The client caches successful responses for {@link #cacheTtlSeconds} so a
 * tick that calls {@code lookupTicker} (search) and then fetches the chart
 * for the same symbol — first to surface live numbers to the agent, then
 * again at publish time to attach the snapshot to the headline — does not
 * hit the network twice. The cache is bounded only by how many distinct
 * queries the agent issues per process lifetime; for the editorial loop
 * that's a handful per hour, so we don't bother with eviction beyond TTL.
 *
 * <p>
 * On any failure (network, non-200, parse error) the methods return an
 * empty result and log a warning — callers should treat "no result" as
 * "Yahoo couldn't tell us", not as an exceptional state.
 */
@Singleton
public class YahooFinanceClient {

    private static final Logger LOG = LoggerFactory.getLogger(YahooFinanceClient.class);

    private static final String SEARCH_URL = "https://query2.finance.yahoo.com/v1/finance/search";
    private static final String CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";

    /**
     * Intraday chart granularity. 5-minute candles over one day give a
     * smooth ~78-point sparkline for a full US session without bloating
     * the response. {@link #SPARK_MAX_POINTS} caps it further for the UI.
     */
    private static final String CHART_INTERVAL = "5m";
    private static final String CHART_RANGE = "1d";

    /**
     * Upper bound on sparkline points handed to the UI. The intraday
     * series is downsampled to at most this many evenly-spaced points —
     * enough for a legible micro-chart, small enough to keep the headline
     * payload tidy when many rows each carry a snapshot.
     */
    private static final int SPARK_MAX_POINTS = 48;

    /** Upper bound on extracted article body length — keeps a prompt block sane. */
    private static final int ARTICLE_MAX_CHARS = 6000;
    private static final Pattern SCRIPT_STYLE =
            Pattern.compile("(?is)<(script|style|noscript|template|svg)[^>]*>.*?</\\1>");
    private static final Pattern PARAGRAPH = Pattern.compile("(?is)<p[^>]*>(.*?)</p>");
    private static final Pattern HTML_TAG = Pattern.compile("(?is)<[^>]+>");

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Host probed by the offline gate. Both {@code query1} (chart) and
     * {@code query2} (search) live behind the same Yahoo edge, so probing one
     * is a faithful proxy for "can we reach Yahoo / do we have internet".
     */
    private static final String REACHABILITY_HOST = "query1.finance.yahoo.com";
    private static final Duration REACHABILITY_PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REACHABILITY_CACHE_TTL = Duration.ofSeconds(30);

    private final HttpClient http;
    private final Duration requestTimeout;
    private final long cacheTtlSeconds;

    /**
     * Offline gate. When the network (or Yahoo) is unreachable, every fetch
     * short-circuits to an empty result instead of hanging for a full connect
     * timeout — Yahoo enrichment is simply skipped and the editorial pipeline
     * publishes on the cluster's own sentiment. Cached responses are still
     * served regardless, since they don't need the network.
     */
    private final HostReachability online =
            new HostReachability(REACHABILITY_HOST, 443, REACHABILITY_PROBE_TIMEOUT, REACHABILITY_CACHE_TTL);

    private final Map<String, CachedSearch> searchCache = new ConcurrentHashMap<>();
    private final Map<String, CachedSnapshot> snapshotCache = new ConcurrentHashMap<>();
    private final Map<String, CachedArticle> articleCache = new ConcurrentHashMap<>();

    public YahooFinanceClient() {
        this(10, 120);
    }

    @Inject
    public YahooFinanceClient(GlobalConfig config) {
        this(resolveTimeout(config), resolveTtl(config));
    }

    private static int resolveTimeout(GlobalConfig config) {
        YahooFinanceConfig y = config == null ? null : config.getYahoo();
        return y == null ? 10 : y.getRequestTimeoutSeconds();
    }

    private static long resolveTtl(GlobalConfig config) {
        YahooFinanceConfig y = config == null ? null : config.getYahoo();
        return y == null ? 120 : y.getCacheTtlSeconds();
    }

    public YahooFinanceClient(int requestTimeoutSeconds, long cacheTtlSeconds) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL) // article links 30x-redirect to the publisher
                .connectTimeout(Duration.ofSeconds(Math.max(2, requestTimeoutSeconds)))
                .build();
        this.requestTimeout = Duration.ofSeconds(Math.max(2, requestTimeoutSeconds));
        this.cacheTtlSeconds = Math.max(0, cacheTtlSeconds);
    }

    /**
     * Searches Yahoo Finance for tickers + news matching the query.
     *
     * @param query        free-text — name, partial name, or ticker guess
     * @param quotesCount  cap on quote results (Yahoo allows up to ~10)
     * @param newsCount    cap on news results
     * @return result tuple; both lists are empty on failure
     */
    public SearchResult search(String query, int quotesCount, int newsCount) {
        if (query == null || query.isBlank()) {
            return SearchResult.empty();
        }
        String trimmed = query.trim();
        String cacheKey = trimmed + "|" + quotesCount + "|" + newsCount;
        CachedSearch cached = searchCache.get(cacheKey);
        long now = Instant.now().getEpochSecond();
        if (cached != null && now - cached.storedAt < cacheTtlSeconds) {
            return cached.result;
        }

        if (!online.isReachable()) {
            LOG.debug("Offline — skipping Yahoo search '{}'", trimmed);
            return SearchResult.empty();
        }

        try {
            String url = SEARCH_URL
                    + "?q=" + URLEncoder.encode(trimmed, StandardCharsets.UTF_8)
                    + "&quotesCount=" + Math.max(1, quotesCount)
                    + "&newsCount=" + Math.max(0, newsCount)
                    + "&enableFuzzyQuery=false"
                    + "&quotesQueryId=tss_match_phrase_query"
                    + "&newsQueryId=news_cie_vespa";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(requestTimeout)
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warn("Yahoo search '{}' returned HTTP {}", trimmed, resp.statusCode());
                return SearchResult.empty();
            }

            SearchResult parsed = parseSearch(resp.body());
            searchCache.put(cacheKey, new CachedSearch(parsed, now));
            return parsed;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SearchResult.empty();
        } catch (Exception e) {
            LOG.warn("Yahoo search '{}' failed: {}", trimmed, e.getMessage());
            return SearchResult.empty();
        }
    }

    /**
     * Same as {@link #search(String, int, int)} with news count 0 — the
     * typical {@code lookupTicker} path that only needs quote matches.
     */
    public List<YahooQuote> searchQuotes(String query, int quotesCount) {
        return search(query, quotesCount, 0).quotes();
    }

    /**
     * Fetches the top news items for a specific Yahoo symbol. Implemented
     * via the same search endpoint — passing the symbol as the query
     * yields the symbol itself as the top quote and the symbol's news in
     * the {@code news} array.
     */
    public List<YahooNewsItem> getNewsForSymbol(String symbol, int newsCount) {
        if (symbol == null || symbol.isBlank()) return List.of();
        return search(symbol.trim(), 1, Math.max(1, newsCount)).news();
    }

    /**
     * Fetches a live {@link MarketSnapshot} for one symbol from the
     * {@code v8/chart} endpoint — current price, day move, day range,
     * 52-week range, volume, and the intraday close series for a
     * sparkline, all in a single request.
     *
     * <p>
     * Cached per upper-cased symbol for {@link #cacheTtlSeconds}; repeat
     * calls inside the TTL (the {@code lookupTicker} → {@code publishHeadline}
     * path hits the same symbol twice) are free.
     *
     * @return the snapshot, or {@link Optional#empty()} on any failure —
     *         the headline still publishes, just without a quote strip.
     */
    public Optional<MarketSnapshot> fetchChart(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        String sym = symbol.trim().toUpperCase();
        long now = Instant.now().getEpochSecond();
        CachedSnapshot cached = snapshotCache.get(sym);
        if (cached != null && now - cached.storedAt < cacheTtlSeconds) {
            return Optional.ofNullable(cached.snapshot);
        }

        if (!online.isReachable()) {
            LOG.debug("Offline — skipping Yahoo chart '{}'", sym);
            return Optional.empty();
        }

        try {
            String url = CHART_URL + URLEncoder.encode(sym, StandardCharsets.UTF_8)
                    + "?interval=" + CHART_INTERVAL + "&range=" + CHART_RANGE;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(requestTimeout)
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warn("Yahoo chart '{}' returned HTTP {}", sym, resp.statusCode());
                return Optional.empty();
            }

            MarketSnapshot snap = parseChart(resp.body());
            snapshotCache.put(sym, new CachedSnapshot(snap, now));
            return Optional.ofNullable(snap);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOG.warn("Yahoo chart '{}' failed: {}", sym, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Best-effort full-text fetch for a news article URL (the {@code link} on a
     * {@link YahooNewsItem}). Follows the {@code finance.yahoo.com/m/…} redirect
     * to the publisher, strips boilerplate, and returns readable body text
     * capped at {@link #ARTICLE_MAX_CHARS}.
     *
     * <p><b>Standalone capability — NOT wired into the editorial pipeline.</b>
     * The resolver still hands the model only headline titles; this exists so
     * deeper article context can be switched on later without new plumbing.
     * Publisher HTML is heterogeneous, so extraction is heuristic (prefer
     * {@code <p>} text); treat a result as "best effort", never authoritative.
     *
     * @return the article text, or {@link Optional#empty()} on any failure
     *         (network, non-200, nothing readable extracted).
     */
    public Optional<String> fetchArticleText(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        String key = url.trim();
        long now = Instant.now().getEpochSecond();
        CachedArticle cached = articleCache.get(key);
        if (cached != null && now - cached.storedAt < cacheTtlSeconds) {
            return Optional.ofNullable(cached.text);
        }
        if (!online.isReachable()) {
            LOG.debug("Offline — skipping Yahoo article fetch '{}'", key);
            return Optional.empty();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(key))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .timeout(requestTimeout)
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warn("Yahoo article fetch '{}' returned HTTP {}", key, resp.statusCode());
                return Optional.empty();
            }
            String text = extractReadableText(resp.body());
            articleCache.put(key, new CachedArticle(text.isEmpty() ? null : text, now));
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOG.warn("Yahoo article fetch '{}' failed: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Heuristic readable-text extraction from an HTML page. Drops
     * script/style/svg blocks, prefers paragraph (<{@code p}>) text to skip
     * nav/menu boilerplate, unescapes the common entities, collapses
     * whitespace, and caps length. Pure function — unit-tested.
     */
    static String extractReadableText(String html) {
        if (html == null || html.isBlank()) return "";
        String cleaned = SCRIPT_STYLE.matcher(html).replaceAll(" ");

        // Paragraph text first: it skips headers, nav, captions and most chrome.
        StringBuilder paras = new StringBuilder();
        Matcher pm = PARAGRAPH.matcher(cleaned);
        while (pm.find()) {
            String t = stripTags(pm.group(1)).trim();
            if (t.length() >= 40) paras.append(t).append(' ');
        }

        String text = paras.length() >= 200 ? paras.toString() : stripTags(cleaned);
        text = unescapeEntities(text).replaceAll("\\s+", " ").trim();
        if (text.length() > ARTICLE_MAX_CHARS) {
            text = text.substring(0, ARTICLE_MAX_CHARS).trim() + "…";
        }
        return text;
    }

    private static String stripTags(String s) {
        return s == null ? "" : HTML_TAG.matcher(s).replaceAll(" ");
    }

    private static String unescapeEntities(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&apos;", "'").replace("&nbsp;", " ").replace("&hellip;", "…")
                .replace("&mdash;", "—").replace("&ndash;", "–")
                .replace("&rsquo;", "'").replace("&lsquo;", "'")
                .replace("&ldquo;", "\"").replace("&rdquo;", "\"");
    }

    // --- parsing -------------------------------------------------------

    SearchResult parseSearch(String body) {
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
                                    ? q.path("regularMarketPercentChange").asDouble() : Double.NaN));
                }
            }

            List<YahooNewsItem> news = new ArrayList<>();
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
                    news.add(new YahooNewsItem(
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
                    Collections.unmodifiableList(news));
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
    MarketSnapshot parseChart(String body) {
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

    /** Clears the in-memory caches — visible for tests. */
    void clearCache() {
        searchCache.clear();
        snapshotCache.clear();
        articleCache.clear();
    }

    /**
     * Result of one {@code /v1/finance/search} call. Quotes and news are
     * exposed together because the endpoint returns them in one response;
     * splitting into two methods would double the round-trips.
     */
    public record SearchResult(List<YahooQuote> quotes, List<YahooNewsItem> news) {
        public static SearchResult empty() {
            return new SearchResult(List.of(), List.of());
        }
    }

    private record CachedSearch(SearchResult result, long storedAt) {
    }

    /** Cached chart snapshot; {@code snapshot} may be null (parse miss). */
    private record CachedSnapshot(MarketSnapshot snapshot, long storedAt) {
    }

    /** Cached article body; {@code text} may be null (nothing extracted). */
    private record CachedArticle(String text, long storedAt) {
    }
}
