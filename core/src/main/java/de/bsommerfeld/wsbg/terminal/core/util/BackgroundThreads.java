package de.bsommerfeld.wsbg.terminal.core.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Daemon thread factories for the app's BACKGROUND work — every scraper poll,
 * HTML/JSON parse, editorial lane and deep-dive worker.
 *
 * <p>The app runs two things at once that want the same cores: a UI whose every
 * frame is a Swing/EDT blit of a CEF buffer, and a fleet of ~20 pools doing HTTP
 * + parsing + LLM calls. All of those threads used to be created at
 * {@link Thread#NORM_PRIORITY} — the SAME priority as the EDT — so a busy
 * pipeline round competed with the render path on equal footing and the UI
 * skipped frames. Everything created through this class runs at
 * {@link #BACKGROUND_PRIORITY} instead, which leaves the EDT (and the asset
 * server that feeds the page itself) alone at the top.
 *
 * <p>Deliberately NOT {@link Thread#MIN_PRIORITY}: the pipeline must still make
 * steady progress while the user works in the UI — this is a yield, not a
 * starve. And it is a soft lever, not a guarantee: on macOS the JVM maps Java
 * priorities onto pthread priorities with a coarse table, so the effect is real
 * but modest. It buys headroom; it does not fix a saturated GPU.
 *
 * <p>Daemon throughout, so a lingering poll loop can never keep the JVM alive
 * past shutdown.
 */
public final class BackgroundThreads {

    /**
     * Priority for every background worker: clearly below {@code NORM_PRIORITY}
     * (5, where the EDT sits), clearly above {@code MIN_PRIORITY} (1) so a pool
     * under sustained UI load still gets scheduled.
     */
    public static final int BACKGROUND_PRIORITY = 3;

    private BackgroundThreads() {
    }

    /**
     * A factory whose threads are named {@code name-1}, {@code name-2}, … — for
     * pools with more than one worker.
     */
    public static ThreadFactory factory(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> daemon(r, name + "-" + n.incrementAndGet());
    }

    /**
     * A factory whose single thread carries {@code name} verbatim — for
     * single-thread executors, where a {@code -1} suffix would only make the
     * logs harder to read.
     */
    public static ThreadFactory single(String name) {
        return r -> daemon(r, name);
    }

    /** A background-priority daemon thread named {@code name}. Not started. */
    public static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(BACKGROUND_PRIORITY);
        return t;
    }
}
