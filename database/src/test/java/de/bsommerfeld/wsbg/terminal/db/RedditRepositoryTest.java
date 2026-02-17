package de.bsommerfeld.wsbg.terminal.db;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests RedditRepository's caching layer and async write-through behavior.
 * The DatabaseService is mocked to isolate the caching logic.
 */
@ExtendWith(MockitoExtension.class)
class RedditRepositoryTest {

    @Mock
    private DatabaseService databaseService;

    private RedditRepository repository;

    @BeforeEach
    void setUp() {
        repository = new RedditRepository(databaseService);
    }

    // -- Thread Operations --

    @Test
    void saveThread_shouldAddToCache() {
        RedditThread thread = thread("t3_1");
        repository.saveThread(thread);

        RedditThread cached = repository.getThread("t3_1");
        assertNotNull(cached);
        assertEquals("t3_1", cached.id());
    }

    @Test
    void saveThread_shouldDelegateToDatabase() throws Exception {
        RedditThread thread = thread("t3_1");
        repository.saveThread(thread);

        // Allow async write to complete
        Thread.sleep(200);
        verify(databaseService).saveThread(thread);
    }

    @Test
    void getThread_shouldReturnNullForUnknown() {
        when(databaseService.getThread("nonexistent")).thenReturn(null);
        assertNull(repository.getThread("nonexistent"));
    }

    @Test
    void getThread_shouldFallbackToDbOnCacheMiss() {
        RedditThread dbThread = thread("t3_db");
        when(databaseService.getThread("t3_db")).thenReturn(dbThread);

        RedditThread result = repository.getThread("t3_db");
        assertNotNull(result);
        assertEquals("t3_db", result.id());
        verify(databaseService).getThread("t3_db");
    }

    @Test
    void getAllThreads_shouldReturnCachedItems() {
        repository.saveThread(thread("t3_1"));
        repository.saveThread(thread("t3_2"));

        List<RedditThread> all = repository.getAllThreads();
        assertEquals(2, all.size());
    }

    @Test
    void saveThreadsBatch_shouldAddAllToCache() {
        List<RedditThread> batch = List.of(thread("t3_a"), thread("t3_b"), thread("t3_c"));
        repository.saveThreadsBatch(batch);

        assertEquals(3, repository.getAllThreads().size());
    }

    @Test
    void saveThreadsBatch_shouldHandleNullGracefully() {
        // Should return a completed future, not throw
        CompletableFuture<Void> result = repository.saveThreadsBatch(null);
        assertNotNull(result);
        assertTrue(result.isDone());
    }

    @Test
    void saveThreadsBatch_shouldHandleEmptyListGracefully() {
        CompletableFuture<Void> result = repository.saveThreadsBatch(List.of());
        assertNotNull(result);
        assertTrue(result.isDone());
    }

    // -- Comment Operations --

    @Test
    void saveComment_shouldDelegateToDatabase() throws Exception {
        RedditComment comment = comment("t1_1", "t3_1");
        repository.saveComment(comment);

        Thread.sleep(200);
        verify(databaseService).saveComment(comment);
    }

    @Test
    void getCommentsForThread_shouldFetchFromDbOnCacheMiss() {
        List<RedditComment> dbComments = List.of(comment("t1_1", "t3_a"), comment("t1_2", "t3_a"));
        when(databaseService.getCommentsForThread("t3_a", 200)).thenReturn(dbComments);

        List<RedditComment> result = repository.getCommentsForThread("t3_a", 10);
        assertFalse(result.isEmpty());
    }

    @Test
    void getCommentsForThread_shouldReturnCachedAfterFirstFetch() {
        List<RedditComment> dbComments = List.of(comment("t1_1", "t3_a"));
        when(databaseService.getCommentsForThread("t3_a", 200)).thenReturn(dbComments);

        // First call triggers DB fetch
        repository.getCommentsForThread("t3_a", 10);
        // Second call should hit cache
        repository.getCommentsForThread("t3_a", 10);

        // DB should only be hit once
        verify(databaseService, times(1)).getCommentsForThread("t3_a", 200);
    }

    @Test
    void getCommentsForThread_shouldRespectLimit() {
        List<RedditComment> dbComments = List.of(
                comment("t1_1", "t3_a"), comment("t1_2", "t3_a"),
                comment("t1_3", "t3_a"), comment("t1_4", "t3_a"));
        when(databaseService.getCommentsForThread("t3_a", 200)).thenReturn(dbComments);

        List<RedditComment> result = repository.getCommentsForThread("t3_a", 2);
        assertEquals(2, result.size());
    }

    // -- Warmup --

    @Test
    void warmup_shouldLoadThreadsFromDatabase() {
        RedditThread seedThread = thread("t3_seed");
        when(databaseService.getAllThreads()).thenReturn(List.of(seedThread));

        repository.warmup();

        assertNotNull(repository.getThread("t3_seed"));
    }

    // -- Shutdown --

    @Test
    void shutdown_shouldNotThrow() {
        assertDoesNotThrow(() -> repository.shutdown());
    }

    // -- Helpers --

    private static RedditThread thread(String id) {
        long now = System.currentTimeMillis() / 1000;
        return new RedditThread(id, "wsb", "Title", "author", "text",
                now, "/p", 1, 0.5, 0, now, null);
    }

    private static RedditComment comment(String id, String threadId) {
        long now = System.currentTimeMillis() / 1000;
        return new RedditComment(id, threadId, threadId, "user", "body", 1, now, now, now);
    }
}
