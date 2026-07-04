package de.bsommerfeld.wsbg.terminal.reddit.support;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.reddit.ScrapeStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The async comment-ingestion worker shared by both Reddit sources — the
 * cold-start fix. Fetching one comment tree per new thread inline (rate-limited)
 * is what made a cold-start listing crawl for minutes; clustering only needs
 * title+body+vision, so a new thread is enqueued here and a single daemon worker
 * backfills its comments in the background while the listing scan returns
 * immediately.
 *
 * <p>Threads whose comments backfilled since the last scan are re-surfaced into
 * the next scan's {@link ScrapeStats} (via {@link #drainInto}) so the editorial
 * layer regenerates their headline with the new evidence — the one channel the
 * {@code RedditSource} contract gives back to the agent.
 *
 * <p>The per-thread backfill work differs between the JSON and RSS paths, so it
 * is supplied as a {@link Backfiller}; the queue, daemon lifecycle and
 * resurface bookkeeping are identical and live here.
 */
public final class DeferredCommentBackfill {

    private static final Logger LOG = LoggerFactory.getLogger(DeferredCommentBackfill.class);

    /**
     * Per-thread backfill action. Persists the thread's comment tree as a side
     * effect and returns {@code true} when the thread should be re-surfaced into
     * the next scan (fresh comments arrived).
     */
    @FunctionalInterface
    public interface Backfiller {
        boolean backfill(String threadId, String permalink) throws Exception;
    }

    /** One queued comment-ingestion job. */
    private record Job(String threadId, String permalink) {}

    private final BlockingQueue<Job> queue = new LinkedBlockingQueue<>();
    private final Set<String> pendingResurface = ConcurrentHashMap.newKeySet();
    private final RedditRepository repository;
    private final Backfiller backfiller;

    /**
     * Starts the daemon worker immediately. The queue is empty at construction,
     * so the worker blocks on {@code take()} until the first {@link #enqueue};
     * nothing runs before the owning source has finished wiring itself.
     */
    public DeferredCommentBackfill(String threadName, RedditRepository repository,
            Backfiller backfiller) {
        this.repository = repository;
        this.backfiller = backfiller;
        Thread worker = new Thread(this::run, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    /** Queues a thread's comments for background ingestion. */
    public void enqueue(String threadId, String permalink) {
        queue.add(new Job(threadId, permalink));
    }

    private void run() {
        while (true) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                if (backfiller.backfill(job.threadId(), job.permalink())) {
                    pendingResurface.add(job.threadId());
                }
            } catch (Exception e) {
                LOG.debug("Async comment ingest failed for {}: {}", job.threadId(), e.getMessage());
            }
        }
    }

    /**
     * Folds threads whose comments backfilled asynchronously since the last scan
     * into this scan's stats, so the editorial layer re-evaluates them with the
     * new comment tree.
     */
    public void drainInto(ScrapeStats stats) {
        if (pendingResurface.isEmpty()) return;
        List<String> ids = new ArrayList<>(pendingResurface);
        pendingResurface.clear();
        for (String id : ids) {
            if (stats.scannedIds.contains(id)) continue; // already in this batch
            RedditThread t = repository.getThread(id);
            if (t == null) continue;
            stats.scannedIds.add(id);
            stats.threadUpdates.add(t);
            stats.newComments++; // mark hasUpdates so the cycle isn't a no-op
        }
    }
}
