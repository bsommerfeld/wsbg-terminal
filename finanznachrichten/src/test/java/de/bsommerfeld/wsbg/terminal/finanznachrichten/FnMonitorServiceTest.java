package de.bsommerfeld.wsbg.terminal.finanznachrichten;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FnMonitorServiceTest {

    private FnMonitorService monitor;

    /** In-memory fetcher returning canned items per feed; counts how often each feed was hit. */
    private static final class StubFetcher implements FeedFetcher {
        final Map<FnFeed, List<RawNewsItem>> items = new ConcurrentHashMap<>();
        final Map<FnFeed, AtomicInteger> hits = new ConcurrentHashMap<>();

        void put(FnFeed feed, RawNewsItem... newsItems) {
            items.put(feed, List.of(newsItems));
        }

        @Override
        public List<RawNewsItem> fetch(FnFeed feed) {
            hits.computeIfAbsent(feed, f -> new AtomicInteger()).incrementAndGet();
            return items.getOrDefault(feed, List.of());
        }
    }

    private static RawNewsItem item(String link, FnFeed feed) {
        return new RawNewsItem(link, "T " + link, "finanznachrichten", link, null, List.of());
    }

    /** Config with a high interval (so the scheduler never fires mid-test) and no inter-request delay. */
    private static FinanznachrichtenConfig fastConfig() {
        FinanznachrichtenConfig c = new FinanznachrichtenConfig();
        c.setPollIntervalSeconds(3600);
        c.setInterRequestDelayMillis(0);
        return c;
    }

    @AfterEach
    void tearDown() {
        if (monitor != null) {
            monitor.shutdown();
        }
    }

    @Test
    void noArgStartSelectsAllFeeds() {
        monitor = new FnMonitorService(new StubFetcher(), fastConfig());
        List<FnFeed> active = monitor.start();
        assertEquals(FnFeed.values().length, active.size());
    }

    @Test
    void varargsSelectExactlyThoseFeeds() {
        monitor = new FnMonitorService(new StubFetcher(), fastConfig());
        List<FnFeed> active = monitor.start(FnFeed.AKTIEN_NACHRICHTEN, FnFeed.DAX_40_NACHRICHTEN_1);
        assertEquals(List.of(FnFeed.AKTIEN_NACHRICHTEN, FnFeed.DAX_40_NACHRICHTEN_1), active);
    }

    @Test
    void duplicateFeedsAreCollapsed() {
        monitor = new FnMonitorService(new StubFetcher(), fastConfig());
        List<FnFeed> active = monitor.start(FnFeed.NEWS, FnFeed.NEWS, FnFeed.AKTIEN_ADHOC);
        assertEquals(List.of(FnFeed.NEWS, FnFeed.AKTIEN_ADHOC), active);
    }

    @Test
    void emitsNewItemsFromSelectedFeeds() {
        StubFetcher fetcher = new StubFetcher();
        fetcher.put(FnFeed.NEWS, item("a", FnFeed.NEWS), item("b", FnFeed.NEWS));
        fetcher.put(FnFeed.AKTIEN_ADHOC, item("c", FnFeed.AKTIEN_ADHOC));

        List<RawNewsItem> received = new CopyOnWriteArrayList<>();
        monitor = new FnMonitorService(fetcher, fastConfig());
        monitor.addListener(received::add);
        monitor.start(FnFeed.NEWS, FnFeed.AKTIEN_ADHOC);

        monitor.tick();

        assertEquals(3, received.size());
        assertEquals(3, monitor.seenCount());
        // unselected feed never fetched
        assertNull(fetcher.hits.get(FnFeed.DAX_40_NACHRICHTEN_1));
    }

    @Test
    void deduplicatesByLinkAcrossTicks() {
        StubFetcher fetcher = new StubFetcher();
        fetcher.put(FnFeed.NEWS, item("a", FnFeed.NEWS));

        List<RawNewsItem> received = new ArrayList<>();
        monitor = new FnMonitorService(fetcher, fastConfig());
        monitor.addListener(received::add);
        monitor.start(FnFeed.NEWS);

        monitor.tick();
        monitor.tick(); // same item again

        assertEquals(1, received.size(), "an already-seen link must not be re-emitted");

        // a genuinely new item on the next tick IS emitted
        fetcher.put(FnFeed.NEWS, item("a", FnFeed.NEWS), item("b", FnFeed.NEWS));
        monitor.tick();
        assertEquals(2, received.size());
        assertEquals("b", received.get(1).link());
    }

    @Test
    void startResetsDeduplicationState() {
        StubFetcher fetcher = new StubFetcher();
        fetcher.put(FnFeed.NEWS, item("a", FnFeed.NEWS));

        monitor = new FnMonitorService(fetcher, fastConfig());
        monitor.start(FnFeed.NEWS);
        monitor.tick();
        assertEquals(1, monitor.seenCount());

        monitor.start(FnFeed.NEWS); // restart clears seen state
        assertEquals(0, monitor.seenCount());
    }

    @Test
    void aFailingFeedDoesNotStallTheSweep() {
        FeedFetcher fetcher = feed -> {
            if (feed == FnFeed.NEWS) {
                throw new RuntimeException("boom");
            }
            return List.of(item("ok", feed));
        };
        List<RawNewsItem> received = new ArrayList<>();
        monitor = new FnMonitorService(fetcher, fastConfig());
        monitor.addListener(received::add);
        monitor.start(FnFeed.NEWS, FnFeed.AKTIEN_ADHOC);

        assertDoesNotThrow(monitor::tick);
        assertEquals(1, received.size());
        assertEquals("ok", received.getFirst().link());
    }

    @Test
    void pollIntervalIsFlooredAtFiveMinutes() {
        FinanznachrichtenConfig tooFast = new FinanznachrichtenConfig();
        tooFast.setPollIntervalSeconds(10);
        monitor = new FnMonitorService(new StubFetcher(), tooFast);
        assertEquals(FnMonitorService.MIN_POLL_INTERVAL_SECONDS, monitor.pollIntervalSeconds());
    }

    @Test
    void itemWithBlankLinkIsNotEmitted() {
        StubFetcher fetcher = new StubFetcher();
        fetcher.put(FnFeed.NEWS, new RawNewsItem("", "t", "finanznachrichten", "", null, List.of()));
        List<RawNewsItem> received = new ArrayList<>();
        monitor = new FnMonitorService(fetcher, fastConfig());
        monitor.addListener(received::add);
        monitor.start(FnFeed.NEWS);

        monitor.tick();
        assertTrue(received.isEmpty());
        assertEquals(0, monitor.seenCount());
    }
}
