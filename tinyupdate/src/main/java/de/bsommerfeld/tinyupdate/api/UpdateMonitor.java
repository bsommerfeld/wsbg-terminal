package de.bsommerfeld.tinyupdate.api;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Asks, in the background, whether an update is pending - right after
 * {@link #start()} and then at a fixed interval - and reports the first
 * {@code true} once. For the application that checks while it runs instead
 * of before it starts: nothing waits on the network, the application only
 * hears about an update when there is one.
 *
 * <p>
 * A check that fails (offline, rate-limited, a release still uploading) is
 * not an answer: the monitor stays quiet and asks again at the next interval.
 * Once it has reported, it stops - an update is either applied, which ends
 * the process, or declined, and then asking again would only repeat itself.
 */
public final class UpdateMonitor implements AutoCloseable {

    /** The question the monitor asks - {@link TinyUpdateClient#isUpdatePending}. */
    @FunctionalInterface
    public interface Probe {
        boolean isUpdatePending() throws Exception;
    }

    private final Probe probe;
    private final Duration interval;
    private final Runnable onPending;

    /*
     * One platform thread, a daemon: the monitor must never keep an
     * application alive that is shutting down, and it runs one check at a
     * time by construction, so a slow check cannot overlap the next one.
    */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "update-monitor");
        thread.setDaemon(true);
        return thread;
    });

    private ScheduledFuture<?> schedule;

    /**
     * @param probe     what decides whether an update is pending
     * @param interval  the time between two checks
     * @param onPending called once, on the monitor's thread, when the probe
     *                  first answers {@code true}
     */
    public UpdateMonitor(Probe probe, Duration interval, Runnable onPending) {
        this.probe = Objects.requireNonNull(probe, "probe");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.onPending = Objects.requireNonNull(onPending, "onPending");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive: " + interval);
        }
    }

    /** The monitor for a client's stream. */
    public static UpdateMonitor of(TinyUpdateClient client, Duration interval, Runnable onPending) {
        return new UpdateMonitor(client::isUpdatePending, interval, onPending);
    }

    /** Starts checking: the first check right away, then one per interval. */
    public synchronized void start() {
        if (schedule != null) {
            throw new IllegalStateException("UpdateMonitor is already started");
        }
        schedule = scheduler.scheduleWithFixedDelay(this::check, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void check() {
        boolean pending;
        try {
            pending = probe.isUpdatePending();
        } catch (Exception e) {
            System.err.println("[updater] Update check failed, retrying in " + interval + ": " + e);
            return;
        }
        if (pending) {
            scheduler.shutdown();
            onPending.run();
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
