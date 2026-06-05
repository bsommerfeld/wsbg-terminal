package de.bsommerfeld.wsbg.terminal.core.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BrowserUserAgentTest {

    @Test
    void poolIsNonEmptyAndImmutable() {
        List<String> pool = BrowserUserAgent.pool();
        assertFalse(pool.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> pool.add("x"));
    }

    @Test
    void everyEntryLooksLikeARealDesktopBrowser() {
        for (String ua : BrowserUserAgent.pool()) {
            assertTrue(ua.startsWith("Mozilla/5.0"), () -> "not browser-shaped: " + ua);
            assertTrue(ua.contains("Windows NT") || ua.contains("Macintosh"),
                    () -> "no desktop OS token: " + ua);
            assertTrue(ua.contains("Chrome/") || ua.contains("Firefox/")
                            || ua.contains("Safari/") || ua.contains("Edg/"),
                    () -> "no known browser token: " + ua);
            // a bot tell would be an empty / library-default agent — none here
            assertFalse(ua.toLowerCase().contains("java"), () -> "leaks java agent: " + ua);
        }
    }

    @Test
    void randomAlwaysReturnsAPooledValue() {
        Set<String> pool = new HashSet<>(BrowserUserAgent.pool());
        for (int i = 0; i < 500; i++) {
            assertTrue(pool.contains(BrowserUserAgent.random()));
        }
    }

    @Test
    void randomActuallyVariesAcrossCalls() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(BrowserUserAgent.random());
        }
        // With a multi-entry pool and 500 draws, collapsing to one value is
        // astronomically unlikely — proves the selection is randomized.
        assertTrue(seen.size() > 1, "random() must spread across the pool");
    }
}
