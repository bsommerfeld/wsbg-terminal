package de.bsommerfeld.wsbg.terminal.db;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SqlDatabaseService against a real temporary SQLite
 * database.
 * Validates the full persistence lifecycle: schema init, upserts, queries, and
 * cleanup.
 *
 * Uses a custom subclass to override the connection URL and point at a temp
 * file,
 * avoiding interference with the production database or OS-specific paths.
 */
class SqlDatabaseServiceTest {

    @TempDir
    Path tempDir;

    private SqlDatabaseService db;

    @BeforeEach
    void setUp() {
        String url = "jdbc:sqlite:" + tempDir.resolve("test.db").toAbsolutePath();
        db = new SqlDatabaseService() {
            @Override
            Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url);
            }
        };
        // Re-initialize schema on the temp DB
        try (Connection conn = db.getConnection()) {
            var is = getClass().getClassLoader().getResourceAsStream("schema.sql");
            if (is != null) {
                String schema = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                for (String sql : schema.split(";\\s*(\r?\n|$)")) {
                    if (!sql.trim().isEmpty()) {
                        conn.createStatement().execute(sql.trim());
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Schema init failed", e);
        }
    }

    // -- Thread Persistence --

    @Test
    void saveThread_shouldPersistAndRetrieve() {
        RedditThread thread = thread("t3_1", "wsb", "Title", 100, 10);
        db.saveThread(thread);

        RedditThread loaded = db.getThread("t3_1");
        assertNotNull(loaded);
        assertEquals("t3_1", loaded.id());
        assertEquals("wsb", loaded.subreddit());
        assertEquals("Title", loaded.title());
        assertEquals(100, loaded.score());
    }

    @Test
    void saveThread_shouldUpsertOnConflict() {
        db.saveThread(thread("t3_1", "wsb", "Original", 10, 5));
        db.saveThread(thread("t3_1", "wsb", "Updated", 99, 50));

        RedditThread loaded = db.getThread("t3_1");
        assertNotNull(loaded);
        assertEquals(99, loaded.score());
    }

    @Test
    void getThread_shouldReturnNullForNonexistent() {
        assertNull(db.getThread("nonexistent"));
    }

    @Test
    void getAllThreads_shouldReturnAllSavedThreads() {
        db.saveThread(thread("t3_1", "wsb", "A", 1, 1));
        db.saveThread(thread("t3_2", "wsb", "B", 2, 2));
        db.saveThread(thread("t3_3", "wsb", "C", 3, 3));

        List<RedditThread> all = db.getAllThreads();
        assertEquals(3, all.size());
    }

    @Test
    void getAllThreads_shouldReturnEmptyForEmptyDb() {
        assertTrue(db.getAllThreads().isEmpty());
    }

    @Test
    void getRecentThreads_shouldReturnLimitedSet() {
        for (int i = 0; i < 10; i++) {
            db.saveThread(thread("t3_" + i, "wsb", "T" + i, i, i));
        }
        List<RedditThread> recent = db.getRecentThreads(5);
        assertEquals(5, recent.size());
    }

    @Test
    void saveThreadsBatch_shouldPersistAll() {
        List<RedditThread> batch = List.of(
                thread("t3_a", "wsb", "A", 1, 1),
                thread("t3_b", "wsb", "B", 2, 2));
        db.saveThreadsBatch(batch);

        assertEquals(2, db.getAllThreads().size());
    }

    // -- Comment Persistence --

    @Test
    void saveComment_shouldPersistAndRetrieve() {
        db.saveThread(thread("t3_1", "wsb", "T", 1, 1));
        db.saveComment(comment("t1_1", "t3_1", "t3_1", "user", "body", 5));

        List<RedditComment> comments = db.getCommentsForThread("t3_1", 100);
        assertFalse(comments.isEmpty());
        assertEquals("t1_1", comments.get(0).id());
        assertEquals("body", comments.get(0).body());
    }

    @Test
    void getCommentsForThread_shouldReturnEmptyForNonexistent() {
        assertTrue(db.getCommentsForThread("nonexistent", 100).isEmpty());
    }

    @Test
    void getCommentsForThread_shouldRespectLimit() {
        db.saveThread(thread("t3_1", "wsb", "T", 1, 1));
        for (int i = 0; i < 10; i++) {
            db.saveComment(comment("t1_" + i, "t3_1", "t3_1", "u", "b" + i, i));
        }

        List<RedditComment> limited = db.getCommentsForThread("t3_1", 3);
        assertEquals(3, limited.size());
    }

    @Test
    void getAllComments_shouldReturnAllSavedComments() {
        db.saveThread(thread("t3_1", "wsb", "T", 1, 1));
        db.saveComment(comment("t1_1", "t3_1", "t3_1", "u1", "b1", 1));
        db.saveComment(comment("t1_2", "t3_1", "t3_1", "u2", "b2", 2));

        List<RedditComment> all = db.getAllComments();
        assertEquals(2, all.size());
    }

    @Test
    void saveComment_shouldPersistImageUrls() {
        db.saveThread(thread("t3_1", "wsb", "T", 1, 1));
        var withImages = new RedditComment("t1_1", "t3_1", "t3_1", "u", "b", 1,
                1L, 1L, 1L, List.of("http://img1.png", "http://img2.png"));
        db.saveComment(withImages);

        List<RedditComment> comments = db.getCommentsForThread("t3_1", 100);
        assertFalse(comments.isEmpty());
        assertEquals(2, comments.get(0).imageUrls().size());
        assertTrue(comments.get(0).imageUrls().contains("http://img1.png"));
    }

    // -- Cleanup --

    @Test
    void cleanupOldThreads_shouldRemoveExpiredThreads() {
        long now = System.currentTimeMillis() / 1000;
        long old = now - 86400;

        db.saveThread(new RedditThread("t3_old", "wsb", "Old", "a", null,
                old, "/p", 1, 0.5, 1, old, null));
        db.saveThread(new RedditThread("t3_new", "wsb", "New", "a", null,
                now, "/p", 1, 0.5, 1, now, null));

        int removed = db.cleanupOldThreads(3600);
        assertEquals(1, removed);
        assertNull(db.getThread("t3_old"));
        assertNotNull(db.getThread("t3_new"));
    }

    @Test
    void cleanupOldThreads_shouldReturnZeroWhenNothingToClean() {
        long now = System.currentTimeMillis() / 1000;
        db.saveThread(new RedditThread("t3_1", "wsb", "T", "a", null,
                now, "/p", 1, 0.5, 1, now, null));
        assertEquals(0, db.cleanupOldThreads(3600));
    }

    @Test
    void cleanupOldThreads_shouldCascadeDeleteComments() {
        long now = System.currentTimeMillis() / 1000;
        long old = now - 86400;

        db.saveThread(new RedditThread("t3_old", "wsb", "Old", "a", null,
                old, "/p", 1, 0.5, 1, old, null));
        // Comment must also use old timestamps — saveComment updates the thread's
        // last_activity_utc, which would move it out of the cleanup window
        var oldComment = new RedditComment("t1_1", "t3_old", "t3_old", "u", "b", 1, old, old, old);
        db.saveComment(oldComment);

        db.cleanupOldThreads(3600);
        assertTrue(db.getCommentsForThread("t3_old", 100).isEmpty());
    }

    // -- Helpers --

    private static RedditThread thread(String id, String sub, String title, int score, int comments) {
        long now = System.currentTimeMillis() / 1000;
        return new RedditThread(id, sub, title, "author", "text", now, "/r/" + sub + "/" + id,
                score, 0.9, comments, now, null);
    }

    private static RedditComment comment(String id, String threadId, String parentId,
            String author, String body, int score) {
        long now = System.currentTimeMillis() / 1000;
        return new RedditComment(id, threadId, parentId, author, body, score, now, now, now);
    }
}
