package de.bsommerfeld.wsbg.terminal.feargreed;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.NetConfig;
import de.bsommerfeld.wsbg.terminal.core.util.JitteredScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Polls {@link FearGreedClient} and holds the latest {@link FearGreedIndex} in
 * memory, fanning each new reading out to listeners (the websocket publisher).
 * The index only moves a few points across a day, so the cadence is slow
 * ({@value #POLL_INTERVAL_SECONDS}s) — polling faster just re-fetches an
 * unchanged value. Mirrors {@code EurUsdMonitorService}.
 */
@Singleton
public class FearGreedMonitorService {

    private static final Logger LOG = LoggerFactory.getLogger(FearGreedMonitorService.class);

    /**
     * Zero initial delay: the first fetch fires immediately on the scheduler's
     * daemon thread at construction. It runs off-thread (never blocks the DI
     * graph) and the fan-out is harmless if the publisher's listener isn't wired
     * yet — the reading is cached in {@link #current} and re-sent on the next
     * {@code onClientOpen}. So the gauge fills as soon as CNN answers instead of
     * waiting out a fixed delay after boot.
     */
    private static final long INITIAL_DELAY_SECONDS = 0;
    /** 5 min — CNN refreshes the composite only periodically through the session. */
    private static final long POLL_INTERVAL_SECONDS = 300;

    private final FearGreedClient client;
    private final double pollJitterPercent;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fear-greed-monitor");
                t.setDaemon(true);
                return t;
            });

    private final AtomicReference<FearGreedIndex> current = new AtomicReference<>(null);
    private final List<Consumer<FearGreedIndex>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    public FearGreedMonitorService() {
        this(new FearGreedClient(), true, new NetConfig().getPollJitterPercent());
    }

    @Inject
    public FearGreedMonitorService(FearGreedClient client, GlobalConfig config) {
        // Production (Guice) path: build WITHOUT auto-starting. The poll loop hits the
        // browser transport, which must not trigger JCEF's native init off the AWT
        // thread before the window does — that races the EDT init and deadlocks the
        // window. AppMain calls start() only after the window has brought CEF up.
        this(client, false,
                config == null || config.getNet() == null
                        ? new NetConfig().getPollJitterPercent()
                        : config.getNet().getPollJitterPercent());
    }

    FearGreedMonitorService(FearGreedClient client, boolean autoStart) {
        this(client, autoStart, new NetConfig().getPollJitterPercent());
    }

    FearGreedMonitorService(FearGreedClient client, boolean autoStart, double pollJitterPercent) {
        this.client = client;
        this.pollJitterPercent = pollJitterPercent;
        if (autoStart) start();
    }

    /** Starts the poll loop. Idempotent — safe whether called by the ctor or AppMain. */
    public void start() {
        if (!started.compareAndSet(false, true)) return;
        LOG.info("Starting Fear&Greed monitor (interval: {} seconds, jitter {}%)",
                POLL_INTERVAL_SECONDS, pollJitterPercent);
        // Jittered cadence (traffic blending): first tick still at t=0 for a
        // fast first paint, only the repeat interval varies around the base.
        JitteredScheduler.schedule(scheduler, this::tick,
                INITIAL_DELAY_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS, pollJitterPercent);
    }

    /** Single poll cycle: fetch, update the cache, fan out. Never lets the executor see an exception. */
    void tick() {
        try {
            Optional<FearGreedIndex> next = client.fetch();
            if (next.isEmpty()) {
                LOG.debug("Fear&Greed unavailable this tick; keeping last reading");
                return;
            }
            FearGreedIndex idx = next.get();
            current.set(idx);
            LOG.debug("Fear&Greed = {} ({})", Math.round(idx.score()), idx.band());
            for (Consumer<FearGreedIndex> l : listeners) {
                try {
                    l.accept(idx);
                } catch (Exception e) {
                    LOG.warn("Fear&Greed listener {} threw: {}", l, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.error("Fear&Greed tick failed", e);
        }
    }

    /** Most recent reading, or empty if no poll has succeeded yet. */
    public Optional<FearGreedIndex> getCurrent() {
        return Optional.ofNullable(current.get());
    }

    /** Registers a listener fired on every successful poll (on the monitor thread — keep it cheap). */
    public void addListener(Consumer<FearGreedIndex> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<FearGreedIndex> listener) {
        listeners.remove(listener);
    }

    /** Stops the scheduler. For test teardown / shutdown. */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
