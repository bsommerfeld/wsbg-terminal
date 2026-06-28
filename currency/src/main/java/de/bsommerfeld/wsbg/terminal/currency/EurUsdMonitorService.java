package de.bsommerfeld.wsbg.terminal.currency;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.CurrencyConfig;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>
 * <b>UI integration TODO.</b> This service is intentionally stand-alone — it
 * does not yet emit on the {@code ApplicationEventBus} and is not wired into
 * {@code AppModule}. To complete the UI integration:
 *
 * <ol>
 *   <li>Register {@code EurUsdMonitorService} as an eager singleton in
 *       {@code terminal/AppModule} so the poll loop starts at boot.</li>
 *   <li>In the JCEF bridge (the layer that already pushes headlines and
 *       FinancialJuice news to the frontend over the websocket), call
 *       {@link #addListener(Consumer)} during construction and forward each
 *       {@link EurUsdQuote} as a JSON message, e.g.
 *       {@code {"type":"eurusd","rate":1.0876,"direction":"UP","source":"YAHOO","fetchedAt":...}}.</li>
 *   <li>On the frontend, render the rate in a small badge styled like the
 *       FinancialJuice ticker. On every push, briefly apply
 *       {@code .flash-up} (green background, ~600&nbsp;ms ease-out) when
 *       {@code direction === "UP"}, {@code .flash-down} (red) on
 *       {@code "DOWN"}, and leave the white/neutral default on
 *       {@code "NEUTRAL"}. Match the timing FinancialJuice and TradingView
 *       use — a single quick flash, no sustained colour change. Suggested
 *       CSS:
 *       <pre>{@code
 *       .eurusd { background: white; transition: background 600ms ease-out; }
 *       .eurusd.flash-up   { background: #1fbf75; }
 *       .eurusd.flash-down { background: #e84d4d; }
 *       }</pre>
 *       (add the class on push, remove it after ~600&nbsp;ms via
 *       {@code setTimeout} so the transition decays back to white).</li>
 *   <li>If {@link EurUsdQuote#source()} is {@link EurUsdQuote.Source#FRANKFURTER},
 *       show a small badge ("ECB ref / delayed") so the user knows the
 *       intraday feed is unavailable.</li>
 * </ol>
 *
 * Until those steps are done the service still works — it just polls and
 * holds the latest quote in {@link #getCurrent()}, ready to be wired up.
 */
@Singleton
public class EurUsdMonitorService {

    private static final Logger LOG = LoggerFactory.getLogger(EurUsdMonitorService.class);

    /**
     * Initial delay before the first poll: zero, so the very first fetch fires
     * immediately on the scheduler's daemon thread the moment the service is
     * constructed. This never blocks the DI graph (the tick runs off-thread) and
     * the fan-out is harmless even if the publisher hasn't registered its
     * listener yet — the quote is cached in {@link #current} and the publisher
     * re-sends it on the next {@code onClientOpen}. The result is data on screen
     * as fast as the network allows instead of a fixed dead wait after boot.
     */
    private static final long INITIAL_DELAY_SECONDS = 0;

    private final EurUsdClient client;
    private final long pollIntervalSeconds;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "eurusd-monitor");
                t.setDaemon(true);
                return t;
            });

    private final AtomicReference<EurUsdQuote> current = new AtomicReference<>(null);
    private final List<Consumer<EurUsdQuote>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    public EurUsdMonitorService() {
        this(new EurUsdClient(), new CurrencyConfig());
    }

    @Inject
    public EurUsdMonitorService(EurUsdClient client, GlobalConfig config) {
        // Production (Guice) path: build WITHOUT auto-starting. The poll loop hits the
        // browser transport, which must not trigger JCEF's native init off the AWT
        // thread before the window does — that races the EDT init and deadlocks the
        // window. AppMain calls start() only after the window has brought CEF up.
        this(client, resolveCurrencyConfig(config), false);
    }

    public EurUsdMonitorService(EurUsdClient client, CurrencyConfig config) {
        this(client, config, true);
    }

    /**
     * Full constructor. {@code autoStart=false} builds the service without
     * scheduling the poll loop — for tests that drive {@link #tick()} by hand
     * and must not race a zero-delay scheduled tick (which would consume their
     * stubbed return values). Production always starts.
     */
    EurUsdMonitorService(EurUsdClient client, CurrencyConfig config, boolean autoStart) {
        this.client = client;
        // 30 s floor — the Yahoo v8/chart spot quote only refreshes ~once
        // per minute, so polling faster than this just re-fetches an
        // unchanged value.
        this.pollIntervalSeconds = Math.max(30, config.getPollIntervalSeconds());
        if (autoStart) start();
    }

    private static CurrencyConfig resolveCurrencyConfig(GlobalConfig config) {
        CurrencyConfig c = config == null ? null : config.getCurrency();
        return c == null ? new CurrencyConfig() : c;
    }

    /** Starts the poll loop. Idempotent — safe whether called by the ctor (tests) or AppMain. */
    public void start() {
        if (!started.compareAndSet(false, true)) return;
        LOG.info("Starting EUR/USD monitor (interval: {} seconds)", pollIntervalSeconds);
        scheduler.scheduleAtFixedRate(this::tick,
                INITIAL_DELAY_SECONDS, pollIntervalSeconds, TimeUnit.SECONDS);
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

            EurUsdQuote prev = current.get();
            Double previousRate = prev == null ? null : prev.rate();
            EurUsdQuote next = EurUsdQuote.of(rate, previousRate, source, Instant.now());
            current.set(next);
            LOG.debug("EUR/USD = {} ({}, {})", next.rate(), next.source(), next.direction());

            for (Consumer<EurUsdQuote> l : listeners) {
                try {
                    l.accept(next);
                } catch (Exception e) {
                    LOG.warn("EUR/USD listener {} threw: {}", l, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.error("EUR/USD tick failed", e);
        }
    }

    /** Most recent successful quote, or empty if no poll has succeeded yet. */
    public Optional<EurUsdQuote> getCurrent() {
        return Optional.ofNullable(current.get());
    }

    /**
     * Registers a listener that fires on every successful poll. Listeners
     * are invoked on the monitor's scheduler thread — keep them cheap or
     * dispatch to your own executor.
     */
    public void addListener(Consumer<EurUsdQuote> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<EurUsdQuote> listener) {
        listeners.remove(listener);
    }

    /** Effective interval the scheduler is using, in seconds. */
    public long pollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    /** Stops the scheduler. Intended for test teardown / shutdown hooks. */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
