package de.bsommerfeld.wsbg.terminal.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.UserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Isolated, low-profile tracker for the cumulative wall-clock time the user has
 * had the terminal open. It exists for one product reason: the footer donation
 * banner should stay hidden until the terminal has plausibly earned its keep
 * (~12&nbsp;h of use), so new users aren't asked for money before the tool has
 * shown them a profitable trade.
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
 *       a laptop closed overnight doesn't unlock the banner by morning.</li>
 *   <li><b>Unobtrusive</b> — all work runs on a single daemon thread off the
 *       UI/EDT; the only I/O is the config write, which happens on that thread,
 *       never blocking the UI.</li>
 * </ul>
 *
 * <p>This is the sole owner of session bookkeeping: besides the precise active
 * time it also bumps the coarse {@link UserConfig#getOpenCount() open-count} and
 * records the {@link UserConfig#getFirstStartTimestamp() first-ever start} once,
 * both psychological signals for the donation gate (how long the user has had
 * the tool, across how many sessions).
 */
@Singleton
public final class TimeTracker {

    private static final Logger LOG = LoggerFactory.getLogger(TimeTracker.class);

    private static final long MILLIS_PER_HOUR = 3_600_000L;

    /**
     * How long a banner-link click suppresses the active nudge layer. Two days,
     * not weeks: the banner is the product's only revenue surface, and a long
     * cooldown after a single engagement effectively hid it forever. The heart
     * click deliberately does NOT snooze (see {@code DonationGatePublisher}).
     */
    private static final long SNOOZE_MILLIS = 2L * 24 * MILLIS_PER_HOUR;

    /**
     * How often the running session is flushed to disk. Small enough that a
     * crash loses almost nothing, large enough to be invisible (one tiny TOML
     * write per minute). The 12&nbsp;h unlock target makes the exact value
     * uncritical — this is the "fixed interval" checkpoint.
     */
    private static final long FLUSH_INTERVAL_SECONDS = 60;

    private final GlobalConfig config;
    private final UserConfig user;
    private final ScheduledExecutorService executor;

    /** Anything beyond this between two checkpoints is treated as machine sleep. */
    private final long maxCreditPerFlushMs = TimeUnit.SECONDS.toMillis(FLUSH_INTERVAL_SECONDS) * 2;

    /** Monotonic reference for the last credited checkpoint. Guarded by {@code this}. */
    private long lastCheckpointNanos;

    private final AtomicBoolean unlocked = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Runnable> unlockListeners = new CopyOnWriteArrayList<>();

    /** Listeners fired whenever {@link #isDonationActive()} flips (either way). */
    private final CopyOnWriteArrayList<Runnable> activeChangeListeners = new CopyOnWriteArrayList<>();

    /** Last {@link #isDonationActive()} value the change listeners were told about. */
    private volatile boolean lastActiveState;

    @Inject
    public TimeTracker(GlobalConfig config) {
        this.config = config;
        this.user = config.getUser();
        this.lastCheckpointNanos = System.nanoTime();   // session "start" checkpoint
        this.unlocked.set(thresholdReached(user.getActiveMillis(), user.getDonationUnlockHours()));
        this.lastActiveState = isDonationActive();

        recordSessionStart();

        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "time-tracker");
            t.setDaemon(true);
            return t;
        });
        this.executor.scheduleAtFixedRate(this::checkpoint,
                FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);

        LOG.info("TimeTracker started — {} of active time so far (unlock at {} h, {}).",
                Duration.ofMillis(user.getActiveMillis()),
                user.getDonationUnlockHours(),
                unlocked.get() ? "already unlocked" : "still locked");
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
     * Adds {@code elapsedMs} (clamped for machine sleep) to the running total,
     * persists, and fires the donation unlock if the threshold is now crossed.
     * Package-private and timing-free so the crediting logic is unit-testable
     * without the real scheduler/clock.
     */
    synchronized void creditAndPersist(long elapsedMs) {
        long credited = creditedMillis(elapsedMs, maxCreditPerFlushMs);
        if (credited > 0) {
            if (credited < elapsedMs) {
                LOG.debug("Checkpoint delta {} ms exceeds cap {} ms — crediting only the "
                        + "cap (machine sleep/suspend assumed).", elapsedMs, maxCreditPerFlushMs);
            }
            long total = user.getActiveMillis() + credited;
            user.setActiveMillis(total);
            persist();
            maybeUnlock(total);
        }
        // Re-evaluated even when nothing was credited: the snooze window can
        // expire mid-session with no crediting involved, and a long-running
        // terminal would otherwise never learn the banner may run again.
        notifyIfActiveChanged();
    }

    private void maybeUnlock(long totalMs) {
        if (!unlocked.get() && thresholdReached(totalMs, user.getDonationUnlockHours())
                && unlocked.compareAndSet(false, true)) {
            LOG.info("Donation banner unlocked — {} of cumulative active time reached.",
                    Duration.ofMillis(totalMs));
            for (Runnable l : unlockListeners) {
                try {
                    l.run();
                } catch (Throwable t) {
                    LOG.warn("Unlock listener failed: {}", t.getMessage());
                }
            }
        }
    }

    /** Fires the active-change listeners if {@link #isDonationActive()} flipped. */
    private void notifyIfActiveChanged() {
        boolean now = isDonationActive();
        if (now == lastActiveState) return;
        lastActiveState = now;
        for (Runnable l : activeChangeListeners) {
            try {
                l.run();
            } catch (Throwable t) {
                LOG.warn("Active-change listener failed: {}", t.getMessage());
            }
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

    /** Whether {@code activeMs} meets the unlock bar; {@code hours <= 0} = always. */
    static boolean thresholdReached(long activeMs, double hours) {
        if (hours <= 0) return true;   // 0 = show immediately
        return activeMs >= Math.round(hours * MILLIS_PER_HOUR);
    }

    /**
     * Whether the cumulative active time has crossed the donation-unlock
     * threshold (12&nbsp;h). This is the latched time gate only — it ignores
     * the snooze. The persistent heart icon does not depend on this; only the
     * active nudge layer (rotating footer banner) does, via {@link
     * #isDonationActive()}.
     */
    public boolean isDonationUnlocked() {
        return unlocked.get();
    }

    /**
     * Whether the active donation nudge layer should currently show: time gate
     * crossed <em>and</em> not inside a snooze window. The heart icon ignores
     * this and is always visible; this gates only the rotating footer banner.
     */
    public boolean isDonationActive() {
        return unlocked.get() && System.currentTimeMillis() >= user.getDonationSnoozeUntil();
    }

    /**
     * Suppresses the active nudge layer for {@value #SNOOZE_MILLIS}ms (48 h).
     * Called when the user engages a banner link — the nudge was answered, so
     * it rests for a while. Persisted, so it survives restarts. The heart
     * click does not snooze.
     */
    public void snoozeDonation() {
        long until = System.currentTimeMillis() + SNOOZE_MILLIS;
        user.setDonationSnoozeUntil(until);
        persist();
        LOG.info("Donation nudge snoozed until {}.", java.time.Instant.ofEpochMilli(until));
        notifyIfActiveChanged();
    }

    /**
     * Records that the user has opened the donate page at least once (clicked
     * the heart or a banner link). Honor system — there are no accounts and
     * Ko-fi is external, so the click itself is the only signal we have. The
     * flag is persisted once and never reset; it gilds the heart icon. Banner
     * behaviour is unaffected.
     */
    public void markDonationClicked() {
        if (user.isDonationClicked()) return;
        user.setDonationClicked(true);
        persist();
        LOG.info("Donate click recorded — heart gilded (supporter state, honor system).");
    }

    /** Whether the user has ever clicked through to the donate page. */
    public boolean isDonationClicked() {
        return user.isDonationClicked();
    }

    /** How many times the terminal has been opened (sessions), incl. this one. */
    public long getOpenCount() {
        return user.getOpenCount();
    }

    /** Cumulative active milliseconds, including time credited so far this session. */
    public long getActiveMillis() {
        return user.getActiveMillis();
    }

    /**
     * Registers a callback fired once, the moment the unlock threshold is first
     * crossed (mid-session). If already unlocked at registration time it does
     * <em>not</em> fire — callers should also read {@link #isDonationUnlocked()}.
     */
    public void onUnlock(Runnable listener) {
        unlockListeners.add(listener);
    }

    /**
     * Registers a callback fired every time {@link #isDonationActive()} flips —
     * unlock crossing, snooze set, or snooze <em>expiry</em> (caught by the
     * 60&nbsp;s checkpoint, so a long-running session re-arms the banner without
     * needing a restart). Does not fire for the state at registration time.
     */
    public void onActiveChange(Runnable listener) {
        activeChangeListeners.add(listener);
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
