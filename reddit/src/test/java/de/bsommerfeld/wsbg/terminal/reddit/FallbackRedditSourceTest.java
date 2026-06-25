package de.bsommerfeld.wsbg.terminal.reddit;

import com.google.common.eventbus.Subscribe;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.RedditHealthEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chain-selection, scan-fallthrough, demotion and aggregate health for {@link FallbackRedditSource} — no network. */
class FallbackRedditSourceTest {

    /** A controllable stub source that records whether it was used. */
    private static final class StubSource implements RedditSource {
        private final String name;
        boolean reachable;
        boolean fail;        // when true, scans return a hard-failure ScrapeStats
        int scanCalls = 0;

        StubSource(String name, boolean reachable) {
            this.name = name;
            this.reachable = reachable;
        }

        private ScrapeStats scan() {
            scanCalls++;
            ScrapeStats s = new ScrapeStats();
            s.failed = fail;
            return s;
        }

        @Override public ScrapeStats scanSubreddit(String s) { return scan(); }
        @Override public ScrapeStats scanSubredditHot(String s) { return scan(); }
        @Override public ScrapeStats updateThreadsBatch(List<String> ids) { return scan(); }
        @Override public ThreadAnalysisContext fetchThreadContext(String p) { return new ThreadAnalysisContext(); }
        @Override public boolean probe(String subreddit) { return reachable; }
        @Override public String sourceName() { return name; }
    }

    /** Captures every RedditHealthEvent posted to the bus. */
    private static final class HealthCapture {
        final List<RedditHealthEvent> events = new ArrayList<>();
        @Subscribe public void on(RedditHealthEvent e) { events.add(e); }
    }

    @Test
    void picksFirstReachableInPreferenceOrder() {
        StubSource oauth = new StubSource("OAUTH", false);
        StubSource json = new StubSource("JSON", false);
        StubSource rss = new StubSource("RSS", true);
        var source = new FallbackRedditSource(List.of(oauth, json, rss), "sub", 600, null);

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
        var source = new FallbackRedditSource(List.of(oauth, json, rss), "sub", 600, null);

        source.scanSubreddit("sub");

        assertEquals("OAUTH", source.sourceName());
        assertEquals(1, oauth.scanCalls);
    }

    @Test
    void fallsBackToLastWhenNoneReachable() {
        StubSource oauth = new StubSource("OAUTH", false);
        StubSource rss = new StubSource("RSS", false);
        var source = new FallbackRedditSource(List.of(oauth, rss), "sub", 600, null);

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
        var source = new FallbackRedditSource(List.of(oauth, rss), "sub", 0, null);

        source.scanSubreddit("sub");
        assertEquals("RSS", source.sourceName());

        oauth.reachable = true; // OAuth comes back
        source.scanSubreddit("sub");
        assertEquals("OAUTH", source.sourceName());
        assertTrue(oauth.scanCalls >= 1);
    }

    @Test
    void fallsThroughToRssWhenTheActiveScanFails() {
        // JSON probes fine (anchor up) but its scan hard-fails (503/403) — the
        // source must fall through to RSS in the same cycle, not sit on JSON.
        StubSource oauth = new StubSource("OAUTH", false);
        StubSource json = new StubSource("JSON", true);
        json.fail = true;
        StubSource rss = new StubSource("RSS", true);
        var source = new FallbackRedditSource(List.of(oauth, json, rss), "sub", 600, null);

        ScrapeStats result = source.scanSubreddit("sub");

        assertFalse(result.failed, "RSS answered, so the cycle succeeded");
        assertEquals(1, json.scanCalls, "JSON was tried once");
        assertEquals(1, rss.scanCalls, "and fell through to RSS");
        assertEquals("RSS", source.sourceName());
    }

    @Test
    void demotedSourceIsSkippedOnReProbeUntilCooldown() {
        // interval 0 → would re-resolve every call and re-pick JSON (it probes
        // true) — but the demotion from its scan failure must keep it skipped.
        StubSource oauth = new StubSource("OAUTH", false);
        StubSource json = new StubSource("JSON", true);
        json.fail = true;
        StubSource rss = new StubSource("RSS", true);
        var source = new FallbackRedditSource(List.of(oauth, json, rss), "sub", 0, null);

        source.scanSubreddit("sub");      // JSON fails → demoted → RSS
        assertEquals("RSS", source.sourceName());

        json.fail = false;                // even if JSON would now work...
        source.scanSubreddit("sub");      // ...it's still in cooldown → stay on RSS
        assertEquals("RSS", source.sourceName());
        assertEquals(1, json.scanCalls, "demoted JSON is not retried during cooldown");
    }

    @Test
    void postsDegradedOnlyWhenTheWholeChainIsDown() {
        ApplicationEventBus bus = new ApplicationEventBus();
        HealthCapture cap = new HealthCapture();
        bus.register(cap);

        StubSource json = new StubSource("JSON", true);
        json.fail = true;
        StubSource rss = new StubSource("RSS", true);
        rss.fail = true; // whole chain down
        var source = new FallbackRedditSource(List.of(json, rss), "sub", 600, bus);

        source.scanSubreddit("sub");

        assertEquals(1, cap.events.size());
        assertEquals(RedditHealthEvent.State.DEGRADED, cap.events.get(0).state());
    }

    @Test
    void staysHealthyWhenAFallbackAnswers() {
        ApplicationEventBus bus = new ApplicationEventBus();
        HealthCapture cap = new HealthCapture();
        bus.register(cap);

        StubSource json = new StubSource("JSON", true);
        json.fail = true;        // active fails...
        StubSource rss = new StubSource("RSS", true); // ...but RSS answers
        var source = new FallbackRedditSource(List.of(json, rss), "sub", 600, bus);

        source.scanSubreddit("sub");

        assertTrue(cap.events.isEmpty(),
                "a working fallback means no DEGRADED transition is posted");
    }
}
