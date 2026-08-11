package de.bsommerfeld.wsbg.terminal.ui.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hidden-tab eviction policy. The measured defect (2026-08-09): a
 * collect run touches 29 origins inside 9 minutes, so a pure LRU cap threw
 * away tabs the same run needed again seconds later and paid a fresh anchor
 * load plus warmup for each one.
 */
class CefWebFetcherEvictionTest {

    private static final long NOW = 1_000_000_000L;
    private static final long MINUTE = 60_000L;

    private static Map<String, Long> stamps(int count, long ageMs) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) m.put("host" + i, NOW - ageMs);
        return m;
    }

    @Test
    @DisplayName("under the cap nothing is evicted at all")
    void underCapEvictsNothing() {
        assertTrue(CefWebFetcher.lruVictims(stamps(10, 30 * MINUTE), 10, NOW).isEmpty());
    }

    @Test
    @DisplayName("a burst of recently used tabs bends the cap instead of thrashing")
    void recentTabsSurviveTheCap() {
        // 29 origins, all touched seconds ago - exactly the collect phase.
        assertTrue(CefWebFetcher.lruVictims(stamps(29, 5_000L), 29, NOW).isEmpty());
    }

    @Test
    @DisplayName("over the cap, only tabs past the grace go - oldest first")
    void onlyStaleTabsGo() {
        Map<String, Long> idle = new LinkedHashMap<>();
        idle.put("fresh", NOW - 5_000L);
        idle.put("oldest", NOW - 30 * MINUTE);
        idle.put("middle", NOW - 10 * MINUTE);
        // 18 parked, cap 16 → 2 may go, but "fresh" is inside the grace.
        assertEquals(List.of("oldest", "middle"), CefWebFetcher.lruVictims(idle, 18, NOW));
    }

    @Test
    @DisplayName("a busy tab (fetch in flight) is never a candidate - it is not in the map")
    void busyTabsAreNotCandidates() {
        Map<String, Long> idle = new LinkedHashMap<>();
        idle.put("idle-old", NOW - 30 * MINUTE);
        // 20 parked but only one is idle → at most that one goes.
        assertEquals(List.of("idle-old"), CefWebFetcher.lruVictims(idle, 20, NOW));
    }

    @Test
    @DisplayName("past the hard ceiling the grace stops protecting - renderers stay bounded")
    void hardCeilingIgnoresGrace() {
        List<String> victims = CefWebFetcher.lruVictims(stamps(41, 5_000L), 41, NOW);
        assertEquals(41 - 16, victims.size());
    }

    @Test
    @DisplayName("never evicts more than the excess over the cap")
    void evictsOnlyTheExcess() {
        assertEquals(4, CefWebFetcher.lruVictims(stamps(20, 30 * MINUTE), 20, NOW).size());
    }
}
