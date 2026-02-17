package de.bsommerfeld.wsbg.terminal.db;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Write-through caching layer over {@link DatabaseService}.
 *
 * <p>
 * This is the single point of access for all Reddit data in the application.
 * Neither the agent module nor the UI ever talk to {@code DatabaseService}
 * directly — they always go through this repository.
 *
 * <h3>Threading model</h3>
 * <ul>
 * <li><strong>Writes</strong>: cache is updated synchronously on the caller's
 * thread (instant visibility), then the DB write is dispatched to a
 * dedicated single-thread executor. This prevents the scraper from
 * blocking on SQLite I/O.</li>
 * <li><strong>Reads</strong>: served from the in-memory cache whenever
 * possible.
 * Cache misses fall through to the underlying {@code DatabaseService}
 * and populate the cache for subsequent reads.</li>
 * </ul>
 *
 * <h3>Record immutability</h3>
 * Since {@link RedditThread} and {@link RedditComment} are Java records, any
 * mutable field update (e.g. advancing {@code lastActivityUtc}) requires
 * reconstructing the entire record. This is intentional — it keeps the cache
 * entries safely immutable and eliminates concurrent modification concerns.
 */
@Singleton
public class RedditRepository {

    private static final Logger LOG = LoggerFactory.getLogger(RedditRepository.class);

    private final DatabaseService databaseService;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    private final Map<String, RedditThread> threadCache = new ConcurrentHashMap<>();
    private final Map<String, List<RedditComment>> threadCommentsCache = new ConcurrentHashMap<>();

