package de.bsommerfeld.wsbg.terminal.feargreed;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    private static final long INITIAL_DELAY_SECONDS = 8;
    /** 5 min — CNN refreshes the composite only periodically through the session. */
    private static final long POLL_INTERVAL_SECONDS = 300;

    private final FearGreedClient client;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fear-greed-monitor");
                t.setDaemon(true);
                return t;
            });

    private final AtomicReference<FearGreedIndex> current = new AtomicReference<>(null);
    private final List<Consumer<FearGreedIndex>> listeners = new CopyOnWriteArrayList<>();

    public FearGreedMonitorService() {
        this(new FearGreedClient());
    }

    @Inject
    public FearGreedMonitorService(FearGreedClient client) {
        this.client = client;
        start();
    }

    private void start() {
        LOG.info("Starting Fear&Greed monitor (interval: {} seconds)", POLL_INTERVAL_SECONDS);
        scheduler.scheduleAtFixedRate(this::tick,
                INITIAL_DELAY_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
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
