package de.bsommerfeld.wsbg.terminal.core.util;

import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Schedules a recurring task with a <em>jittered</em> cadence instead of a
 * machine-exact one. Perfectly periodic polling is the clearest bot signal an
 * external host can observe — no browser fingerprint hides it — so every
 * external poll loop plans its next run as {@code base * (1 ± jitter)} via a
 * self-rescheduling {@code schedule(...)} rather than {@code
 * scheduleAtFixedRate} (see {@code docs/network-traffic-blending.md}).
 *
 * <h3>Semantics</h3>
 * <ul>
 *   <li>The <b>first</b> run fires at the caller's {@code initialDelay},
 *       unchanged — several monitors deliberately fetch at t=0 so data is on
 *       screen as fast as the network allows, and that must not degrade.</li>
 *   <li>A one-time random <b>phase offset</b> in {@code [0, base)} is folded
 *       into the delay after the first run, so loops that boot together stop
 *       firing in step immediately (not only after the jitter drifts them
 *       apart).</li>
 *   <li>Every subsequent delay is drawn uniformly from
 *       {@code [base*(1-j), base*(1+j)]}, measured from task completion
 *       (fixed-delay semantics).</li>
 *   <li>{@code jitterPercent <= 0} reproduces the old behaviour exactly: no
 *       offset, every delay is exactly {@code base} (a regular fixed delay).</li>
 * </ul>
 *
 * <p>A task that throws does not kill the loop — the next run is always
 * scheduled (the tick methods catch their own errors anyway). Cancel via the
 * returned {@link Handle}; shutting the executor down stops the loop too.
 */
public final class JitteredScheduler {

    /** Jitter above this is clamped so a drawn delay can never reach zero. */
    private static final double MAX_JITTER_FRACTION = 0.9;

    private JitteredScheduler() {
    }

    /** Cancellable handle for one jittered loop. */
    public interface Handle {
        void cancel();
    }

    /**
     * Starts a self-rescheduling jittered loop on {@code executor}.
     *
     * @param initialDelay  delay before the first run (kept exact — see class doc)
     * @param baseInterval  the nominal interval the jitter varies around
     * @param jitterPercent jitter share in percent of {@code baseInterval};
     *                      {@code 0} disables jitter and phase offset entirely
     */
    public static Handle schedule(ScheduledExecutorService executor, Runnable task,
            long initialDelay, long baseInterval, TimeUnit unit, double jitterPercent) {
        JitteredTask jt = new JitteredTask(executor, task,
                unit.toMillis(baseInterval), jitterFraction(jitterPercent));
        jt.start(unit.toMillis(initialDelay));
        return jt;
    }

    /** Percent → clamped fraction in {@code [0, }{@value #MAX_JITTER_FRACTION}{@code ]}. */
    static double jitterFraction(double jitterPercent) {
        return Math.min(MAX_JITTER_FRACTION, Math.max(0.0, jitterPercent / 100.0));
    }

    /** Uniform draw from {@code [base*(1-j), base*(1+j)]}; exactly {@code base} at j=0. */
    static long jitteredDelayMillis(long baseMillis, double jitterFraction, Random random) {
        if (jitterFraction <= 0.0 || baseMillis <= 0) return baseMillis;
        double factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * jitterFraction;
        return Math.max(1L, Math.round(baseMillis * factor));
    }

    /** Uniform draw from {@code [0, base)}; {@code 0} when jitter is disabled. */
    static long phaseOffsetMillis(long baseMillis, double jitterFraction, Random random) {
        if (jitterFraction <= 0.0 || baseMillis <= 0) return 0L;
        return (long) (random.nextDouble() * baseMillis);
    }

    private static final class JitteredTask implements Handle, Runnable {

        private final ScheduledExecutorService executor;
        private final Runnable task;
        private final long baseMillis;
        private final double jitterFraction;
        private final Random random = new Random();

        private boolean cancelled;
        private boolean phaseApplied;
        private ScheduledFuture<?> future;

        JitteredTask(ScheduledExecutorService executor, Runnable task,
                long baseMillis, double jitterFraction) {
            this.executor = executor;
            this.task = task;
            this.baseMillis = baseMillis;
            this.jitterFraction = jitterFraction;
        }

        void start(long initialDelayMillis) {
            scheduleNext(initialDelayMillis);
        }

        @Override
        public void run() {
            try {
                task.run();
            } finally {
                long delay = jitteredDelayMillis(baseMillis, jitterFraction, random);
                if (!phaseApplied) {
                    delay += phaseOffsetMillis(baseMillis, jitterFraction, random);
                    phaseApplied = true;
                }
                scheduleNext(delay);
            }
        }

        private synchronized void scheduleNext(long delayMillis) {
            if (cancelled || executor.isShutdown()) return;
            try {
                future = executor.schedule(this, delayMillis, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // executor shut down between the check and the submit — loop ends
            }
        }

        @Override
        public synchronized void cancel() {
            cancelled = true;
            if (future != null) future.cancel(false);
        }
    }
}
