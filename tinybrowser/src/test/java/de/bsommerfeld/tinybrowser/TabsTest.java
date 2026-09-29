package de.bsommerfeld.tinybrowser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which tabs close when, and which caller headers reach the page - both
 * carried over from master with the tests that pinned them down there.
 */
class TabsTest {

    private static final long NOW = 1_000_000_000L;
    private static final long MINUTE = 60_000L;

    private static Map<String, Long> stamps(int count, long ageMillis) {
        Map<String, Long> stamps = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            stamps.put("host" + i, NOW - ageMillis);
        }
        return stamps;
    }

    private static List<Map.Entry<String, String>> headers(String... namesAndValues) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            headers.add(Map.entry(namesAndValues[i], namesAndValues[i + 1]));
        }
        return headers;
    }

    // ---- eviction ------------------------------------------------------------

    @Test
    @DisplayName("under the cap nothing is evicted at all")
    void underCapEvictsNothing() {
        assertTrue(Tabs.lruVictims(stamps(10, 30 * MINUTE), 10, NOW).isEmpty());
    }

    @Test
    @DisplayName("a burst of recently used tabs bends the cap instead of thrashing")
    void recentTabsSurviveTheCap() {
        assertTrue(Tabs.lruVictims(stamps(29, 5_000L), 29, NOW).isEmpty());
    }

    @Test
    @DisplayName("over the cap, only tabs past the grace go - oldest first")
    void onlyStaleTabsGo() {
        Map<String, Long> idle = new LinkedHashMap<>();
        idle.put("fresh", NOW - 5_000L);
        idle.put("oldest", NOW - 30 * MINUTE);
        idle.put("middle", NOW - 10 * MINUTE);
        assertEquals(List.of("oldest", "middle"), Tabs.lruVictims(idle, 18, NOW));
    }

    @Test
    @DisplayName("a busy tab is never a candidate - it is not among the idle ones")
    void busyTabsAreNotCandidates() {
        Map<String, Long> idle = new LinkedHashMap<>();
        idle.put("idle-old", NOW - 30 * MINUTE);
        assertEquals(List.of("idle-old"), Tabs.lruVictims(idle, 20, NOW));
    }

    @Test
    @DisplayName("past the hard ceiling the grace stops protecting - renderers stay bounded")
    void hardCeilingIgnoresGrace() {
        assertEquals(41 - 16, Tabs.lruVictims(stamps(41, 5_000L), 41, NOW).size());
    }

    @Test
    @DisplayName("never evicts more than the excess over the cap")
    void evictsOnlyTheExcess() {
        assertEquals(4, Tabs.lruVictims(stamps(20, 30 * MINUTE), 20, NOW).size());
    }

    // ---- headers ---------------------------------------------------------------

    @Test
    void sameOriginKeepsTheCallersHeaders() {
        Map<String, String> sent = Tabs.sanitizeHeaders(headers(
                "accept", "application/rss+xml",
                "if-none-match", "\"abc\"",
                "authorization", "bearer token"), false);
        assertEquals(Map.of("accept", "application/rss+xml", "if-none-match", "\"abc\"",
                "authorization", "bearer token"), sent);
    }

    @Test
    void dropsWhatTheBrowserOwnsItself() {
        Map<String, String> sent = Tabs.sanitizeHeaders(headers(
                "Accept-Encoding", "gzip",
                "Cookie", "session=1",
                "Referer", "https://example.org/",
                "Sec-Fetch-Mode", "cors",
                "Proxy-Authorization", "x",
                "Accept", "application/json"), false);
        assertEquals(Map.of("Accept", "application/json"), sent,
                "Chromium supplies its own session - only the negotiation header survives");
    }

    @Test
    void aCallersUserAgentTravelsUnderTheMarker() {
        assertEquals(Map.of(ResourcePolicy.USER_AGENT_MARKER, "java:app:1.0 (by /u/someone)"),
                Tabs.sanitizeHeaders(headers("user-agent", "java:app:1.0 (by /u/someone)"), false));
        assertTrue(Tabs.sanitizeHeaders(headers("user-agent", "x"), true).isEmpty(),
                "cross-origin it would cost a preflight");
    }

    @Test
    void crossOriginIsReducedToTheCorsSafelist() {
        Map<String, String> sent = Tabs.sanitizeHeaders(headers(
                "Accept", "application/json",
                "Accept-Language", "de-DE",
                "If-None-Match", "\"abc\""), true);
        assertEquals(Map.of("Accept", "application/json", "Accept-Language", "de-DE"), sent,
                "a validator here would trigger a preflight the API never answers");
    }
}
