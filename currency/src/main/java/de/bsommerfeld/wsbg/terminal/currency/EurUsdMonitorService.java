package de.bsommerfeld.wsbg.terminal.currency;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.CurrencyConfig;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.NetConfig;
import de.bsommerfeld.wsbg.terminal.core.util.PollingMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Polls {@link EurUsdClient} every {@link CurrencyConfig#getPollIntervalSeconds()}
 * seconds (default 30) and maintains the latest {@link EurUsdQuote} in memory.
 * Listeners registered via {@link #addListener(Consumer)} are notified after
 * every successful poll, even when the rate is unchanged — the UI uses the
 * {@link EurUsdQuote#direction()} field to decide whether to flash green,
 * red, or stay white.
 *
 * <p>
 * <b>Source fallback.</b> Each tick tries the primary endpoint first
 * ({@link EurUsdClient#fetchYahoo()}); only if that returns empty does it
 * fall back to Frankfurter. The {@link EurUsdQuote#source()} field records
 * which endpoint answered, so the UI can surface a "degraded source" badge
 * when running on the fallback.
 *
 * <p>The polling scaffolding (scheduler, latest value, listener fan-out,
 * shutdown) lives in {@link PollingMonitor}; this class keeps only the
 * per-source poll strategy and interval.
 */
@Singleton
public class EurUsdMonitorService extends PollingMonitor<EurUsdQuote> {

    private static final Logger LOG = LoggerFactory.getLogger(EurUsdMonitorService.class);

    /**
     * Initial delay before the first poll: zero, so the very first fetch fires
     * immediately on the scheduler's daemon thread the moment {@code start()}
     * is called. This never blocks the DI graph (the tick runs off-thread) and
     * the fan-out is harmless even if the publisher hasn't registered its
     * listener yet — the quote is cached and the publisher re-sends it on the
     * next {@code onClientOpen}. The result is data on screen as fast as the
     * network allows instead of a fixed dead wait after boot.
     */
    private static final long INITIAL_DELAY_SECONDS = 0;

    /**
     * How long the cached ECB history stays fresh. The ECB fixes once per
     * business day (~16:00 CET), so a 6h cadence catches every new fix without
     * hammering Frankfurter from the 30s rate loop.
     */
    private static final long ECB_HISTORY_TTL_MS = 6 * 60 * 60 * 1000L;

    private final EurUsdClient client;
    private final long pollIntervalSeconds;
    private final double pollJitterPercent;

    /** Cached ~1y daily ECB series; refreshed on its own long cadence inside tick(). */
    private volatile EurUsdClient.EcbHistory ecbHistory;
    private volatile long ecbFetchedAtMs;

    public EurUsdMonitorService() {
        this(new EurUsdClient(), new CurrencyConfig());
    }

    @Inject
    public EurUsdMonitorService(EurUsdClient client, GlobalConfig config) {
        // Production (Guice) path: build WITHOUT auto-starting. The poll loop hits the
        // browser transport, which must not trigger JCEF's native init off the AWT
        // thread before the window does — that races the EDT init and deadlocks the
        // window. AppMain calls start() only after the window has brought CEF up.
        this(client, resolveCurrencyConfig(config), false,
                config == null || config.getNet() == null
                        ? new NetConfig().getPollJitterPercent()
                        : config.getNet().getPollJitterPercent());
    }

    public EurUsdMonitorService(EurUsdClient client, CurrencyConfig config) {
        this(client, config, true, new NetConfig().getPollJitterPercent());
    }

    EurUsdMonitorService(EurUsdClient client, CurrencyConfig config, boolean autoStart) {
        this(client, config, autoStart, new NetConfig().getPollJitterPercent());
    }

    /**
     * Full constructor. {@code autoStart=false} builds the service without
     * scheduling the poll loop — for tests that drive {@link #tick()} by hand
     * and must not race a zero-delay scheduled tick (which would consume their
     * stubbed return values). Production always starts.
     */
    EurUsdMonitorService(EurUsdClient client, CurrencyConfig config, boolean autoStart,
            double pollJitterPercent) {
        super("eurusd-monitor");
        this.client = client;
        // 30 s floor — the Yahoo v8/chart spot quote only refreshes ~once
        // per minute, so polling faster than this just re-fetches an
        // unchanged value.
        this.pollIntervalSeconds = Math.max(30, config.getPollIntervalSeconds());
        this.pollJitterPercent = pollJitterPercent;
        if (autoStart) start();
    }

    private static CurrencyConfig resolveCurrencyConfig(GlobalConfig config) {
        CurrencyConfig c = config == null ? null : config.getCurrency();
        return c == null ? new CurrencyConfig() : c;
    }

    /** Starts the poll loop. Idempotent — safe whether called by the ctor (tests) or AppMain. */
    public void start() {
        if (!beginStart()) return;
        LOG.info("Starting EUR/USD monitor (interval: {} seconds, jitter {}%)",
                pollIntervalSeconds, pollJitterPercent);
        // Jittered cadence (traffic blending): the first tick still fires
        // immediately, only the repeat interval varies around the base.
        scheduleTick(this::tick, INITIAL_DELAY_SECONDS, pollIntervalSeconds, pollJitterPercent);
    }

    /**
     * Single poll cycle: try Yahoo, fall back to Frankfurter, update the
     * cached quote and fan out to listeners. Catches everything so the
     * scheduled executor never sees an exception and silently stops.
     */
    void tick() {
        try {
            Optional<EurUsdClient.YahooFx> primary = client.fetchYahooDetailed();
            EurUsdQuote.Source source;
            double rate;
            if (primary.isPresent()) {
                source = EurUsdQuote.Source.YAHOO;
                rate = primary.get().rate();
            } else {
                LOG.info("Yahoo EUR/USD unavailable, falling back to Frankfurter");
                Optional<Double> fallback = client.fetchFrankfurter();
                if (fallback.isEmpty()) {
                    LOG.warn("EUR/USD: both primary and fallback failed; keeping last quote");
                    return;
                }
                source = EurUsdQuote.Source.FRANKFURTER;
                rate = fallback.get();
            }

            refreshEcbHistoryIfStale();

            EurUsdQuote prev = getCurrent().orElse(null);
            Double previousRate = prev == null ? null : prev.rate();
            EurUsdQuote next = EurUsdQuote.of(rate, previousRate, source, Instant.now(),
                    buildDetails(primary.orElse(null)));
            setCurrent(next);
            LOG.debug("EUR/USD = {} ({}, {})", next.rate(), next.source(), next.direction());

            fanOut(next);
        } catch (Exception e) {
            LOG.error("EUR/USD tick failed", e);
        }
    }

    /**
     * Assembles the widget's detail context from the Yahoo picture (may be null on
     * the Frankfurter fallback) plus the cached ECB series. The 52-week band
     * prefers Yahoo's own figure and falls back to the min/max of the ECB year.
     */
    private FxDetails buildDetails(EurUsdClient.YahooFx fx) {
        EurUsdClient.EcbHistory ecb = ecbHistory;
        Double week52High = fx == null ? null : fx.week52High();
        Double week52Low = fx == null ? null : fx.week52Low();
        if ((week52High == null || week52Low == null) && ecb != null && !ecb.points().isEmpty()) {
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
            for (double[] p : ecb.points()) { hi = Math.max(hi, p[1]); lo = Math.min(lo, p[1]); }
            if (week52High == null) week52High = hi;
            if (week52Low == null) week52Low = lo;
        }
        return new FxDetails(
                fx == null ? null : fx.previousClose(),
                fx == null ? null : fx.dayHigh(),
                fx == null ? null : fx.dayLow(),
                week52High, week52Low,
                fx == null ? null : fx.spark(),
                ecb == null ? null : ecb.points(),
                ecb == null ? null : ecb.latestRate(),
                ecb == null ? null : ecb.latestDate());
    }

    /**
     * Refreshes the ECB daily series when the cache is empty or older than
     * {@link #ECB_HISTORY_TTL_MS}. A failed refresh keeps the old series (stale
     * beats absent) and retries on the next expiry check.
     */
    private void refreshEcbHistoryIfStale() {
        long now = System.currentTimeMillis();
        if (ecbHistory != null && now - ecbFetchedAtMs < ECB_HISTORY_TTL_MS) return;
        Optional<EurUsdClient.EcbHistory> fresh = client.fetchEcbHistory();
        if (fresh.isPresent()) {
            ecbHistory = fresh.get();
            ecbFetchedAtMs = now;
            LOG.info("EUR/USD: ECB history refreshed ({} points, latest {})",
                    fresh.get().points().size(), fresh.get().latestDate());
        } else if (ecbHistory == null) {
            LOG.warn("EUR/USD: ECB history unavailable (will retry next tick)");
        } else {
            // Keep the stale series; retry in ~10 min instead of on every rate tick.
            ecbFetchedAtMs = now - ECB_HISTORY_TTL_MS + 10 * 60_000L;
        }
    }

    @Override
    protected void onListenerError(Consumer<EurUsdQuote> listener, Exception e) {
        LOG.warn("EUR/USD listener {} threw: {}", listener, e.getMessage());
    }

    /** Effective interval the scheduler is using, in seconds. */
    public long pollIntervalSeconds() {
        return pollIntervalSeconds;
    }
}
