package de.bsommerfeld.wsbg.terminal.core.debug;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the smaller registries: CollectorClock, BasinInflow, LlmDebug, DebugGauges. */
class DebugRegistriesTest {

    @BeforeEach
    void reset() {
        CollectorClock.get().reset();
        BasinInflow.get().reset();
        LlmDebug.get().reset();
        DebugGauges.get().reset();
        RedditPollDebug.get().reset();
    }

    @Test
    void collectorClockTracksPassesAndAppointments() {
        CollectorClock clock = CollectorClock.get();
        clock.recordBooked("nasdaq-rss", System.currentTimeMillis() + 60_000);
        clock.recordPass("nasdaq-rss", 250, 12, 3, null);
        clock.recordPass("nasdaq-rss", 100, 0, 0, "HTTP 503");
        CollectorClock.View view = clock.snapshot().get(0);
        assertEquals("nasdaq-rss", view.source());
        assertEquals(2, view.passes());
        assertEquals(1, view.misses());
        assertEquals("HTTP 503", view.lastError());
        assertTrue(view.nextDueMs() > System.currentTimeMillis() - 1_000);
        assertEquals(2, clock.recentPasses(10).size());
        assertNull(clock.recentPasses(10).get(0).error());
    }

    @Test
    void basinInflowAccumulatesPerSourceTotals() {
        BasinInflow inflow = BasinInflow.get();
        inflow.record("yahoo", 10, 4, 100);
        inflow.record("yahoo", 5, 5, 105);
        inflow.record("edgar", 1, 1, 106);
        List<BasinInflow.Totals> totals = inflow.snapshotTotals();
        assertEquals("yahoo", totals.get(0).source());
        assertEquals(9, totals.get(0).fresh());
        assertEquals(15, totals.get(0).offered());
        assertEquals(2, totals.get(0).pours());
        assertEquals(3, inflow.recentPours(10).size());
    }

    @Test
    void llmDebugKeepsMeasuredMaximaAndLastValues() {
        LlmDebug llm = LlmDebug.get();
        llm.record("editorial-prep-1", 5, 900, 4_200, 120);
        llm.record("editorial-worker-1", 0, 1_500, 6_100, 80);
        llm.record("editorial-worker-2", 2, 700, -1, -1); // no usage block reported
        LlmDebug.Stats stats = llm.stats();
        assertEquals(3, stats.calls());
        assertEquals(6_100, stats.maxTokensIn());
        assertEquals(120, stats.maxTokensOut());
        assertEquals(-1, stats.lastTokensIn());
        assertEquals(10_300, stats.totalTokensIn(), "unreported (-1) calls must not distort totals");
        assertEquals(3, llm.recentCalls(10).size());
    }

    @Test
    void gaugesSampleEverySupplierAndSurviveAThrowingOne() {
        DebugGauges gauges = DebugGauges.get();
        gauges.register("a.depth", () -> 7);
        gauges.register("b.broken", () -> {
            throw new IllegalStateException("boom");
        });
        Map<String, Long> sample = gauges.sample();
        assertEquals(7L, sample.get("a.depth"));
        assertEquals(-1L, sample.get("b.broken"));
    }

    @Test
    void redditPollDebugAccumulatesThrottleTruth() {
        RedditPollDebug debug = RedditPollDebug.get();
        debug.recordLane("scan", "wallstreetbetsGER", 1_200, 2, 40, 9, 50, false);
        debug.recordLane("comments", "wallstreetbets", 400, 0, 0, 17, 0, false);
        debug.addBucketWait(150);
        debug.addBucketWait(50);
        debug.countAcquire();
        debug.recordBackoff(11_000);
        debug.recordBudgetRemaining(1.0);
        debug.recordSourceEvent("demote", "reddit-oauth for 10 min");

        assertEquals(2, debug.recentLanes(10).size());
        assertEquals("scan", debug.recentLanes(10).get(0).lane());
        RedditPollDebug.Throttle throttle = debug.throttle();
        assertEquals(200, throttle.bucketWaitMsTotal());
        assertEquals(1, throttle.bucketAcquires());
        assertEquals(11_000, throttle.backoffSleepMsTotal());
        assertEquals(1, throttle.backoffCount());
        assertEquals(1.0, throttle.lastRatelimitRemaining());
        assertTrue(throttle.lastRatelimitSeenAtMs() > 0);
        assertEquals("demote", debug.recentSourceEvents(10).get(0).kind());
    }
}
