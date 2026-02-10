package de.bsommerfeld.wsbg.terminal.db;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * No-Op Implementation of DatabaseService for TEST mode.
 * Does not persist anything. Returns empty lists.
 */
@Singleton
public class TestDatabaseService implements DatabaseService {

    private static final Logger LOG = LoggerFactory.getLogger(TestDatabaseService.class);
    private final java.util.Map<String, RedditThread> memoryStore = new java.util.concurrent.ConcurrentHashMap<>();
    // Map<ThreadID, List<RedditComment>>
    private final java.util.Map<String, List<RedditComment>> commentStore = new java.util.concurrent.ConcurrentHashMap<>();

    public TestDatabaseService() {
        LOG.warn("#######################################################");
        LOG.warn("#  TEST MODE ENABLED: Database persistence is DISABLED #");
        LOG.warn("#  Using In-Memory Stub with Random Data Generation   #");
        LOG.warn("#######################################################");

        // Pre-fill with some random data
        List<RedditThread> initData = de.bsommerfeld.wsbg.terminal.core.util.TestDataGenerator.generateThreads(20);
        for (RedditThread t : initData) {
            memoryStore.put(t.getId(), t);

            // Generate initial comments for this thread (10-30 comments)
            int commentCount = 10 + (int) (Math.random() * 20);
            List<RedditComment> threadComments = de.bsommerfeld.wsbg.terminal.core.util.TestDataGenerator
                    .generateCommentsRecursive(t.getId(), commentCount);

            commentStore.put(t.getId(), threadComments);
        }
    }

    @Override
    public void saveThread(RedditThread thread) {
        LOG.debug("[TEST] Stub saveThread: {}", thread.getId());
        memoryStore.put(thread.getId(), thread);
    }

    @Override
    public void saveThreadsBatch(List<RedditThread> threads) {
        LOG.debug("[TEST] Stub saveThreadsBatch: {} items", threads.size());
        for (RedditThread t : threads) {
            memoryStore.put(t.getId(), t);
        }
    }

    @Override
    public void saveComment(RedditComment comment) {
        LOG.debug("[TEST] Stub saveComment: {}", comment.getId());
        commentStore.computeIfAbsent(comment.getThreadId(), k -> new java.util.ArrayList<>())
                .add(comment);
    }

    @Override
    public RedditThread getThread(String id) {
        return memoryStore.get(id);
    }

    @Override
    public List<RedditThread> getAllThreads() {
        return new java.util.ArrayList<>(memoryStore.values());
    }

    @Override
    public List<RedditComment> getCommentsForThread(String threadId, int limit) {
        LOG.debug("[TEST] Fetching comments for thread: {}", threadId);

        List<RedditComment> comments = commentStore.get(threadId);

        // Lazy generation if missing (e.g. for new dynamic threads)
        if (comments == null || comments.isEmpty()) {
            LOG.warn("Thread {} has no comments in stub DB, generating fresh batch...", threadId);
            int count = (limit > 0) ? limit : 20;
            comments = de.bsommerfeld.wsbg.terminal.core.util.TestDataGenerator.generateCommentsRecursive(threadId,
                    count);
            commentStore.put(threadId, comments);
        }

        // Apply limit if needed (simplified)
        if (limit > 0 && comments.size() > limit) {
            return comments.subList(0, limit);
        }

        return comments;
    }

    @Override
    public List<RedditComment> getAllComments() {
        List<RedditComment> all = new java.util.ArrayList<>();
        for (List<RedditComment> list : commentStore.values()) {
            all.addAll(list);
        }
        return all;
    }

    @Override
    public List<RedditThread> getRecentThreads(int limit) {
        List<RedditThread> all = new java.util.ArrayList<>(memoryStore.values());
        // Sort by Last Activity Desc
        all.sort((a, b) -> Long.compare(b.getLastActivityUtc(), a.getLastActivityUtc()));

        if (limit > 0 && all.size() > limit) {
            return all.subList(0, limit);
        }
        return all;
    }

    @Override
    public int cleanupOldThreads(long maxAgeSeconds) {
        LOG.debug("[TEST] Stub cleanupOldThreads");
        return 0;
    }
}
