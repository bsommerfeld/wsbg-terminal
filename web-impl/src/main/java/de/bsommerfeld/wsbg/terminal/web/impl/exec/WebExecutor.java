package de.bsommerfeld.wsbg.terminal.web.impl.exec;

import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The ONE executor everything web rides on. Fetch work is almost pure I/O
 * wait, so the workers are virtual threads — one per task, no pool sizing to
 * get wrong, thousands of concurrent fetches for free — while pacing lives
 * where it belongs (host cooldowns in the fetcher, cadence in the scheduler),
 * never in the executor. Beside the workers runs ONE small platform-thread
 * timer that only books appointments and hands the actual work back to the
 * workers, so a slow collector can never block the clock.
 *
 * <p>This retires the scattered per-client pools of the old world (every
 * hand-rolled {@code HttpClient} carried its own).
 */
@Singleton
public final class WebExecutor implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WebExecutor.class);

    private final ExecutorService workers;
    private final ScheduledExecutorService timer;

    public WebExecutor() {
        ThreadFactory virtualFactory = Thread.ofVirtual().name("web-worker-", 0).factory();
        this.workers = Executors.newThreadPerTaskExecutor(virtualFactory);
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "web-timer");
            t.setDaemon(true);
            return t;
        });
    }

    /** Runs {@code task} on a worker; failures land in the future, never vanish. */
    public <T> CompletableFuture<T> supply(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        workers.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Runs {@code task} on a worker, fire-and-forget with logged failures. */
    public void execute(String what, Runnable task) {
        workers.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOG.warn("web task '{}' failed: {}", what, t.toString());
            }
        });
    }

    /** Books {@code task} onto the timer; the task body runs on a WORKER. */
    public void schedule(String what, Runnable task, long delay, TimeUnit unit) {
        timer.schedule(() -> execute(what, task), delay, unit);
    }

    @Override
    public void close() {
        timer.shutdownNow();
        workers.shutdown();
    }
}
