package de.bsommerfeld.wsbg.terminal.web.impl.sources.yahoofinance;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.util.HostReachability;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
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
 * Thin client for Yahoo Finance's unauthenticated MARKET-DATA endpoints:
 * {@code /v1/finance/search} (quote resolution + news in one shot),
 * {@code /v8/finance/chart/{symbol}} (live quote snapshot + intraday
 * price series in one shot), {@code /v7/finance/spark} (batched snapshots),
 * plus the predefined screener and trending lists. The pure NEWS legs live
 * in {@code YahooFinanceSource}; this client carries the market plumbing
 * (price chain, ticker resolution, market memory, movers).
 *
 * <p>
 * The endpoints are undocumented but stable in practice — yfinance and a
 * dozen other libraries depend on them. No crumb/cookie dance is required,
 * just a browser-shaped User-Agent (the transport's own). (The richer
 * {@code /v10/quoteSummary} and {@code /v7/quote} endpoints are deliberately
 * avoided — since Yahoo's 2024 lockdown they hard-require a cookie+crumb
 * session and 401 on every unauthenticated call. {@code /v8/chart} carries
 * the same scalar quote fields in its {@code meta} block without that
 * handshake.)
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
 * Parsing lives in {@link YahooResponseParser}; this class is transport +
 * caching + orchestration. The old client's own rate-limit circuit breaker
 * (first 429 opened it, everything short-circuited for 90 s) is GONE: the
 * house fetcher owns the host cooldown after a 429/503/999, and this client
 * stays polite by skipping outright — and telling the caller via
 * {@link SearchResult#rateLimited()} — while {@code hostCoolingDown()} says
 * Yahoo is resting.
 */
@Singleton
public class YahooMarketClient {

    private static final Logger LOG = LoggerFactory.getLogger(YahooMarketClient.class);

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

    /** The old binding rode the unannotated house chain: browser first, direct fallback. */
    private static final FetchUtil[] MODES = {FetchUtil.BROWSER, FetchUtil.DIRECT};

    private final WebFetcher fetcher;
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

    private final TtlCache<String, SearchResult> searchCache;
    private final TtlCache<String, MarketSnapshot> snapshotCache;

    /** Yahoo status codes that mean "back off", not "not found". Package-private for testing. */
    static boolean isRateLimitStatus(int code) {
        return code == 429 || code == 503 || code == 999;
    }

    /**
     * A recent 429 put Yahoo in the house fetcher's host cooldown — the polite
     * skip that replaced the old client-local circuit breaker.
     */
    private boolean coolingDown(String url) {
        return fetcher.hostCoolingDown(url);
    }

    /** Production seam: fixed defaults (10 s timeout, 120 s cache TTL — the old config defaults). */
    @Inject
    public YahooMarketClient(WebFetcher fetcher) {
        this(fetcher, 10, 120);
    }

    public YahooMarketClient(WebFetcher fetcher, int requestTimeoutSeconds, long cacheTtlSeconds) {
        this.fetcher = fetcher;
        this.requestTimeout = Duration.ofSeconds(Math.max(2, requestTimeoutSeconds));
        this.cacheTtlSeconds = Math.max(0, cacheTtlSeconds);
        this.searchCache = new TtlCache<>(this.cacheTtlSeconds);
        this.snapshotCache = new TtlCache<>(this.cacheTtlSeconds);
    }

    /**
     * Performs a GET via the house {@link WebFetcher} (browser→direct),
     * applying the standard Yahoo Accept header. The User-Agent is the
     * transport's own — set centrally, never here.
     */
    private WebResponse httpGet(String url, String accept) throws Exception {
        return fetcher.fetch(url, Map.of("Accept", accept), requestTimeout, MODES);
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

        // Yahoo is resting in the fetcher's cooldown after a recent 429 → don't
        // even try; tell the caller so it skips this subject rather than
        // treating it as "no ticker".
        if (coolingDown(SEARCH_URL)) {
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
                    // The fetcher registered the cooldown; report the throttle upstream.
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

        if (coolingDown(CHART_URL)) return Optional.empty();
        if (!online.isReachable()) {
            LOG.debug("Offline — skipping Yahoo chart '{}'", sym);
            return Optional.empty();
        }

        try {
            String url = CHART_URL + URLEncoder.encode(sym, StandardCharsets.UTF_8)
                    + "?interval=" + CHART_INTERVAL + "&range=" + CHART_RANGE;

            WebResponse resp = httpGet(url, "application/json");
            if (resp.status() != 200) {
                if (!isRateLimitStatus(resp.status())) {
                    LOG.warn("Yahoo chart '{}' returned HTTP {}", sym, resp.status());
                    // Negative cache: a 404 symbol (news relatedTickers carry raw futures
                    // contract codes like GCQ6 that v8/chart doesn't know) stays dead for
                    // the whole TTL — without this every prep re-fetched the same 404
                    // several times per minute (live: GCQ6 5x in 4 min).
                    snapshotCache.put(sym, null, now);
                }
                // Rate-limit statuses: the fetcher owns the cooldown; no negative cache.
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
     * The full DAILY history of one symbol from {@code from} to today, oldest
     * first — the market memory's price axis. Deliberately NOT {@code
     * range=max}: that reply is capped/thinned (live-probed 2026-07-14 — IBM
     * answered 260 points), and {@code period1=0} cuts at the Unix epoch. An
     * explicit — possibly NEGATIVE — {@code period1} unlocks the real floor
     * (^GSPC daily back to 1927, US names to 1962, German names to the Xetra
     * era). Uncached (one-shot per enrichment sweep; callers batch per run);
     * cooldown/offline gates as everywhere. Empty on any failure.
     */
    public List<Bar> fetchDailyBars(String symbol, java.time.LocalDate from) {
        return fetchBars(symbol, "interval=1d&period1="
                + from.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
                + "&period2=" + Instant.now().getEpochSecond());
    }

    /**
     * Hourly bars over the trailing {@code rangeDays} (Yahoo serves 60m up to
     * ~730 days) — the volume profile's input. Empty on any failure.
     */
    public List<Bar> fetchHourlyBars(String symbol, int rangeDays) {
        return fetchBars(symbol, "interval=60m&range=" + Math.max(1, rangeDays) + "d");
    }

    private List<Bar> fetchBars(String symbol, String query) {
        if (symbol == null || symbol.isBlank()) return List.of();
        String sym = symbol.trim().toUpperCase();
        if (coolingDown(CHART_URL)) return List.of();
        if (!online.isReachable()) {
            LOG.debug("Offline — skipping Yahoo bars '{}'", sym);
            return List.of();
        }
        try {
            String url = CHART_URL + URLEncoder.encode(sym, StandardCharsets.UTF_8) + "?" + query;
            WebResponse resp = httpGet(url, "application/json");
            if (resp.status() != 200) {
                if (!isRateLimitStatus(resp.status())) {
                    LOG.warn("Yahoo bars '{}' returned HTTP {}", sym, resp.status());
                }
                return List.of();
            }
            return YahooResponseParser.parseBars(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            LOG.warn("Yahoo bars '{}' failed: {}", sym, e.getMessage());
            return List.of();
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
        if (misses.isEmpty() || coolingDown(SPARK_URL) || !online.isReachable()) return out;

        List<String> miss = new ArrayList<>(misses);
        int sparkHits = 0, fellBack = 0;
        for (int i = 0; i < miss.size(); i += SPARK_BATCH) {
            // Re-check per chunk: if chunk 1 put Yahoo in the fetcher's cooldown,
            // chunks 2..N must not keep firing spark requests (that defeats the
            // cooldown's whole purpose).
            if (coolingDown(SPARK_URL)) break;
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
                if (!isRateLimitStatus(resp.status())) {
                    LOG.warn("Yahoo spark batch returned HTTP {}", resp.status());
                }
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

    /**
     * One of Yahoo's predefined screener lists ({@code day_gainers},
     * {@code day_losers}, {@code most_actives}) — the day's US movers,
     * quantitative and keyless (verified NOT crumb-locked 2026-07-13, unlike
     * the {@code v7/quote} family). {@code total} is the uncapped hit count,
     * which doubles as a crude market-breadth proxy (how many names moved
     * hard enough to qualify at all).
     */
    public ScreenerResult fetchScreener(String scrIds, int count) {
        if (scrIds == null || scrIds.isBlank()) return ScreenerResult.empty();
        String url = "https://query1.finance.yahoo.com/v1/finance/screener/predefined/saved"
                + "?scrIds=" + URLEncoder.encode(scrIds.trim(), StandardCharsets.UTF_8)
                + "&count=" + Math.max(1, count);
        if (coolingDown(url) || !online.isReachable()) return ScreenerResult.empty();
        try {
            WebResponse resp = httpGet(url, "application/json");
            if (resp.status() != 200) {
                if (!isRateLimitStatus(resp.status())) {
                    LOG.warn("Yahoo screener '{}' returned HTTP {}", scrIds, resp.status());
                }
                return ScreenerResult.empty();
            }
            return YahooResponseParser.parseScreener(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ScreenerResult.empty();
        } catch (Exception e) {
            LOG.warn("Yahoo screener '{}' failed: {}", scrIds, e.getMessage());
            return ScreenerResult.empty();
        }
    }

    /** Yahoo's trending-symbols list for a region ("US") — bare symbols, no quotes. */
    public List<String> fetchTrending(String region, int count) {
        String url = "https://query1.finance.yahoo.com/v1/finance/trending/"
                + URLEncoder.encode(region == null || region.isBlank() ? "US" : region.trim(),
                        StandardCharsets.UTF_8)
                + "?count=" + Math.max(1, count);
        if (coolingDown(url) || !online.isReachable()) return List.of();
        try {
            WebResponse resp = httpGet(url, "application/json");
            if (resp.status() != 200) {
                if (!isRateLimitStatus(resp.status())) {
                    LOG.warn("Yahoo trending returned HTTP {}", resp.status());
                }
                return List.of();
            }
            return YahooResponseParser.parseTrending(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            LOG.warn("Yahoo trending failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** One screener row; NaN/-1 where Yahoo omitted a field. */
    public record ScreenerQuote(String symbol, String name, double price,
            double changePercent, long marketCap, long volume) {
    }

    /** A screener page plus the uncapped total hit count (-1 unknown). */
    public record ScreenerResult(List<ScreenerQuote> quotes, int total) {
        public static ScreenerResult empty() {
            return new ScreenerResult(List.of(), -1);
        }
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

    /**
     * Result of one {@code /v1/finance/search} call. Quotes and news are
     * exposed together because the endpoint returns them in one response;
     * splitting into two methods would double the round-trips.
     */
    public record SearchResult(List<YahooQuote> quotes, List<Article> news, boolean rateLimited) {
        public static SearchResult empty() {
            return new SearchResult(List.of(), List.of(), false);
        }

        /** Yahoo is rate-limiting (the fetcher's cooldown is active) — distinct from "found nothing". */
        public static SearchResult throttled() {
            return new SearchResult(List.of(), List.of(), true);
        }
    }
}
