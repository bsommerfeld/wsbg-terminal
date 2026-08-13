package de.bsommerfeld.wsbg.terminal.core.debug;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceHealthRegistryTest {

    private final SourceHealthRegistry registry = SourceHealthRegistry.get();

    @BeforeEach
    void reset() {
        registry.reset();
    }

    @Test
    void deliveredStampsSuccessAndResetsTheFailureStreak() {
        registry.record("mercopress", "FAILED", 0, "HTTP 503");
        registry.record("mercopress", "FAILED", 0, "HTTP 503");
        registry.record("mercopress", "DELIVERED", 7, "");
        SourceHealthRegistry.View view = registry.snapshot().get(0);
        assertEquals("mercopress", view.source());
        assertEquals(0, view.consecutiveFailures());
        assertEquals(2, view.failures());
        assertEquals(3, view.runs());
        assertEquals(7, view.lastItemCount());
        assertTrue(view.lastSuccessMs() > 0);
    }

    @Test
    void emptyIsACleanAnswerButSkippedStampsNothing() {
        registry.record("pool", "EMPTY", 0, "");
        registry.record("edgar", "SKIPPED", 0, "no ISIN");
        List<SourceHealthRegistry.View> views = registry.snapshot();
        SourceHealthRegistry.View pool = views.stream()
                .filter(v -> v.source().equals("pool")).findFirst().orElseThrow();
        SourceHealthRegistry.View edgar = views.stream()
                .filter(v -> v.source().equals("edgar")).findFirst().orElseThrow();
        assertTrue(pool.lastSuccessMs() > 0, "EMPTY is a clean answer");
        assertEquals(0, edgar.lastSuccessMs(), "SKIPPED must stamp neither success nor failure");
        assertEquals(0, edgar.consecutiveFailures());
        assertTrue(edgar.recentItemCounts().isEmpty(), "SKIPPED does not enter the yield history");
    }

    @Test
    void failuresShowAsMinusOneInTheYieldHistory() {
        registry.record("benzinga", "DELIVERED", 5, "");
        registry.record("benzinga", "FAILED", 0, "timeout");
        SourceHealthRegistry.View view = registry.snapshot().get(0);
        assertEquals(List.of(5, -1), view.recentItemCounts());
        assertEquals(1, view.consecutiveFailures());
    }

    @Test
    void yieldHistoryIsBounded() {
        for (int i = 0; i < 50; i++) registry.record("s", "DELIVERED", i, "");
        SourceHealthRegistry.View view = registry.snapshot().get(0);
        assertEquals(SourceHealthRegistry.HISTORY, view.recentItemCounts().size());
        assertEquals(49, view.recentItemCounts().get(SourceHealthRegistry.HISTORY - 1));
    }

    @Test
    void notesAreLengthCapped() {
        registry.record("s", "FAILED", 0, "x".repeat(10_000));
        assertEquals(SourceHealthRegistry.NOTE_MAX, registry.snapshot().get(0).lastNote().length());
    }

    @Test
    void suspiciousSourcesSortFirst() {
        registry.record("healthy", "DELIVERED", 3, "");
        registry.record("dying", "FAILED", 0, "HTTP 429");
        registry.record("dying", "FAILED", 0, "HTTP 429");
        assertEquals("dying", registry.snapshot().get(0).source());
    }

    @Test
    void eventRingIsBounded() {
        for (int i = 0; i < SourceHealthRegistry.EVENT_CAPACITY + 100; i++) {
            registry.record("s", "DELIVERED", i, "");
        }
        assertEquals(SourceHealthRegistry.EVENT_CAPACITY, registry.recentEvents(Integer.MAX_VALUE).size());
        assertEquals(SourceHealthRegistry.EVENT_CAPACITY + 100, registry.totalEvents());
    }
}
