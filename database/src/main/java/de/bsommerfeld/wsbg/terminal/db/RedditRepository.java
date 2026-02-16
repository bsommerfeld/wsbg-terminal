package de.bsommerfeld.wsbg.terminal.db;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Caching Repository for Reddit Data.
 * Acts as the Single Source of Truth, maintaining a Write-Through cache over
 * the DatabaseService.
 */
@Singleton
public class RedditRepository {

    private static final Logger LOG = LoggerFactory.getLogger(RedditRepository.class);
    private final DatabaseService databaseService;
    private final java.util.concurrent.ExecutorService dbExecutor = java.util.concurrent.Executors
            .newSingleThreadExecutor();

    // Cache: ThreadID -> RedditThread
    private final Map<String, RedditThread> threadCache = new ConcurrentHashMap<>();

    // Cache: ThreadID -> List of Comments
    // We assume if a key exists, the list is reasonably fresh.
    private final Map<String, List<RedditComment>> threadCommentsCache = new ConcurrentHashMap<>();

    @Inject
    public RedditRepository(DatabaseService databaseService) {
        this.databaseService = databaseService;
        // Optional: Warmup can be triggered specifically, or lazy.
    }

    public void warmup() {
        LOG.info("Warming up RedditRepository Cache...");
        List<RedditThread> all = databaseService.getAllThreads();
        for (RedditThread t : all) {
            threadCache.put(t.getId(), t);
        }
        LOG.info("Cache warmed with {} threads.", all.size());
    }

    public void shutdown() {
        LOG.info("Shutting down RedditRepository (Waiting for DB writes)...");
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
                LOG.warn("RedditRepository forced shutdown (timed out).");
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("RedditRepository saved.");
    }

    // --- Writes (Async Write-Through) ---

    // Return CompletableFuture so caller CAN react, but doesn't have to
    public java.util.concurrent.CompletableFuture<Void> saveThread(RedditThread thread) {
        // 1. Write to Cache (Immediate)
        threadCache.put(thread.getId(), thread);

        // 2. Write to DB (Async)
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            databaseService.saveThread(thread);
        }, dbExecutor);
    }

    public java.util.concurrent.CompletableFuture<Void> saveThreadsBatch(java.util.List<RedditThread> threads) {
        if (threads == null || threads.isEmpty())
            return java.util.concurrent.CompletableFuture.completedFuture(null);

        // 1. Write to Cache
        for (RedditThread t : threads) {
            threadCache.put(t.getId(), t);
        }

        // 2. Write to DB (Async Batch)
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            databaseService.saveThreadsBatch(threads);
        }, dbExecutor);
    }

    public java.util.concurrent.CompletableFuture<Void> saveComment(RedditComment comment) {
        // 1. Write to Cache (Immediate)
        String threadId = comment.getThreadId();
        if (threadId != null && !threadId.equals("unknown")) {
            threadCommentsCache.computeIfPresent(threadId, (k, list) -> {
                List<RedditComment> newList = new ArrayList<>(list);
                newList.removeIf(c -> c.getId().equals(comment.getId()));
                newList.add(comment);
                newList.sort(Comparator.comparingLong(RedditComment::getCreatedUtc).reversed());
                return newList;
            });

            // Update Thread Cache (Bump Activity)
            threadCache.computeIfPresent(threadId, (k, t) -> {
                long newActivity = Math.max(t.getLastActivityUtc(), comment.getCreatedUtc());
                return new RedditThread(
                        t.getId(), t.getSubreddit(), t.getTitle(), t.getAuthor(), t.getTextContent(),
                        t.getCreatedUtc(), t.getPermalink(), t.getScore(), t.getUpvoteRatio(),
                        t.getNumComments(),
                        newActivity,
                        t.getImageUrl());
            });
        }

        // 2. Write to DB (Async)
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            databaseService.saveComment(comment);
        }, dbExecutor);
    }

    // --- Reads (Read-Through) ---
    // Reads stay synchronous for simplicity (Cache Hit = Instant).
    // Miss requires blocking simply because the contract expects an object.

    public RedditThread getThread(String id) {
        if (threadCache.containsKey(id)) {
            return threadCache.get(id);
        }
        RedditThread t = databaseService.getThread(id);
        if (t != null) {
            threadCache.put(id, t);
        }
        return t;
    }

    /**
     * returns all threads from cache (warming up if empty)
     */
    public List<RedditThread> getAllThreads() {
        if (threadCache.isEmpty()) {
            warmup();
        }
        return new ArrayList<>(threadCache.values());
    }

    public List<RedditComment> getCommentsForThread(String threadId, int limit) {
        // Try Cache
        if (threadCommentsCache.containsKey(threadId)) {
            List<RedditComment> cached = threadCommentsCache.get(threadId);
            if (limit > 0 && cached.size() > limit) {
                return new ArrayList<>(cached.subList(0, limit));
            }
            return new ArrayList<>(cached);
        }

        // Fetch from DB
        int fetchLimit = Math.max(limit, 200);
        List<RedditComment> fetched = databaseService.getCommentsForThread(threadId, fetchLimit);

        // Populate Cache
        threadCommentsCache.put(threadId, fetched);

        if (limit > 0 && fetched.size() > limit) {
            return fetched.subList(0, limit);
        }
        return fetched;
    }

    public List<RedditComment> getAllComments() {
        // Can optionally cache this entirely too?
        return databaseService.getAllComments();
    }

    public java.util.concurrent.CompletableFuture<Integer> cleanupOldThreads(long maxAgeSeconds) {
        // Async cleanup
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            int deleted = databaseService.cleanupOldThreads(maxAgeSeconds);
            // Invalidate Cache after DB operation checks out
            threadCache.clear();
            threadCommentsCache.clear();
            warmup();
            return deleted;
        }, dbExecutor);
    }

    public List<RedditThread> getRecentThreads(int limit) {
        List<RedditThread> dbList = databaseService.getRecentThreads(limit);
        List<RedditThread> resolved = new ArrayList<>();

        for (RedditThread t : dbList) {
            // Update Cache if DB has newer or different
            threadCache.put(t.getId(), t);
            resolved.add(t);
        }
        return resolved;
    }
}
