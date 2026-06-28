package de.bsommerfeld.wsbg.terminal.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.UserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Isolated, low-profile tracker for the cumulative wall-clock time the user has
 * had the terminal open. It exists to feed the footer donation banner's
 * reciprocity copy — the personalised {@code {hours}} / {@code {opens}} lines —
 * with a precise, crash-safe figure for how long, and across how many sessions,
 * the user has had the tool. The banner itself runs unconditionally; there is no
 * time gate, snooze, or supporter state anymore.
 *
 * <h2>How it measures</h2>
 * Not by counting ticks ("+1 minute every minute"), which over-counts when the
 * machine sleeps and silently loses the final partial minute on every crash.
 * Instead it holds timestamps against each other: a monotonic checkpoint is
 * taken at <em>start</em>, on a fixed <em>interval</em>, and at <em>stop</em>,
 * and the delta between consecutive checkpoints is credited to a persisted
 * total ({@link UserConfig#getActiveMillis()}).
 *
 * <ul>
 *   <li><b>Accurate</b> — elapsed time comes from {@link System#nanoTime()}
 *       deltas, immune to wall-clock adjustments (NTP, DST, manual changes).</li>
 *   <li><b>Crash-safe</b> — because each interval is flushed to disk, an
 *       unclean exit loses at most one interval ({@value #FLUSH_INTERVAL_SECONDS}s),
 *       never the whole session.</li>
 *   <li><b>Sleep-aware</b> — a checkpoint delta far larger than the interval
 *       means the machine was suspended, not used; such deltas are clamped so
 *       a laptop closed overnight doesn't inflate the active-time figure.</li>
 *   <li><b>Unobtrusive</b> — all work runs on a single daemon thread off the
 *       UI/EDT; the only I/O is the config write, which happens on that thread,
 *       never blocking the UI.</li>
 * </ul>
 *
 * <p>This is the sole owner of session bookkeeping: besides the precise active
 * time it also bumps the coarse {@link UserConfig#getOpenCount() open-count} and
 * records the {@link UserConfig#getFirstStartTimestamp() first-ever start} once.
 */
@Singleton
public final class TimeTracker {

    private static final Logger LOG = LoggerFactory.getLogger(TimeTracker.class);

    /**
     * How often the running session is flushed to disk. Small enough that a
     * crash loses almost nothing, large enough to be invisible (one tiny TOML
     * write per minute). This is the "fixed interval" checkpoint.
     */
    private static final long FLUSH_INTERVAL_SECONDS = 60;

    private final GlobalConfig config;
    private final UserConfig user;
    private final ScheduledExecutorService executor;

    /** Anything beyond this between two checkpoints is treated as machine sleep. */
    private final long maxCreditPerFlushMs = TimeUnit.SECONDS.toMillis(FLUSH_INTERVAL_SECONDS) * 2;

    /** Monotonic reference for the last credited checkpoint. Guarded by {@code this}. */
    private long lastCheckpointNanos;

    @Inject
    public TimeTracker(GlobalConfig config) {
        this.config = config;
        this.user = config.getUser();
        this.lastCheckpointNanos = System.nanoTime();   // session "start" checkpoint

        recordSessionStart();

        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "time-tracker");
            t.setDaemon(true);
            return t;
        });
        this.executor.scheduleAtFixedRate(this::checkpoint,
                FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);

        LOG.info("TimeTracker started — {} of active time so far.",
                Duration.ofMillis(user.getActiveMillis()));
    }

    /** One-shot session bookkeeping: bump open-count, stamp the first-ever start. */
    private void recordSessionStart() {
        user.setOpenCount(user.getOpenCount() + 1);
        if (user.getFirstStartTimestamp() == 0) {
            user.setFirstStartTimestamp(System.currentTimeMillis());
        }
        persist();
    }

    /**
     * Credits the time elapsed since the previous checkpoint, persists it, and
     * advances the reference. Called on the interval and once more on stop.
     */
    private synchronized void checkpoint() {
        long now = System.nanoTime();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - lastCheckpointNanos);
        lastCheckpointNanos = now;
        creditAndPersist(elapsedMs);
    }

    /**
     * Adds {@code elapsedMs} (clamped for machine sleep) to the running total and
     * persists. Package-private and timing-free so the crediting logic is
     * unit-testable without the real scheduler/clock.
     */
    synchronized void creditAndPersist(long elapsedMs) {
        long credited = creditedMillis(elapsedMs, maxCreditPerFlushMs);
        if (credited > 0) {
            if (credited < elapsedMs) {
                LOG.debug("Checkpoint delta {} ms exceeds cap {} ms — crediting only the "
                        + "cap (machine sleep/suspend assumed).", elapsedMs, maxCreditPerFlushMs);
            }
            user.setActiveMillis(user.getActiveMillis() + credited);
            persist();
        }
    }

    private void persist() {
        try {
            config.save();
        } catch (Exception e) {
            LOG.error("Failed to persist session state", e);
        }
    }

    /**
     * Elapsed time to credit for one checkpoint: never negative, and capped at
     * {@code maxCreditMs} so a machine-sleep gap doesn't count as active use.
     */
    static long creditedMillis(long elapsedMs, long maxCreditMs) {
        if (elapsedMs <= 0) return 0;
        return Math.min(elapsedMs, maxCreditMs);
    }

    /** How many times the terminal has been opened (sessions), incl. this one. */
    public long getOpenCount() {
        return user.getOpenCount();
    }

    /** Cumulative active milliseconds, including time credited so far this session. */
    public long getActiveMillis() {
        return user.getActiveMillis();
    }

    /** Final checkpoint (credits the partial interval) and stops the daemon. */
    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            try {
                checkpoint();
            } catch (Exception e) {
                LOG.warn("Final checkpoint on shutdown failed: {}", e.getMessage());
            }
            executor.shutdown();
        }
    }
}
