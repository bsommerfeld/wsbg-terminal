package de.bsommerfeld.wsbg.terminal.db;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;

import java.util.List;

/**
 * Persistence contract for Reddit data. All implementations must be
 * thread-safe — the {@link RedditRepository} invokes writes from a
 * single-threaded executor but reads may arrive from any thread.
 *
 * <p>
 * Two implementations exist:
 * <ul>
 * <li>{@link SqlDatabaseService} — production persistence via SQLite</li>
 * <li>{@link TestDatabaseService} — in-memory store for TEST mode,
 * pre-seeded with generated data, no disk I/O</li>
 * </ul>
 *
 * <p>
 * Switching between implementations is done at the Guice module level.
 * Callers interact exclusively through {@link RedditRepository} which adds
 * caching, async writes, and lifecycle management on top of this contract.
 */
public interface DatabaseService {

    /**
     * Persists a single thread. If a thread with the same ID already exists,
     * the implementation must upsert — updating mutable fields (score,
     * upvote_ratio, last_activity_utc) while preserving the original
     * created_utc.
     */
    void saveThread(RedditThread thread);

    /**
     * Persists multiple threads in a single transaction. Semantics are
     * identical to calling {@link #saveThread} for each entry, but
     * implementations should batch the operations for performance.
     */
    void saveThreadsBatch(List<RedditThread> threads);

    /**
     * Persists a comment and its associated content/images. The implementation
     * must also update the owning thread's {@code last_activity_utc} if the
     * comment's timestamp is newer.
     */
    void saveComment(RedditComment comment);

    /**
     * Returns a single thread by its Reddit ID ({@code t3_...}), including
     * joined content and image data. Returns {@code null} if not found.
     */
    RedditThread getThread(String id);

    /**
     * Returns all stored threads. The returned list does not include comment
     * counts — callers that need counts must query separately.
     */
    List<RedditThread> getAllThreads();

    /**
     * Returns comments belonging to the given thread, ordered by
     * {@code created_utc} descending (newest first), limited to at most
     * {@code limit} results. The implementation resolves the full comment
     * tree via the {@code parent_id} hierarchy — not just direct children.
     *
     * @param threadId the owning thread's Reddit ID
     * @param limit    maximum number of comments to return
     */
    List<RedditComment> getCommentsForThread(String threadId, int limit);

    /**
     * Returns every comment across all threads. Used by the graph view to
     * build the full entity graph. Expensive — prefer
     * {@link #getCommentsForThread} when the thread is known.
     */
    List<RedditComment> getAllComments();

    /**
     * Returns the most recent threads ordered by {@code last_activity_utc}
     * descending. Used for initial display and cache warming.
     *
     * @param limit maximum number of threads to return
     */
    List<RedditThread> getRecentThreads(int limit);

    /**
     * Deletes threads (and their entire comment trees, content, and images)
     * whose {@code last_activity_utc} is older than {@code maxAgeSeconds}
     * ago. Cleanup cascades through the entity hierarchy using a recursive
     * CTE to find all dependent comments.
     *
     * @param maxAgeSeconds maximum age in seconds; threads inactive longer
     *                      than this are deleted
     * @return number of threads deleted
     */
    int cleanupOldThreads(long maxAgeSeconds);
}
