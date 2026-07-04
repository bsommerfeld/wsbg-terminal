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

    private final EurUsdClient client;
    private final long pollIntervalSeconds;
    private final double pollJitterPercent;

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
            Optional<Double> primary = client.fetchYahoo();
            EurUsdQuote.Source source;
            double rate;
            if (primary.isPresent()) {
                source = EurUsdQuote.Source.YAHOO;
                rate = primary.get();
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

            EurUsdQuote prev = getCurrent().orElse(null);
            Double previousRate = prev == null ? null : prev.rate();
            EurUsdQuote next = EurUsdQuote.of(rate, previousRate, source, Instant.now());
            setCurrent(next);
            LOG.debug("EUR/USD = {} ({}, {})", next.rate(), next.source(), next.direction());

            fanOut(next);
        } catch (Exception e) {
            LOG.error("EUR/USD tick failed", e);
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
