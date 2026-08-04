package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The article intake queue's load-bearing promises: the fetch order is
 * round-robin interleaved by host (so the eight producer slots sit on eight
 * DIFFERENT origins instead of hammering one), the host key is derived exactly
 * like the digester's, EVERY task hands over exactly one element - a failed
 * read included, or a consumer would wait forever - and the host politeness
 * (2 in flight, 400 ms between starts) actually bites.
 */
class ArticleIntakeQueueTest {

    private static RawNewsItem item(String link) {
        return new RawNewsItem("uuid-" + link, "T " + link, "P", link, Instant.now(), List.of());
    }

    private static List<String> links(List<RawNewsItem> items) {
        List<String> out = new ArrayList<>();
        for (RawNewsItem i : items) out.add(i.link());
        return out;
    }

    @Test
    void interleavesRoundRobinByHost() {
        List<RawNewsItem> in = List.of(
                item("https://a.example/1"), item("https://a.example/2"),
                item("https://a.example/3"), item("https://b.example/1"),
                item("https://b.example/2"), item("https://c.example/1"));
        assertEquals(List.of("https://a.example/1", "https://b.example/1",
                        "https://c.example/1", "https://a.example/2",
                        "https://b.example/2", "https://a.example/3"),
                links(ArticleIntakeQueue.interleaveByHost(in)));
    }

    @Test
    void interleaveKeepsEveryItemAndDropsOnlyLinklessOnes() {
        List<RawNewsItem> in = new ArrayList<>(List.of(
                item("https://a.example/1"), item("https://b.example/1"),
                item("https://a.example/2")));
        in.add(new RawNewsItem("u", "no link", "P", "  ", Instant.now(), List.of()));
        List<RawNewsItem> out = ArticleIntakeQueue.interleaveByHost(in);
        assertEquals(3, out.size());
        assertTrue(ArticleIntakeQueue.interleaveByHost(List.of()).isEmpty());
    }

    @Test
    void hostKeyIsLowercasedAndFailSafe() {
        assertEquals("www.example.org",
                ArticleIntakeQueue.hostKey("https://WWW.Example.ORG/a/b?x=1"));
        assertEquals("", ArticleIntakeQueue.hostKey("not a url"));
        assertEquals("", ArticleIntakeQueue.hostKey(null));
        assertEquals("", ArticleIntakeQueue.hostKey(""));
        // Same-host items must collapse onto ONE key regardless of path/scheme.
        assertEquals(ArticleIntakeQueue.hostKey("https://a.example/1"),
                ArticleIntakeQueue.hostKey("http://a.example/2/deep?q=3"));
    }

    /**
     * The contract a hole cannot form under: exactly {@code total} elements
     * reach the consumers even when a read fails, throws or comes back empty -
     * and every article is handed out exactly once.
     */
    @Test
    void handsOverExactlyOneElementPerTaskIncludingFailures() throws Exception {
        List<RawNewsItem> in = new ArrayList<>();
        for (int i = 0; i < 12; i++) in.add(item("https://h" + (i % 4) + ".example/" + i));
        Map<String, String> texts = new ConcurrentHashMap<>();
        try (ArticleIntakeQueue q = new ArticleIntakeQueue(link -> {
            if (link.contains("h1")) throw new IllegalStateException("boom");
            if (link.contains("h2")) return "";
            return "BODY " + link;
        }, () -> { })) {
            int total = q.start(in);
            assertEquals(12, total);
            List<Thread> lanes = new ArrayList<>();
            AtomicInteger taken = new AtomicInteger();
            for (int lane = 0; lane < 2; lane++) {
                Thread t = new Thread(() -> {
                    ArticleIntakeQueue.FetchedArticle a;
                    while ((a = q.next()) != null) {
                        taken.incrementAndGet();
                        texts.put(a.item().link(), a.text());
                    }
                });
                t.start();
                lanes.add(t);
            }
            for (Thread t : lanes) t.join(30_000);
            assertEquals(12, taken.get());
            assertEquals(12, texts.size());
            assertNotNull(texts.get("https://h1.example/1"));
            assertEquals("", texts.get("https://h1.example/1")); // throw → empty, still seated
            assertEquals("", texts.get("https://h2.example/2"));
            assertEquals("BODY https://h0.example/0", texts.get("https://h0.example/0"));
            assertNull(q.next()); // nothing left to claim
        }
    }

    /**
     * The RESULT order is the caller's, never the fetch order: the intake is
     * allowed to read host-interleaved and out of order, but the ledger the
     * report is built from is nailed to the source order.
     */
    @Test
    void resultOrderFollowsTheCallerNotTheFetch() throws Exception {
        List<RawNewsItem> order = List.of(
                item("https://a.example/1"), item("https://a.example/2"),
                item("https://b.example/1"));
        Map<String, String> byLink = new ConcurrentHashMap<>();
        try (ArticleIntakeQueue q = new ArticleIntakeQueue(link -> "BODY", () -> { })) {
            int total = q.start(order);
            for (int i = 0; i < total; i++) {
                ArticleIntakeQueue.FetchedArticle a = q.next();
                assertNotNull(a);
                byLink.put(a.item().link(), a.text());
            }
            // The caller rebuilds in ITS order, exactly as DeepDiveService does.
            Map<String, String> ordered = new LinkedHashMap<>();
            for (RawNewsItem i : order) ordered.put(i.link(), byLink.get(i.link()));
            assertEquals(List.of("https://a.example/1", "https://a.example/2",
                    "https://b.example/1"), new ArrayList<>(ordered.keySet()));
        }
    }

    /** At most two reads per host in flight, and 400 ms between two starts. */
    @Test
    void keepsHostPoliteness() throws Exception {
        List<RawNewsItem> in = new ArrayList<>();
        for (int i = 0; i < 4; i++) in.add(item("https://one.example/" + i));
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Long> starts = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch all = new CountDownLatch(4);
        try (ArticleIntakeQueue q = new ArticleIntakeQueue(link -> {
            starts.add(System.nanoTime());
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            all.countDown();
            return "BODY";
        }, () -> { })) {
            q.start(in);
            assertTrue(all.await(30, TimeUnit.SECONDS), "all four reads must run");
            for (int i = 0; i < 4; i++) assertNotNull(q.next());
            assertTrue(peak.get() <= ArticleIntakeQueue.PER_HOST_CONCURRENCY,
                    "at most two reads per host in flight, saw " + peak.get());
            List<Long> sorted = new ArrayList<>(starts);
            java.util.Collections.sort(sorted);
            for (int i = 1; i < sorted.size(); i++) {
                long gapMs = TimeUnit.NANOSECONDS.toMillis(sorted.get(i) - sorted.get(i - 1));
                assertTrue(gapMs >= ArticleIntakeQueue.PER_HOST_MIN_GAP_MS - 40,
                        "starts on one host must keep their distance, saw " + gapMs + " ms");
            }
        }
    }
}
