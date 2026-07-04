package de.bsommerfeld.wsbg.terminal.ui.bridge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Factory for the single-thread daemon executors the bridge publishers use for
 * their poll / fetch loops. Every publisher previously hand-rolled the same
 * {@code Executors.newSingle*(r -> daemon thread named X)} block; this collapses
 * that boilerplate into one place. Daemon threads so a lingering poll loop never
 * keeps the JVM alive past shutdown.
 */
final class DaemonSchedulers {

    private DaemonSchedulers() {
    }

    /** A single-thread scheduled executor whose worker is a daemon named {@code name}. */
    static ScheduledExecutorService scheduled(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> daemon(r, name));
    }

    /** A single-thread executor whose worker is a daemon named {@code name}. */
    static ExecutorService single(String name) {
        return Executors.newSingleThreadExecutor(r -> daemon(r, name));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
