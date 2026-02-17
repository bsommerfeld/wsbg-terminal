package de.bsommerfeld.wsbg.terminal.db;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.util.TestDataGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link DatabaseService} for TEST mode — no disk I/O, no SQLite,
 * no schema migration. Bound by Guice when the application starts with the
 * {@code --test} flag.
 *
 * <h3>Startup behavior</h3>
 * The constructor pre-seeds the store with 20 threads (24h time spread) and
 * 10–30 nested comments per thread. This means the graph view, sidebar, and
 * passive monitor all see a populated dataset immediately — no waiting for
 * scrape cycles.
 *
 * <h3>Runtime behavior</h3>
 * <ul>
 * <li>{@link #saveThread} eagerly generates comments for new threads so that
 * {@link #getAllComments()} returns them on the next graph refresh without
 * requiring a separate {@link #getCommentsForThread} call first</li>
 * <li>{@link #getCommentsForThread} lazily generates comments on first access
 * if none exist yet (fallback for threads that bypassed
 * {@code saveThread})</li>
 * <li>{@link #cleanupOldThreads} is a no-op — test data is ephemeral
 * anyway</li>
 * </ul>
 *
 * <h3>What the output looks like</h3>
 * See {@link TestDataGenerator} for exact data distributions (subreddits,
 * score ranges, nesting depth, image probabilities).
 */
@Singleton
public class TestDatabaseService implements DatabaseService {

    private static final Logger LOG = LoggerFactory.getLogger(TestDatabaseService.class);

    private final Map<String, RedditThread> memoryStore = new ConcurrentHashMap<>();
    private final Map<String, List<RedditComment>> commentStore = new ConcurrentHashMap<>();

    public TestDatabaseService() {
        LOG.warn("#######################################################");
        LOG.warn("#  TEST MODE ENABLED: Database persistence is DISABLED #");
        LOG.warn("#######################################################");

        for (RedditThread t : TestDataGenerator.generateThreads(20)) {
            memoryStore.put(t.id(), t);
            int count = 10 + (int) (Math.random() * 20);
            commentStore.put(t.id(), TestDataGenerator.generateCommentsRecursive(t.id(), count));
        }
    }

    @Override
    public void saveThread(RedditThread thread) {
        memoryStore.put(thread.id(), thread);

        // Eager comment generation ensures getAllComments() returns data
        // on the next graph refresh, instead of requiring a lazy
        // getCommentsForThread call first.
        commentStore.computeIfAbsent(thread.id(), id -> {
            int count = 10 + (int) (Math.random() * 20);
            return TestDataGenerator.generateCommentsRecursive(id, count);
        });
    }

    @Override
    public void saveThreadsBatch(List<RedditThread> threads) {
        threads.forEach(this::saveThread);
    }

    @Override
    public void saveComment(RedditComment comment) {
        commentStore.computeIfAbsent(comment.threadId(), k -> new ArrayList<>()).add(comment);
    }

    @Override
    public RedditThread getThread(String id) {
        return memoryStore.get(id);
    }

    @Override
    public List<RedditThread> getAllThreads() {
        return new ArrayList<>(memoryStore.values());
    }

    @Override
    public List<RedditComment> getCommentsForThread(String threadId, int limit) {
        List<RedditComment> comments = commentStore.computeIfAbsent(threadId, id -> {
            int count = (limit > 0) ? limit : 20;
            return TestDataGenerator.generateCommentsRecursive(id, count);
        });

        if (limit > 0 && comments.size() > limit) {
            return comments.subList(0, limit);
        }
        return comments;
    }

    @Override
    public List<RedditComment> getAllComments() {
        List<RedditComment> all = new ArrayList<>();
        commentStore.values().forEach(all::addAll);
        return all;
    }

    @Override
    public List<RedditThread> getRecentThreads(int limit) {
        List<RedditThread> all = new ArrayList<>(memoryStore.values());
        all.sort((a, b) -> Long.compare(b.lastActivityUtc(), a.lastActivityUtc()));
        if (limit > 0 && all.size() > limit)
            return all.subList(0, limit);
        return all;
    }

    /**
     * No-op — test data is ephemeral and does not accumulate enough to require
     * cleanup.
     */
    @Override
    public int cleanupOldThreads(long maxAgeSeconds) {
        return 0;
    }
}
