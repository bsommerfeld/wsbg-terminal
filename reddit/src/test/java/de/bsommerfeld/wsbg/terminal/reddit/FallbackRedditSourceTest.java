package de.bsommerfeld.wsbg.terminal.reddit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chain-selection logic for {@link FallbackRedditSource} — no network. */
class FallbackRedditSourceTest {

    /** A controllable stub source that records whether it was used. */
    private static final class StubSource implements RedditSource {
        private final String name;
        boolean reachable;
        int scanCalls = 0;

        StubSource(String name, boolean reachable) {
            this.name = name;
            this.reachable = reachable;
        }

        @Override public ScrapeStats scanSubreddit(String s) { scanCalls++; return new ScrapeStats(); }
        @Override public ScrapeStats scanSubredditHot(String s) { return new ScrapeStats(); }
        @Override public ScrapeStats updateThreadsBatch(List<String> ids) { return new ScrapeStats(); }
        @Override public ThreadAnalysisContext fetchThreadContext(String p) { return new ThreadAnalysisContext(); }
        @Override public boolean probe(String subreddit) { return reachable; }
        @Override public String sourceName() { return name; }
    }

    @Test
    void picksFirstReachableInPreferenceOrder() {
        StubSource oauth = new StubSource("OAUTH", false);
        StubSource json = new StubSource("JSON", false);
        StubSource rss = new StubSource("RSS", true);
        var source = new FallbackRedditSource(List.of(oauth, json, rss), "sub", 600);

        source.scanSubreddit("sub");

        assertEquals("RSS", source.sourceName());
        assertEquals(0, oauth.scanCalls);
        assertEquals(0, json.scanCalls);
        assertEquals(1, rss.scanCalls);
    }

    @Test
    void prefersHigherPriorityWhenReachable() {
        StubSource oauth = new StubSource("OAUTH", true);
        StubSource json = new StubSource("JSON", true);
        StubSource rss = new StubSource("RSS", true);
        var source = new FallbackRedditSource(List.of(oauth, json, rss), "sub", 600);

        source.scanSubreddit("sub");

        assertEquals("OAUTH", source.sourceName());
        assertEquals(1, oauth.scanCalls);
    }

    @Test
    void fallsBackToLastWhenNoneReachable() {
        StubSource oauth = new StubSource("OAUTH", false);
        StubSource rss = new StubSource("RSS", false);
        var source = new FallbackRedditSource(List.of(oauth, rss), "sub", 600);

        // Should not throw; uses the last delegate as a best-effort.
        source.scanSubreddit("sub");
        assertEquals("RSS", source.sourceName());

        assertFalse(source.probe("sub")); // composite probe: none reachable
    }

    @Test
    void selfHealsUpgradeWhenHigherPriorityRecovers() {
        StubSource oauth = new StubSource("OAUTH", false);
        StubSource rss = new StubSource("RSS", true);
        // interval 0 → re-resolves on every call
        var source = new FallbackRedditSource(List.of(oauth, rss), "sub", 0);

        source.scanSubreddit("sub");
        assertEquals("RSS", source.sourceName());

        oauth.reachable = true; // OAuth comes back
        source.scanSubreddit("sub");
        assertEquals("OAUTH", source.sourceName());
        assertTrue(oauth.scanCalls >= 1);
    }
}
