package de.bsommerfeld.wsbg.terminal.aggregator;

import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fan runs the sources SIDE BY SIDE without moving a single item: the
 * merge still walks the answers in the source set's own order, so the dedupe's
 * "first-seen wins" verdict is the one the serial fan gave. Measured cause
 * (measured 2026-08-09): 36 sources asked one after another made the press
 * leg 353 s while the machine waited on one socket at a time.
 */
class NewsAggregatorFanTest {

    private static final Instant T = Instant.parse("2026-08-09T10:00:00Z");

    private static RawNewsItem item(String uuid) {
        return new RawNewsItem(uuid, "title-" + uuid, "pub-" + uuid,
                "https://x/" + uuid, T, List.of());
    }

    /** A source that takes {@code delayMs} to answer - a slow host, in miniature. */
    private static NewsSource slow(String name, long delayMs, AtomicInteger concurrent,
            AtomicInteger peak, String... uuids) {
        return new NewsSource() {
            @Override public String sourceName() { return name; }
            @Override public List<RawNewsItem> newsFor(String symbol, int limit) {
                int now = concurrent.incrementAndGet();
                peak.updateAndGet(p -> Math.max(p, now));
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    concurrent.decrementAndGet();
                }
                List<RawNewsItem> out = new ArrayList<>();
                for (String u : uuids) out.add(item(u));
                return out;
            }
        };
    }

    private static NewsAggregator aggregator(NewsSource... sources) {
        Set<NewsSource> set = new LinkedHashSet<>(List.of(sources));
        return new NewsAggregator(set);
    }

    @Test
    @DisplayName("sources are asked at the same time, not one after another")
    void sourcesRunConcurrently() {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        NewsSource[] all = new NewsSource[8];
        for (int i = 0; i < all.length; i++) {
            all[i] = slow("s" + i, 200, concurrent, peak, "u" + i);
        }
        long t0 = System.nanoTime();
        List<RawNewsItem> out = aggregator(all).newsFor("NVDA", 50);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(8, out.size(), "every source's item must survive");
        assertTrue(peak.get() > 1, "sources must overlap, peak concurrency was " + peak.get());
        assertTrue(elapsedMs < 8 * 200L, "serial would need 1600 ms, took " + elapsedMs + " ms");
    }

    @Test
    @DisplayName("the merge order is the source order, however the answers arrive")
    void mergeOrderFollowsSourceOrderNotCompletionOrder() {
        AtomicInteger c = new AtomicInteger();
        AtomicInteger p = new AtomicInteger();
        // The FIRST source is the slowest: under completion order it would lose
        // the dedupe, under source order it wins it - as it did when serial.
        NewsSource first = slow("first", 250, c, p, "shared", "only-first");
        NewsSource second = slow("second", 10, c, p, "shared", "only-second");

        List<RawNewsItem> out = aggregator(first, second).newsFor("NVDA", 50);

        assertEquals(3, out.size(), "the shared uuid must appear once");
        RawNewsItem shared = out.stream().filter(i -> i.uuid().equals("shared")).findFirst()
                .orElseThrow();
        assertEquals("pub-shared", shared.publisher());
        // The pool keeps the source order for equal-rank items.
        assertEquals(List.of("shared", "only-first", "only-second"),
                out.stream().map(RawNewsItem::uuid).toList());
    }

    @Test
    @DisplayName("one exploding source never takes the fan down")
    void oneFailingSourceIsIsolated() {
        AtomicInteger c = new AtomicInteger();
        AtomicInteger p = new AtomicInteger();
        NewsSource boom = new NewsSource() {
            @Override public String sourceName() { return "boom"; }
            @Override public List<RawNewsItem> newsFor(String symbol, int limit) {
                throw new RuntimeException("boom");
            }
        };
        NewsSource ok = slow("ok", 5, c, p, "survivor");

        List<RawNewsItem> out = aggregator(boom, ok).newsFor("NVDA", 50);

        assertEquals(List.of("survivor"), out.stream().map(RawNewsItem::uuid).toList());
    }

    @Test
    @DisplayName("the archive window fan overlaps too and keeps its round-robin")
    void historyWindowFansConcurrently() {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        NewsSource[] all = new NewsSource[6];
        for (int i = 0; i < all.length; i++) {
            final int idx = i;
            all[i] = new NewsSource() {
                @Override public String sourceName() { return "h" + idx; }
                @Override public List<RawNewsItem> newsFor(String symbol, int limit) {
                    return List.of();
                }
                @Override public List<RawNewsItem> newsForNameWindow(String name, String isin,
                        String from, String to, int limit) {
                    int now = concurrent.incrementAndGet();
                    peak.updateAndGet(x -> Math.max(x, now));
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        concurrent.decrementAndGet();
                    }
                    return List.of(item("w" + idx));
                }
            };
        }
        long t0 = System.nanoTime();
        List<RawNewsItem> out = aggregator(all)
                .historyFor("SAP SE", "DE0007164600", "2020-01-01", "2021-01-01", 50);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(6, out.size());
        assertTrue(peak.get() > 1, "archive sources must overlap, peak was " + peak.get());
        assertTrue(elapsedMs < 6 * 200L, "serial would need 1200 ms, took " + elapsedMs + " ms");
        // Round-robin over the per-source lists, in source order.
        assertEquals(List.of("w0", "w1", "w2", "w3", "w4", "w5"),
                out.stream().map(RawNewsItem::uuid).toList());
    }
}
