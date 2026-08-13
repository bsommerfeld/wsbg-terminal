package de.bsommerfeld.wsbg.terminal.core.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The article basin's inflow ledger: every pour into the pool — collectors,
 * the live instrument fan, the search engines — leaves one bounded event plus
 * a per-source running total. Holds source NAMES and counts only, never an
 * article reference, so nothing here can extend an article's lifetime past
 * its eviction (top rule #3).
 *
 * <p>Fed from {@code InMemoryArticlePool.add} (web-impl), ONLY behind
 * {@code if (Debug.ENABLED)}.
 */
public final class BasinInflow {

    private static final BasinInflow INSTANCE = new BasinInflow();

    public static BasinInflow get() {
        return INSTANCE;
    }

    static final int POUR_CAPACITY = 2_000;

    /** One pour: {@code offered} items handed in, {@code fresh} of them new. */
    public record Pour(long atMs, String source, int offered, int fresh, int basinSize) {
    }

    /** Per-source running totals since process start. */
    public record Totals(String source, long pours, long offered, long fresh, long lastPourMs) {
    }

    private static final class Counter {
        final AtomicLong pours = new AtomicLong();
        final AtomicLong offered = new AtomicLong();
        final AtomicLong fresh = new AtomicLong();
        volatile long lastPourMs;
    }

    private final ConcurrentHashMap<String, Counter> totals = new ConcurrentHashMap<>();
    private final DebugRing<Pour> pours = new DebugRing<>(POUR_CAPACITY);

    public void record(String source, int offered, int fresh, int basinSize) {
        if (source == null) return;
        long now = System.currentTimeMillis();
        Counter c = totals.computeIfAbsent(source, s -> new Counter());
        c.pours.incrementAndGet();
        c.offered.addAndGet(offered);
        c.fresh.addAndGet(fresh);
        c.lastPourMs = now;
        pours.add(new Pour(now, source, offered, fresh, basinSize));
    }

    /** All sources, biggest contributors first. */
    public List<Totals> snapshotTotals() {
        List<Totals> out = new ArrayList<>(totals.size());
        totals.forEach((name, c) -> out.add(
                new Totals(name, c.pours.get(), c.offered.get(), c.fresh.get(), c.lastPourMs)));
        out.sort(Comparator.comparingLong(Totals::fresh).reversed());
        return out;
    }

    public List<Pour> recentPours(int limit) {
        return pours.recent(limit);
    }

    /** Test seam. */
    public void reset() {
        totals.clear();
        pours.clear();
    }

    private BasinInflow() {
    }
}
