package de.bsommerfeld.wsbg.terminal.db;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import java.util.List;

/**
 * Interface for Database Operations.
 * Abstraction allows swapping between PROD (Sqlite) and TEST (No-Op) modes.
 */
public interface DatabaseService {

    void saveThread(RedditThread thread);

    void saveThreadsBatch(List<RedditThread> threads);

    void saveComment(RedditComment comment);

    RedditThread getThread(String id);

    List<RedditThread> getAllThreads();

    List<RedditComment> getCommentsForThread(String threadId, int limit);

    List<RedditComment> getAllComments();

    List<RedditThread> getRecentThreads(int limit);

    int cleanupOldThreads(long maxAgeSeconds);
}
