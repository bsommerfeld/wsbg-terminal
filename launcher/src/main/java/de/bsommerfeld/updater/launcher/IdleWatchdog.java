package de.bsommerfeld.updater.launcher;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kills a setup process (and its children) once it has been silent for a
 * configured idle timeout. Every long-running setup step (curl, ollama pull)
 * emits steady progress lines, so prolonged silence means the process is hung,
 * not slow. Self-contained: the caller {@link #start(Process)}s it, calls
 * {@link #markOutput()} on every line read, checks {@link #timedOut()} after
 * the stream ends, and {@link #stop()}s it in a finally block.
 */
final class IdleWatchdog {

    private final Duration timeout;

    /** Timestamp of the last observed output line. */
    private final AtomicLong lastOutputAt = new AtomicLong();

    /** Set when the watchdog killed the process for being silent. */
    private volatile boolean timedOut;

    private volatile Thread thread;

    IdleWatchdog(Duration timeout) {
        this.timeout = timeout;
    }

    Duration timeout() {
        return timeout;
    }

    /**
     * Starts watching the process on a virtual thread. Resets the output clock
     * and the timed-out flag, so an instance may be reused across runs.
     */
    void start(Process process) {
        lastOutputAt.set(System.currentTimeMillis());
        timedOut = false;
        thread = Thread.ofVirtual().name("setup-watchdog").start(() -> watch(process));
    }

    /** Records that the process produced output — proof it is still alive. */
    void markOutput() {
        lastOutputAt.set(System.currentTimeMillis());
    }

    /** Whether the watchdog killed the process for being silent. */
    boolean timedOut() {
        return timedOut;
    }

    /** Stops the watchdog thread (the run has finished). */
    void stop() {
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    private void watch(Process process) {
        try {
            while (process.isAlive()) {
                long remaining = timeout.toMillis()
                        - (System.currentTimeMillis() - lastOutputAt.get());
                if (remaining <= 0) {
                    timedOut = true;
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    return;
                }
                Thread.sleep(Math.min(remaining, 1000));
            }
        } catch (InterruptedException ignored) {
            // run() finished — watchdog no longer needed
        }
    }
}
