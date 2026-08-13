package de.bsommerfeld.wsbg.terminal.core.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The collector clock's shadow: when did each collector pass actually run,
 * how long did it take, what did it yield, and when is the next appointment
 * booked. Fed from {@code RandomIntervalScheduler} (web-impl), read by the
 * debug bridge. Written ONLY behind {@code if (Debug.ENABLED)}.
 */
public final class CollectorClock {

    private static final CollectorClock INSTANCE = new CollectorClock();

    public static CollectorClock get() {
        return INSTANCE;
    }

    static final int PASS_CAPACITY = 500;
    static final int ERROR_MAX = 240;

    /** One completed collector pass. {@code error == null} means it succeeded. */
    public record Pass(long atMs, String source, long durationMs, int items, int fresh,
            String error) {
    }

    /** Immutable per-collector view. */
    public record View(String source, long lastStartMs, long lastDurationMs, int lastItems,
            int lastFresh, String lastError, long nextDueMs, long passes, long misses) {
    }

    private static final class State {
        long lastStartMs;
        long lastDurationMs;
        int lastItems;
        int lastFresh;
        String lastError;
        long nextDueMs;
        long passes;
        long misses;

        synchronized void pass(long atMs, long durationMs, int items, int fresh, String error) {
            lastStartMs = atMs - durationMs;
            lastDurationMs = durationMs;
            lastItems = items;
            lastFresh = fresh;
            lastError = error;
            passes++;
            if (error != null) misses++;
        }

        synchronized void booked(long dueMs) {
            nextDueMs = dueMs;
        }

        synchronized View view(String name) {
            return new View(name, lastStartMs, lastDurationMs, lastItems, lastFresh,
                    lastError, nextDueMs, passes, misses);
        }
    }

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final DebugRing<Pass> passes = new DebugRing<>(PASS_CAPACITY);

    public void recordPass(String source, long durationMs, int items, int fresh, String error) {
        if (source == null) return;
        long now = System.currentTimeMillis();
        String cappedError = error == null ? null
                : error.length() <= ERROR_MAX ? error : error.substring(0, ERROR_MAX);
        states.computeIfAbsent(source, s -> new State())
                .pass(now, durationMs, items, fresh, cappedError);
        passes.add(new Pass(now, source, durationMs, items, fresh, cappedError));
    }

    public void recordBooked(String source, long dueMs) {
        if (source == null) return;
        states.computeIfAbsent(source, s -> new State()).booked(dueMs);
    }

    /** All collectors, most-recently-run last. */
    public List<View> snapshot() {
        List<View> out = new ArrayList<>(states.size());
        states.forEach((name, s) -> out.add(s.view(name)));
        out.sort(Comparator.comparingLong(View::lastStartMs));
        return out;
    }

    public List<Pass> recentPasses(int limit) {
        return passes.recent(limit);
    }

    /** Test seam. */
    public void reset() {
        states.clear();
        passes.clear();
    }

    private CollectorClock() {
    }
}
