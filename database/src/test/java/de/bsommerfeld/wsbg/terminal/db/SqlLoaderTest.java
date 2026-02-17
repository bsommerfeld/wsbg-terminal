package de.bsommerfeld.wsbg.terminal.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests SqlLoader's ability to load SQL files from classpath resources.
 * SqlLoader reads from "sql/{name}.sql" — the schema.sql lives at the
 * root classpath level and is NOT loaded through SqlLoader.
 */
class SqlLoaderTest {

    @Test
    void load_shouldReturnUpsertThread() {
        String sql = SqlLoader.load("upsert-thread");
        assertNotNull(sql);
        assertFalse(sql.isBlank());
        assertTrue(sql.toLowerCase().contains("insert"));
    }

    @Test
    void load_shouldReturnSelectAllThreads() {
        String sql = SqlLoader.load("select-all-threads");
        assertNotNull(sql);
        assertTrue(sql.toLowerCase().contains("select"));
    }

    @Test
    void load_shouldReturnInsertComment() {
        String sql = SqlLoader.load("insert-comment");
        assertNotNull(sql);
        assertFalse(sql.isBlank());
    }

    @Test
    void load_shouldReturnSelectCommentsForThread() {
        String sql = SqlLoader.load("select-comments-for-thread");
        assertNotNull(sql);
        assertTrue(sql.toLowerCase().contains("select"));
    }

    @Test
    void load_shouldCacheRepeatCalls() {
        String first = SqlLoader.load("upsert-thread");
        String second = SqlLoader.load("upsert-thread");
        assertSame(first, second, "Cached calls should return the same String reference");
    }

    @Test
    void load_shouldThrowForNonexistentFile() {
        assertThrows(IllegalStateException.class,
                () -> SqlLoader.load("nonexistent-sql-file"));
    }
}
