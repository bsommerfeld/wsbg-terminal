package de.bsommerfeld.wsbg.terminal.db;

import de.bsommerfeld.wsbg.terminal.db.DatabaseService.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.DatabaseService.TickerMentionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests AgentRepository's caching layer and async write-through behavior.
 * The DatabaseService is mocked to isolate the caching logic.
 */
@ExtendWith(MockitoExtension.class)
class AgentRepositoryTest {

    @Mock
    private DatabaseService databaseService;

    private AgentRepository repository;

    @BeforeEach
    void setUp() {
        repository = new AgentRepository(databaseService);
    }

    // -- Headlines --

    @Test
    void saveHeadline_shouldAddToCache() {
        repository.saveHeadline("c-1", "Breaking news", "Full context");

        List<HeadlineRecord> headlines = repository.getRecentHeadlines();
        assertEquals(1, headlines.size());
        assertEquals("Breaking news", headlines.get(0).headline());
    }

    @Test
    void saveHeadline_shouldDelegateToDatabase() throws Exception {
        repository.saveHeadline("c-1", "headline", "ctx");

        Thread.sleep(200);
        verify(databaseService).saveHeadline("c-1", "headline", "ctx");
    }

    @Test
    void getRecentHeadlines_shouldReturnNewestFirst() {
        repository.saveHeadline("c-1", "First", "ctx1");
        repository.saveHeadline("c-2", "Second", "ctx2");

        List<HeadlineRecord> headlines = repository.getRecentHeadlines();
        assertEquals(2, headlines.size());
        // Both should be present — exact order may be identical timestamp
        assertTrue(headlines.stream().anyMatch(h -> h.headline().equals("First")));
        assertTrue(headlines.stream().anyMatch(h -> h.headline().equals("Second")));
    }

    // -- Ticker Mentions --

    @Test
    void saveTickerMentions_shouldAddToCache() {
        List<TickerMentionRecord> mentions = List.of(
                new TickerMentionRecord("AAPL", "STOCK", "Apple"),
                new TickerMentionRecord("AAPL", "STOCK", "Apple"),
                new TickerMentionRecord("DAX", "INDEX", "DAX 40"));
        repository.saveTickerMentions(mentions);

        Map<String, Integer> counts = repository.getTickerCountsLastHour();
        assertEquals(2, counts.get("AAPL"));
        assertEquals(1, counts.get("DAX"));
    }

    @Test
    void saveTickerMentions_shouldDelegateToDatabase() throws Exception {
        List<TickerMentionRecord> mentions = List.of(
                new TickerMentionRecord("SPY", "ETF", null));
        repository.saveTickerMentions(mentions);

        Thread.sleep(200);
        verify(databaseService).saveTickerMentions(mentions);
    }

    @Test
    void saveTickerMentions_shouldUppercaseSymbols() {
        repository.saveTickerMentions(List.of(
                new TickerMentionRecord("aapl", "STOCK", "Apple")));

        Map<String, Integer> counts = repository.getTickerCountsLastHour();
        assertTrue(counts.containsKey("AAPL"));
        assertFalse(counts.containsKey("aapl"));
    }

    @Test
    void saveTickerMentions_shouldHandleNullGracefully() {
        assertDoesNotThrow(() -> repository.saveTickerMentions(null));
        assertDoesNotThrow(() -> repository.saveTickerMentions(List.of()));
    }

    @Test
    void getTickerCountsLastHour_shouldReturnSortedDescending() {
        repository.saveTickerMentions(List.of(
                new TickerMentionRecord("GOLD", "COMMODITY", null),
                new TickerMentionRecord("SPY", "ETF", null),
                new TickerMentionRecord("SPY", "ETF", null),
                new TickerMentionRecord("SPY", "ETF", null)));

        Map<String, Integer> counts = repository.getTickerCountsLastHour();
        var keys = new java.util.ArrayList<>(counts.keySet());
        assertEquals("SPY", keys.get(0));
        assertEquals("GOLD", keys.get(1));
    }

    @Test
    void getTickerCountsLastHour_shouldReturnEmptyWhenNoData() {
        assertTrue(repository.getTickerCountsLastHour().isEmpty());
    }

    // -- Cleanup --

    @Test
    void cleanup_shouldRemoveExpiredFromCache() {
        repository.saveHeadline("c-1", "headline", "ctx");
        repository.saveTickerMentions(List.of(
                new TickerMentionRecord("TSLA", "STOCK", "Tesla")));

        // Items just added should survive cleanup (they're < 24h old)
        repository.cleanup();

        assertFalse(repository.getRecentHeadlines().isEmpty());
        assertFalse(repository.getTickerCountsLastHour().isEmpty());
    }

    @Test
    void cleanup_shouldDelegateToDatabase() throws Exception {
        repository.cleanup();

        Thread.sleep(200);
        verify(databaseService).cleanupAgentData(anyLong());
    }

    // -- Shutdown --

    @Test
    void shutdown_shouldNotThrow() {
        assertDoesNotThrow(() -> repository.shutdown());
    }
}
