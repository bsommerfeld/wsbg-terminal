package de.bsommerfeld.wsbg.terminal.core.debug;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The source health ledger — fed from the ONE funnel every per-source answer
 * already flows through ({@code SourceOutcome}'s constructor in web-api), so
 * a source that dies quietly becomes visible without a single new request.
 *
 * <p>Per source it keeps the last run's status/yield/note, the last success
 * timestamp, the consecutive-failure streak and a short yield history (for a
 * sparkline); globally it keeps a bounded event ring.
 *
 * <p>Written ONLY behind {@code if (Debug.ENABLED)}; in a shipped build this
 * class never loads. Locks: the per-source monitor and the ring monitor are
 * leaves; nothing here calls out.
 */
public final class SourceHealthRegistry {

    private static final SourceHealthRegistry INSTANCE = new SourceHealthRegistry();

    public static SourceHealthRegistry get() {
        return INSTANCE;
    }

    /** Notes are diagnostics, not payloads — capped so the ledger stays small. */
    static final int NOTE_MAX = 240;
    static final int EVENT_CAPACITY = 1_000;
    /** Yield history per source (sparkline depth). */
    static final int HISTORY = 20;

    /** One recorded outcome. {@code status} is the {@code SourceOutcome.Status} name. */
    public record Event(long atMs, String source, String status, int itemCount, String note) {
    }

    /** Immutable per-source view for the debug consumer. */
    public record View(String source, String lastStatus, long lastRunMs, long lastSuccessMs,
            int lastItemCount, String lastNote, int consecutiveFailures,
            long runs, long failures, List<Integer> recentItemCounts) {
    }

    private static final class Health {
        String lastStatus = "";
        long lastRunMs;
        long lastSuccessMs;
        int lastItemCount;
        String lastNote = "";
        int consecutiveFailures;
        long runs;
        long failures;
        final int[] history = new int[HISTORY];
        int historySize;
        int historyHead;

        synchronized void record(long atMs, String status, int itemCount, String note) {
            runs++;
            lastRunMs = atMs;
            lastStatus = status;
            lastItemCount = itemCount;
            lastNote = note;
            boolean failed = "FAILED".equals(status);
            if (failed) {
                failures++;
                consecutiveFailures++;
            } else {
                consecutiveFailures = 0;
                // DELIVERED and EMPTY are both clean answers; SKIPPED is
                // "not asked" and stamps neither success nor failure.
                if ("DELIVERED".equals(status) || "EMPTY".equals(status)) {
                    lastSuccessMs = atMs;
                }
            }
            if (!"SKIPPED".equals(status)) {
                history[historyHead] = failed ? -1 : itemCount;
                historyHead = (historyHead + 1) % HISTORY;
                if (historySize < HISTORY) historySize++;
            }
        }

        synchronized View view(String name) {
            List<Integer> counts = new ArrayList<>(historySize);
            int start = (historyHead - historySize + HISTORY) % HISTORY;
            for (int i = 0; i < historySize; i++) {
                counts.add(history[(start + i) % HISTORY]);
            }
            return new View(name, lastStatus, lastRunMs, lastSuccessMs, lastItemCount,
                    lastNote, consecutiveFailures, runs, failures, counts);
        }
    }

    private final ConcurrentHashMap<String, Health> health = new ConcurrentHashMap<>();
    private final DebugRing<Event> events = new DebugRing<>(EVENT_CAPACITY);

    /**
     * Records one source outcome. {@code status} is a status NAME (DELIVERED,
     * EMPTY, FAILED, SKIPPED — kept as string so core needs no web-api type).
     */
    public void record(String source, String status, int itemCount, String note) {
        if (source == null) return;
        long now = System.currentTimeMillis();
        String cappedNote = note == null ? ""
                : note.length() <= NOTE_MAX ? note : note.substring(0, NOTE_MAX);
        health.computeIfAbsent(source, s -> new Health())
                .record(now, status == null ? "" : status, itemCount, cappedNote);
        events.add(new Event(now, source, status == null ? "" : status, itemCount, cappedNote));
    }

    /** All sources, most-suspicious first (longest since success, failures on top). */
    public List<View> snapshot() {
        List<View> out = new ArrayList<>(health.size());
        health.forEach((name, h) -> out.add(h.view(name)));
        out.sort(Comparator
                .comparingInt((View v) -> -v.consecutiveFailures())
                .thenComparingLong(View::lastSuccessMs));
        return out;
    }

    /** The newest {@code limit} outcome events, oldest-first. */
    public List<Event> recentEvents(int limit) {
        return events.recent(limit);
    }

    public long totalEvents() {
        return events.totalAdded();
    }

    /** Test seam. */
    public void reset() {
        health.clear();
        events.clear();
    }

    private SourceHealthRegistry() {
    }
}
