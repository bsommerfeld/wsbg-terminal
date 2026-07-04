package de.bsommerfeld.wsbg.terminal.core.util;

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
 * Shared scaffolding for the small "poll a source, hold the latest value, fan
 * out to listeners" monitor services (EUR/USD, Fear&amp;Greed, finanznachrichten).
 * They all duplicated the same skeleton: a single-thread daemon
 * {@link ScheduledExecutorService}, an {@link AtomicReference} holding the latest
 * value, a {@link CopyOnWriteArrayList} of listeners fanned out under a
 * per-listener try/catch, a {@code started} guard, and a {@code shutdown()}.
 *
 * <p>This base owns exactly that. Each concrete monitor keeps only its
 * per-source {@code tick()} strategy (the fetch + parse that produces a
 * {@code T}) and its own interval / cadence.
 *
 * <h3>Deliberately NOT auto-starting</h3>
 * The base never schedules anything from its constructor. Subclasses construct
 * fully idle and expose their own {@code start()} that calls
 * {@link #scheduleTick(Runnable, long, long, double)}. This preserves the
 * project-wide contract that a monitor is built without touching the network:
 * the poll loop hits the browser transport, which must not trigger JCEF's native
 * init off the AWT thread before the window has brought CEF up — so the FIRST
 * tick must run off the calling thread, on the scheduler's daemon thread, only
 * once {@code start()} is invoked (from {@code AppMain}, after the window is up).
 *
 * @param <T> the value type each poll produces and each listener receives
 */
public abstract class PollingMonitor<T> {

    private static final Logger LOG = LoggerFactory.getLogger(PollingMonitor.class);

    private final ScheduledExecutorService scheduler;
    private final AtomicReference<T> current = new AtomicReference<>(null);
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * @param threadName name for the single daemon scheduler thread
     *                   (e.g. {@code "eurusd-monitor"})
     */
    protected PollingMonitor(String threadName) {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Schedules the recurring tick on the daemon scheduler with a jittered
     * cadence. The first run fires at {@code initialDelaySeconds} (kept exact,
     * so a t=0 monitor still paints fast) but always off the calling thread.
     * Returns the {@link JitteredScheduler.Handle} so a monitor that restarts
     * its loop (e.g. finanznachrichten) can cancel it; monitors that start once
     * may ignore the return.
     */
    protected JitteredScheduler.Handle scheduleTick(Runnable tick, long initialDelaySeconds,
            long intervalSeconds, double jitterPercent) {
        return JitteredScheduler.schedule(scheduler, tick,
                initialDelaySeconds, intervalSeconds, TimeUnit.SECONDS, jitterPercent);
    }

    /**
     * Marks the monitor started, returning {@code true} exactly once. Concrete
     * {@code start()} methods that must be idempotent guard on this; monitors
     * that manage their own restart lifecycle need not use it.
     */
    protected boolean beginStart() {
        return started.compareAndSet(false, true);
    }

    /** Stores the latest polled value (used by value-holding monitors). */
    protected void setCurrent(T value) {
        current.set(value);
    }

    /** Most recent value, or empty if no poll has produced one yet. */
    public Optional<T> getCurrent() {
        return Optional.ofNullable(current.get());
    }

    /**
     * Fans {@code value} out to every listener, isolating each so one throwing
     * listener never stops the others (nor kills the scheduler thread).
     */
    protected void fanOut(T value) {
        for (Consumer<T> l : listeners) {
            try {
                l.accept(value);
            } catch (Exception e) {
                onListenerError(l, e);
            }
        }
    }

    /**
     * Hook for how a monitor logs a listener that threw during fan-out. Default
     * is a generic WARN; subclasses override to keep their own wording/logger.
     */
    protected void onListenerError(Consumer<T> listener, Exception e) {
        LOG.warn("listener {} threw: {}", listener, e.getMessage());
    }

    /**
     * Registers a listener fired on every fan-out, on the monitor's scheduler
     * thread — keep it cheap or hand off to your own executor.
     */
    public void addListener(Consumer<T> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<T> listener) {
        listeners.remove(listener);
    }

    /** Stops the scheduler for good. Intended for test teardown / shutdown hooks. */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