    @Inject
    public RedditRepository(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    /**
     * Bulk-loads all threads from the database into the in-memory cache. Called
     * once during startup and again after every cleanup cycle to ensure the cache
     * reflects the post-cleanup state.
     */
    public void warmup() {
        LOG.info("Warming up RedditRepository cache...");
        List<RedditThread> all = databaseService.getAllThreads();
        for (RedditThread t : all) {
            threadCache.put(t.id(), t);
        }
        LOG.info("Cache warmed with {} threads.", all.size());
    }

    /**
     * Gracefully drains all pending async writes (up to 30s), then shuts down
     * the DB executor. Must be called during application shutdown to prevent
     * data loss from queued but uncommitted writes.
     */
    public void shutdown() {
        LOG.info("Shutting down RedditRepository...");
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
                LOG.warn("RedditRepository forced shutdown (timed out).");
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // -- Writes (Async Write-Through) --

    /**
     * Saves a thread — cache is updated instantly, DB write is async.
     * Delegates to batch save internally for consistency.
     */
    public CompletableFuture<Void> saveThread(RedditThread thread) {
        threadCache.put(thread.id(), thread);
        return CompletableFuture.runAsync(() -> databaseService.saveThread(thread), dbExecutor);
    }

    /**
     * Batch-saves multiple threads in a single async operation. The cache is
     * populated synchronously so that subsequent reads immediately see the new
     * data, even before the DB transaction commits.
     */
    public CompletableFuture<Void> saveThreadsBatch(List<RedditThread> threads) {
        if (threads == null || threads.isEmpty())
            return CompletableFuture.completedFuture(null);
        for (RedditThread t : threads)
            threadCache.put(t.id(), t);
        return CompletableFuture.runAsync(() -> databaseService.saveThreadsBatch(threads), dbExecutor);
    }

    /**
     * Saves a comment, updates the comment cache, and advances the owning
     * thread's {@code lastActivityUtc} if the comment is newer.
     *
     * <p>
     * Because records are immutable, updating the thread requires full
     * reconstruction via the canonical constructor. The comment list is
     * re-sorted by {@code createdUtc} descending to maintain the "newest first"
     * ordering expected by consumers.
     */
    public CompletableFuture<Void> saveComment(RedditComment comment) {
        String threadId = comment.threadId();
        if (threadId != null && !"unknown".equals(threadId)) {
            threadCommentsCache.computeIfPresent(threadId, (k, list) -> {
                List<RedditComment> updated = new ArrayList<>(list);
                updated.removeIf(c -> c.id().equals(comment.id()));
                updated.add(comment);
                updated.sort(Comparator.comparingLong(RedditComment::createdUtc).reversed());
                return updated;
            });

            threadCache.computeIfPresent(threadId, (k, t) -> new RedditThread(
                    t.id(), t.subreddit(), t.title(), t.author(), t.textContent(),
                    t.createdUtc(), t.permalink(), t.score(), t.upvoteRatio(),
                    t.numComments(),
                    Math.max(t.lastActivityUtc(), comment.createdUtc()),
                    t.imageUrl()));
        }
        return CompletableFuture.runAsync(() -> databaseService.saveComment(comment), dbExecutor);
    }

    // -- Reads (Cache-first, synchronous) --

    /**
     * Returns a thread by its Reddit ID. Serves from cache first; on miss,
     * queries the database and backfills the cache for subsequent reads.
     *
     * @return the thread, or {@code null} if it does not exist in either layer
     */
    public RedditThread getThread(String id) {
        RedditThread cached = threadCache.get(id);
        if (cached != null)
            return cached;

        RedditThread fromDb = databaseService.getThread(id);
        if (fromDb != null)
            threadCache.put(id, fromDb);
        return fromDb;
    }

    /**
     * Returns all threads from the cache. If the cache is cold (empty),
     * triggers a full {@link #warmup()} first. Returns a defensive copy.
     */
    public List<RedditThread> getAllThreads() {
        if (threadCache.isEmpty())
            warmup();
        return new ArrayList<>(threadCache.values());
    }

    /**
     * Returns up to {@code limit} comments for a given thread, newest first.
     *
     * <p>
     * On first access for a thread, fetches up to 200 comments from the DB
     * and caches the result. This over-fetch avoids repeated DB hits when
     * different callers request varying limits for the same thread.
     *
     * @param threadId the owning thread's Reddit ID
     * @param limit    maximum number of comments to return (&le; 0 means all
     *                 cached)
     */
    public List<RedditComment> getCommentsForThread(String threadId, int limit) {
        List<RedditComment> cached = threadCommentsCache.get(threadId);
        if (cached != null) {
            return (limit > 0 && cached.size() > limit) ? new ArrayList<>(cached.subList(0, limit))
                    : new ArrayList<>(cached);
        }

        int fetchLimit = Math.max(limit, 200);
        List<RedditComment> fetched = databaseService.getCommentsForThread(threadId, fetchLimit);
        threadCommentsCache.put(threadId, fetched);

        return (limit > 0 && fetched.size() > limit) ? fetched.subList(0, limit) : fetched;
    }

    /**
     * Returns all comments across all threads directly from the database.
     * Not cached — used by the graph view for full-graph construction where
     * completeness matters more than speed.
     */
    public List<RedditComment> getAllComments() {
        return databaseService.getAllComments();
    }

    /**
     * Deletes threads (and their full entity trees) older than
     * {@code maxAgeSeconds}, then rebuilds both caches from scratch.
     *
     * <p>
     * Both caches are cleared before warmup to ensure no stale entries
     * from deleted threads survive the cleanup cycle.
     *
     * @return future completing with the count of deleted threads
     */
    public CompletableFuture<Integer> cleanupOldThreads(long maxAgeSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            int deleted = databaseService.cleanupOldThreads(maxAgeSeconds);
            threadCache.clear();
            threadCommentsCache.clear();
            warmup();
            return deleted;
        }, dbExecutor);
    }

    /**
     * Fetches the {@code limit} most recently active threads from the DB
     * and populates the cache. Used during initial startup when the cache
     * is cold and only a window of recent data is needed.
     */
    public List<RedditThread> getRecentThreads(int limit) {
        List<RedditThread> dbList = databaseService.getRecentThreads(limit);
        for (RedditThread t : dbList)
            threadCache.put(t.id(), t);
        return dbList;
    }
}
