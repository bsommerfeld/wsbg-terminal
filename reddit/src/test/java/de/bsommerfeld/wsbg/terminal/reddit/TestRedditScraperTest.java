package de.bsommerfeld.wsbg.terminal.reddit;

import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the test-mode stub scraper that produces synthetic data
 * without any network access.
 */
class TestRedditScraperTest {

    private RedditRepository repository;
    private TestRedditScraper scraper;

    @BeforeEach
    void setUp() {
        repository = mock(RedditRepository.class);
        scraper = new TestRedditScraper(repository);
    }

    @Test
    void scanSubreddit_shouldReturnEmptyStatsOnNonGenerationCycle() {
        // First call (callCount=1) should NOT generate (interval=3)
        var stats = scraper.scanSubreddit("wsb");
        assertEquals(0, stats.newThreads);
        assertFalse(stats.hasUpdates());
    }

    @Test
    void scanSubreddit_shouldGenerateThreadsEveryThirdCall() {
        scraper.scanSubreddit("wsb"); // call 1
        scraper.scanSubreddit("wsb"); // call 2
        var stats = scraper.scanSubreddit("wsb"); // call 3

        assertEquals(2, stats.newThreads);
        assertTrue(stats.hasUpdates());
        verify(repository).saveThreadsBatch(anyList());
    }

    @Test
    void scanSubredditHot_shouldDelegateToScanSubreddit() {
        scraper.scanSubreddit("wsb"); // call 1
        scraper.scanSubreddit("wsb"); // call 2
        var stats = scraper.scanSubredditHot("wsb"); // call 3

        assertEquals(2, stats.newThreads);
    }

    @Test
    void updateThreadsBatch_shouldReturnEmptyStats() {
        var stats = scraper.updateThreadsBatch(List.of("t3_1", "t3_2"));
        assertNotNull(stats);
        assertEquals(0, stats.newThreads);
        assertFalse(stats.hasUpdates());
    }

    @Test
    void fetchThreadContext_shouldReturnSyntheticContext() {
        var ctx = scraper.fetchThreadContext("/r/wsb/comments/abc123/test");

        assertNotNull(ctx.title);
        assertTrue(ctx.title.contains("TEST"));
        assertNotNull(ctx.selftext);
        assertFalse(ctx.comments.isEmpty());
    }

    @Test
    void fetchThreadContext_shouldGenerate10Comments() {
        var ctx = scraper.fetchThreadContext("/r/wsb/comments/xyz");
        assertEquals(10, ctx.comments.size());
    }
}
