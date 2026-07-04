package de.bsommerfeld.wsbg.terminal.yahoofinance;

import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetchChain;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.YahooFinanceConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.core.util.HostReachability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 *
 * <p>
 * Parsing lives in {@link YahooResponseParser}, the rate-limit circuit
 * breaker in {@link RateLimitBreaker}, and the dormant article-scrape
 * capability in {@link YahooArticleReader}; this class is transport +
 * caching + orchestration.
 */
@Singleton
public class YahooFinanceClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(YahooFinanceClient.class);

    private static final String SEARCH_URL = "https://query2.finance.yahoo.com/v1/finance/search";
    private static final String CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";
    /**
     * Batched quote+sparkline for many symbols in ONE request — the big
     * Yahoo-call saver. Returns each symbol's meta (price, prevClose, currency)
     * plus the {@code close[]} series the UI draws as a sparkline. It does NOT
     * carry day high/low, volume or 52-week range — those stay on {@link #CHART_URL}.
     */
    private static final String SPARK_URL = "https://query1.finance.yahoo.com/v7/finance/spark";
    /**
     * Symbols per spark request. Yahoo rejects large batches with HTTP 400
     * (a 40-symbol request was observed 400ing while a 2-symbol one succeeded),
     * so we keep chunks small; 10 reliably goes through. A 42-ticker cluster
     * thus costs ~5 spark calls instead of ~42 individual chart calls.
     */
    private static final int SPARK_BATCH = 10;

    /**
     * Intraday chart granularity. 5-minute candles over one day give a
     * smooth ~78-point sparkline for a full US session without bloating
     * the response.
     */
    private static final String CHART_INTERVAL = "5m";
    private static final String CHART_RANGE = "1d";

    /**
     * Host probed by the offline gate. Both {@code query1} (chart) and
     * {@code query2} (search) live behind the same Yahoo edge, so probing one
     * is a faithful proxy for "can we reach Yahoo / do we have internet".
     */
    private static final String REACHABILITY_HOST = "query1.finance.yahoo.com";
    private static final Duration REACHABILITY_PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REACHABILITY_CACHE_TTL = Duration.ofSeconds(30);

    /**
     * The fetch strategy. In production this is a {@link WebFetchChain} of
     * {@code [browser, direct]} so Yahoo's IP/429 block is cleared by the
     * embedded browser with a plain-HTTP fallback; tests/lab get a direct-only
     * chain. Rate-limit/breaker logic stays here, in the caller.
     */
    private final WebFetcher webFetcher;
    private final Duration requestTimeout;
    private final long cacheTtlSeconds;

    /**
     * A random, realistic browser User-Agent chosen once per process. Yahoo's
     * JSON endpoints bot-block bare HTTP-library agents; a single shared string
     * would be just as easy to block across every install. See {@link BrowserUserAgent}.
     */
    private final String userAgent = BrowserUserAgent.random();

    /**
     * Offline gate. When the network (or Yahoo) is unreachable, every fetch
     * short-circuits to an empty result instead of hanging for a full connect
     * timeout — Yahoo enrichment is simply skipped and the editorial pipeline
     * publishes on the cluster's own sentiment. Cached responses are still
     * served regardless, since they don't need the network.
     */
    private final HostReachability online =
            new HostReachability(REACHABILITY_HOST, 443, REACHABILITY_PROBE_TIMEOUT, REACHABILITY_CACHE_TTL);

    private final TtlCache<String, SearchResult> searchCache;
    private final TtlCache<String, MarketSnapshot> snapshotCache;

    /**
     * Rate-limit circuit breaker. A comment-heavy cluster fans out into dozens of
     * per-subject searches; once Yahoo answers one with 429 it will 429 the rest,
     * so the first 429 OPENS the breaker and every subsequent Yahoo call
     * short-circuits (no HTTP) until the cooldown passes. The caller is told via
     * {@link SearchResult#rateLimited()} so it can SKIP the subject (retry on next
     * evidence) rather than cement a wrong tickerless unit — and we never cascade
     * 48 more 429s.
     */
    private final RateLimitBreaker breaker = new RateLimitBreaker();

    /** Dormant standalone article-text scraper (not wired into the pipeline). */
    private final YahooArticleReader articleReader;

    // --- breaker delegators (package-private for tests) -------------------

    boolean breakerOpen() {
        return breaker.isOpen();
    }

    /** Yahoo status codes that mean "back off", not "not found". Package-private for testing. */
    static boolean isRateLimitStatus(int code) {
        return RateLimitBreaker.isRateLimitStatus(code);
    }

    void tripBreaker(String what, int code) {
        breaker.trip(what, code);
    }

    public YahooFinanceClient() {
        this(10, 120);
    }

    @Inject
    public YahooFinanceClient(GlobalConfig config, WebFetcher webFetcher) {
        this(resolveTimeout(config), resolveTtl(config), webFetcher);
    }

    /** Config-driven, direct-HTTP only — for tests/CLI without an injector. */
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

    /** Direct-HTTP-only variant for tests/CLI/lab (no embedded browser available). */
    public YahooFinanceClient(int requestTimeoutSeconds, long cacheTtlSeconds) {
        this(requestTimeoutSeconds, cacheTtlSeconds,
                new WebFetchChain(java.util.List.of(new DirectWebFetcher())));
    }

    public YahooFinanceClient(int requestTimeoutSeconds, long cacheTtlSeconds, WebFetcher webFetcher) {
        this.webFetcher = webFetcher;
        this.requestTimeout = Duration.ofSeconds(Math.max(2, requestTimeoutSeconds));
        this.cacheTtlSeconds = Math.max(0, cacheTtlSeconds);
        this.searchCache = new TtlCache<>(this.cacheTtlSeconds);
        this.snapshotCache = new TtlCache<>(this.cacheTtlSeconds);
        this.articleReader =
                new YahooArticleReader(this.webFetcher, this.userAgent, this.requestTimeout, this.online,
                        this.cacheTtlSeconds);
    }

    /**
     * Performs a GET via the configured {@link WebFetcher} chain (browser→direct
     * in production), applying the standard Yahoo headers. The {@code User-Agent}
     * matters only for the direct strategy; the browser sets its own.
     */
    private WebResponse httpGet(String url, String accept) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Accept", accept);
        return webFetcher.fetch(url, headers, requestTimeout);
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
        long now = Instant.now().getEpochSecond();
        TtlCache.Entry<SearchResult> cached = searchCache.getFresh(cacheKey, now);
        if (cached != null) {
            return cached.value();
        }

        // Breaker open from a recent 429 → don't even try; tell the caller so it
        // skips this subject rather than treating it as "no ticker".
        if (breakerOpen()) {
            return SearchResult.throttled();
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

            WebResponse resp = httpGet(url, "application/json");
            if (resp.status() != 200) {
                if (isRateLimitStatus(resp.status())) {
                    tripBreaker("search '" + trimmed + "'", resp.status());
                    return SearchResult.throttled();
                }
                LOG.warn("Yahoo search '{}' returned HTTP {}", trimmed, resp.status());
                return SearchResult.empty();
            }

            SearchResult parsed = parseSearch(resp.body());
            searchCache.put(cacheKey, parsed, now);
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
    public List<RawNewsItem> getNewsForSymbol(String symbol, int newsCount) {
        if (symbol == null || symbol.isBlank()) return List.of();
        return search(symbol.trim(), 1, Math.max(1, newsCount)).news();
    }

    // --- NewsSource -------------------------------------------------------

    @Override
    public String sourceName() {
        return "yahoo";
    }

    /** {@link NewsSource} view over {@link #getNewsForSymbol} — the pull-by-symbol path. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return getNewsForSymbol(symbol, limit);
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
        TtlCache.Entry<MarketSnapshot> cached = snapshotCache.getFresh(sym, now);
        if (cached != null) {
            return Optional.ofNullable(cached.value());
        }

        if (breakerOpen()) return Optional.empty();
        if (!online.isReachable()) {
            LOG.debug("Offline — skipping Yahoo chart '{}'", sym);
            return Optional.empty();
        }

        try {
            String url = CHART_URL + URLEncoder.encode(sym, StandardCharsets.UTF_8)
                    + "?interval=" + CHART_INTERVAL + "&range=" + CHART_RANGE;

            WebResponse resp = httpGet(url, "application/json");
            if (resp.status() != 200) {
                if (isRateLimitStatus(resp.status())) {
                    tripBreaker("chart '" + sym + "'", resp.status());
                } else {
                    LOG.warn("Yahoo chart '{}' returned HTTP {}", sym, resp.status());
                    // Negative cache: a 404 symbol (news relatedTickers carry raw futures
                    // contract codes like GCQ6 that v8/chart doesn't know) stays dead for
                    // the whole TTL — without this every prep re-fetched the same 404
                    // several times per minute (live: GCQ6 5x in 4 min).
                    snapshotCache.put(sym, null, now);
                }
                return Optional.empty();
            }

            MarketSnapshot snap = parseChart(resp.body());
            snapshotCache.put(sym, snap, now);
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
     * Fetches snapshots for MANY symbols at once via the spark endpoint — one
     * HTTP request instead of one per symbol. Returns a symbol → snapshot map
     * for every symbol that resolved (cache hits + this batch). Symbols already
     * fresh in the cache are served from there; symbols spark doesn't return
     * fall back to a per-symbol {@link #fetchChart}, so this never returns LESS
     * data than the one-by-one path — it just (usually) costs far fewer requests.
     *
     * <p>Spark snapshots carry price, day-change%, currency and the sparkline
     * series, but NOT day high/low, volume or 52-week range (those are
     * {@link Double#NaN}/{@code -1}); the UI's main strip + sparkline are
     * unaffected, only the hover tooltip loses those extras.
     */
    public Map<String, MarketSnapshot> fetchCharts(List<String> symbols) {
        Map<String, MarketSnapshot> out = new LinkedHashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;
        long now = Instant.now().getEpochSecond();

        LinkedHashSet<String> misses = new LinkedHashSet<>();
        for (String raw : symbols) {
            if (raw == null || raw.isBlank()) continue;
            String sym = raw.trim().toUpperCase();
            if (out.containsKey(sym) || misses.contains(sym)) continue;
            TtlCache.Entry<MarketSnapshot> c = snapshotCache.getFresh(sym, now);
            if (c != null) {
                if (c.value() != null) out.put(sym, c.value());
            } else {
                misses.add(sym);
            }
        }
        if (misses.isEmpty() || breakerOpen() || !online.isReachable()) return out;

        List<String> miss = new ArrayList<>(misses);
        int sparkHits = 0, fellBack = 0;
        for (int i = 0; i < miss.size(); i += SPARK_BATCH) {
            // Re-check per chunk: if chunk 1 tripped the 429 breaker, chunks 2..N must
            // not keep firing spark requests (that defeats the breaker's whole purpose).
            if (breakerOpen()) break;
            List<String> chunk = miss.subList(i, Math.min(miss.size(), i + SPARK_BATCH));
            Map<String, MarketSnapshot> got = fetchSparkChunk(chunk, now);
            for (String sym : chunk) {
                MarketSnapshot s = got.get(sym);
                if (s != null) {
                    sparkHits++;
                } else {
                    s = fetchChart(sym).orElse(null); // fallback (caches itself)
                    if (s != null) fellBack++;
                }
                if (s != null) out.put(sym, s);
            }
        }
        LOG.info("Spark batch: {} symbol(s) → {} via spark, {} fell back to v8/chart",
                miss.size(), sparkHits, fellBack);
        return out;
    }

    /** One spark request for a chunk of symbols. Empty map on any failure → callers fall back. */
    private Map<String, MarketSnapshot> fetchSparkChunk(List<String> chunk, long now) {
        Map<String, MarketSnapshot> out = new LinkedHashMap<>();
        try {
            String url = SPARK_URL
                    + "?symbols=" + URLEncoder.encode(String.join(",", chunk), StandardCharsets.UTF_8)
                    + "&range=" + CHART_RANGE + "&interval=" + CHART_INTERVAL;
            WebResponse resp = httpGet(url, "application/json");
            if (resp.status() != 200) {
                if (isRateLimitStatus(resp.status())) tripBreaker("spark batch", resp.status());
                else LOG.warn("Yahoo spark batch returned HTTP {}", resp.status());
                return out;
            }
            for (MarketSnapshot s : parseSpark(resp.body())) {
                String key = s.symbol().toUpperCase();
                snapshotCache.put(key, s, now);
                out.put(key, s);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.warn("Yahoo spark batch failed: {}", e.getMessage());
        }
        return out;
    }

    // --- article scrape (dormant capability, delegated) -------------------

    /**
     * Best-effort full-text fetch for a news article URL — delegates to the
     * standalone {@link YahooArticleReader}. <b>NOT wired into the editorial
     * pipeline</b>; kept so deeper article context can be switched on later.
     *
     * @return the article text, or {@link Optional#empty()} on any failure.
     */
    public Optional<String> fetchArticleText(String url) {
        return articleReader.fetchArticleText(url);
    }

    /** Heuristic readable-text extraction from HTML. Pure function — unit-tested. */
    static String extractReadableText(String html) {
        return YahooArticleReader.extractReadableText(html);
    }

    // --- parsing delegators (package-private for tests) -------------------

    SearchResult parseSearch(String body) {
        return YahooResponseParser.parseSearch(body);
    }

    MarketSnapshot parseChart(String body) {
        return YahooResponseParser.parseChart(body);
    }

    private static List<MarketSnapshot> parseSpark(String body) throws Exception {
        return YahooResponseParser.parseSpark(body);
    }

    /** Clears the in-memory caches — visible for tests. */
    void clearCache() {
        searchCache.clear();
        snapshotCache.clear();
        articleReader.clearCache();
    }

    /**
     * Result of one {@code /v1/finance/search} call. Quotes and news are
     * exposed together because the endpoint returns them in one response;
     * splitting into two methods would double the round-trips.
     */
    public record SearchResult(List<YahooQuote> quotes, List<RawNewsItem> news, boolean rateLimited) {
        public static SearchResult empty() {
            return new SearchResult(List.of(), List.of(), false);
        }

        /** Yahoo is rate-limiting (or the breaker is open) — distinct from "found nothing". */
        public static SearchResult throttled() {
            return new SearchResult(List.of(), List.of(), true);
        }
    }
}
