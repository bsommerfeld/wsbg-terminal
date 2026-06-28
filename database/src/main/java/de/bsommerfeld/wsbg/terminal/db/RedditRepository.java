package de.bsommerfeld.wsbg.terminal.db;

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

/**
 * In-memory store for Reddit threads and their comment trees. Per-session
 * only — when the app exits, everything is gone. This is intentional: stale
 * Reddit data on disk produces ghost clusters when the next session starts
 * from snapshots of posts that no longer exist on Reddit.
 *
 * <p>
 * The async write API ({@link CompletableFuture}-returning saves) is kept
 * for source-compatibility with the rest of the codebase, but every save is
 * effectively synchronous now — the map is updated and a completed future
 * is returned.
 */
@Singleton
public class RedditRepository {

    private static final Logger LOG = LoggerFactory.getLogger(RedditRepository.class);

    private final Map<String, RedditThread> threadCache = new ConcurrentHashMap<>();
    private final Map<String, List<RedditComment>> threadCommentsCache = new ConcurrentHashMap<>();

    public RedditRepository() {
    }

    public void shutdown() {
        LOG.info("Shutting down RedditRepository (in-memory, nothing to flush).");
    }

    /** Drops every thread and comment. Used by the editorial-lab "Reset" action. */
    public void clear() {
        threadCache.clear();
        threadCommentsCache.clear();
    }

    // -- Writes --

    public CompletableFuture<Void> saveThread(RedditThread thread) {
        threadCache.put(thread.id(), thread);
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> saveThreadsBatch(List<RedditThread> threads) {
        if (threads == null || threads.isEmpty())
            return CompletableFuture.completedFuture(null);
        for (RedditThread t : threads)
            threadCache.put(t.id(), t);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Saves a comment and advances the owning thread's {@code lastActivityUtc}
     * if the comment is newer.
     */
    public CompletableFuture<Void> saveComment(RedditComment comment) {
        String threadId = comment.threadId();
        if (threadId != null && !"unknown".equals(threadId)) {
            threadCommentsCache.compute(threadId, (k, list) -> {
                List<RedditComment> updated = list == null ? new ArrayList<>() : new ArrayList<>(list);
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
                    t.imageUrls(), t.pollData()));
        }
        return CompletableFuture.completedFuture(null);
    }

    // -- Reads --

    public RedditThread getThread(String id) {
        return threadCache.get(id);
    }

    public List<RedditThread> getAllThreads() {
        return new ArrayList<>(threadCache.values());
    }

    public List<RedditComment> getCommentsForThread(String threadId, int limit) {
        List<RedditComment> cached = threadCommentsCache.get(threadId);
        if (cached == null)
            return new ArrayList<>();
        if (limit > 0 && cached.size() > limit)
            return new ArrayList<>(cached.subList(0, limit));
        return new ArrayList<>(cached);
    }

    public List<RedditComment> getAllComments() {
        List<RedditComment> all = new ArrayList<>();
        for (List<RedditComment> list : threadCommentsCache.values())
            all.addAll(list);
        return all;
    }

    /** Returns up to {@code limit} most recently active threads. */
    public List<RedditThread> getRecentThreads(int limit) {
        return threadCache.values().stream()
                .sorted(Comparator.comparingLong(RedditThread::lastActivityUtc).reversed())
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .toList();
    }

    /**
     * Removes threads whose {@code lastActivityUtc} is older than
     * {@code maxAgeSeconds} ago, along with their comment trees.
     *
     * @return completed future with the count of removed threads
     */
    public CompletableFuture<Integer> cleanupOldThreads(long maxAgeSeconds) {
        long cutoff = (System.currentTimeMillis() / 1000) - maxAgeSeconds;
        int before = threadCache.size();
        threadCache.values().removeIf(t -> t.lastActivityUtc() < cutoff);
        threadCommentsCache.keySet().retainAll(threadCache.keySet());
        return CompletableFuture.completedFuture(before - threadCache.size());
    }
}
