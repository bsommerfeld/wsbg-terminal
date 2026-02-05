package de.bsommerfeld.wsbg.terminal.db;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the in-memory TestDatabaseService used during TEST mode.
 * Validates pre-seeded data, save semantics, and retrieval behavior.
 */
class TestDatabaseServiceTest {

    private TestDatabaseService db;

    @BeforeEach
    void setUp() {
        db = new TestDatabaseService();
    }

    @Test
    void constructor_shouldPreSeedWithData() {
        assertFalse(db.getAllThreads().isEmpty(), "Should be pre-seeded with threads");
        assertFalse(db.getAllComments().isEmpty(), "Should be pre-seeded with comments");
    }

    @Test
    void getAllThreads_shouldReturnNonEmptyList() {
        assertTrue(db.getAllThreads().size() > 0);
    }

    @Test
    void getThread_shouldReturnSeededThread() {
        List<RedditThread> all = db.getAllThreads();
        String firstId = all.get(0).id();

        RedditThread loaded = db.getThread(firstId);
        assertNotNull(loaded);
        assertEquals(firstId, loaded.id());
    }

    @Test
    void getThread_shouldReturnNullForNonexistent() {
        assertNull(db.getThread("nonexistent_id"));
    }

    @Test
    void saveThread_shouldBeRetrievable() {
        long now = System.currentTimeMillis() / 1000;
        var thread = new RedditThread("t3_custom", "wsb", "Custom", "author", "text",
                now, "/p", 50, 0.8, 5, now, null);
        db.saveThread(thread);

        RedditThread loaded = db.getThread("t3_custom");
        assertNotNull(loaded);
        assertEquals("Custom", loaded.title());
    }

    @Test
    void saveThread_shouldNotDuplicate() {
        int initialSize = db.getAllThreads().size();

        long now = System.currentTimeMillis() / 1000;
        var thread = new RedditThread("t3_dup", "wsb", "Dup", "a", null,
                now, "/p", 1, 0.5, 0, now, null);
        db.saveThread(thread);
        db.saveThread(thread);

        assertEquals(initialSize + 1, db.getAllThreads().size());
    }

    @Test
    void saveComment_shouldBeRetrievable() {
        long now = System.currentTimeMillis() / 1000;
        var thread = new RedditThread("t3_c", "wsb", "T", "a", null,
                now, "/p", 1, 0.5, 0, now, null);
        db.saveThread(thread);

        var comment = new RedditComment("t1_custom", "t3_c", "t3_c", "u", "body", 1, now, now, now);
        db.saveComment(comment);

        List<RedditComment> all = db.getAllComments();
        assertTrue(all.stream().anyMatch(c -> "t1_custom".equals(c.id())));
    }

    @Test
    void getCommentsForThread_shouldFilterByThreadId() {
        long now = System.currentTimeMillis() / 1000;
        var thread = new RedditThread("t3_filter", "wsb", "T", "a", null,
                now, "/p", 1, 0.5, 0, now, null);
        db.saveThread(thread);

        // saveThread eagerly generates comments, so additional saves add to the list
        db.saveComment(new RedditComment("t1_a", "t3_filter", "t3_filter", "u", "a", 1, now, now, now));
        db.saveComment(new RedditComment("t1_b", "t3_filter", "t3_filter", "u", "b", 1, now, now, now));

        List<RedditComment> comments = db.getCommentsForThread("t3_filter", 100);
        // All returned comments should belong to the requested thread
        assertTrue(comments.stream().allMatch(c -> "t3_filter".equals(c.threadId())));
        // Should contain our manually added comments on top of the auto-generated ones
        assertTrue(comments.stream().anyMatch(c -> "t1_a".equals(c.id())));
        assertTrue(comments.stream().anyMatch(c -> "t1_b".equals(c.id())));
    }

    @Test
    void getCommentsForThread_shouldRespectLimit() {
        long now = System.currentTimeMillis() / 1000;
        var thread = new RedditThread("t3_lim", "wsb", "T", "a", null,
                now, "/p", 1, 0.5, 0, now, null);
        db.saveThread(thread);

        for (int i = 0; i < 10; i++) {
            db.saveComment(new RedditComment("t1_lim_" + i, "t3_lim", "t3_lim",
                    "u", "body" + i, i, now + i, now, now));
        }

        List<RedditComment> limited = db.getCommentsForThread("t3_lim", 3);
        assertTrue(limited.size() <= 3);
    }

    @Test
    void getRecentThreads_shouldLimitResults() {
        List<RedditThread> recent = db.getRecentThreads(3);
        assertTrue(recent.size() <= 3);
    }

    @Test
    void cleanupOldThreads_shouldBeNoOp() {
        int sizeBefore = db.getAllThreads().size();
        int removed = db.cleanupOldThreads(1);
        int sizeAfter = db.getAllThreads().size();

        assertEquals(0, removed);
        assertEquals(sizeBefore, sizeAfter);
    }
}
