package de.bsommerfeld.wsbg.terminal.ui.debug;

import de.bsommerfeld.wsbg.terminal.core.debug.DebugRing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-memory log window: the newest {@value #CAPACITY} log lines of THIS
 * (terminal) JVM as structured entries — no ANSI codes, message length
 * capped, throwables summarised. Filled by {@link DebugLogAppender}, which is
 * installed ONLY in dev mode, so a shipped build holds no log content in RAM.
 *
 * <p>Sizing: {@value #CAPACITY} lines × ≤ ~{@value #MESSAGE_MAX} chars ≈ 1–2
 * MB worst case against the 4 GB heap.
 */
public final class DebugLog {

    static final int CAPACITY = 4_000;
    static final int MESSAGE_MAX = 400;

    /** One captured log line. {@code error} is a one-line throwable summary or null. */
    public record Line(long atMs, String level, String logger, String thread,
            String message, String error) {
    }

    /** Aggregation over the buffered window. */
    public record Aggregate(long windowStartMs, int lines, int warnCount, int errorCount,
            Map<String, Integer> warnAndErrorByLogger) {
    }

    private static final DebugRing<Line> RING = new DebugRing<>(CAPACITY);

    static void add(Line line) {
        RING.add(line);
    }

    /** Oldest-first copy of the newest {@code limit} lines. */
    public static List<Line> recent(int limit) {
        return RING.recent(limit);
    }

    /** Total lines ever appended (overwritten ones included). */
    public static long totalAppended() {
        return RING.totalAdded();
    }

    /**
     * One pass over the buffer: WARN/ERROR totals plus a per-logger histogram
     * of everything at WARN and above, worst offenders first.
     */
    public static Aggregate aggregate() {
        List<Line> lines = RING.snapshot();
        long windowStart = lines.isEmpty() ? 0L : lines.get(0).atMs();
        int warn = 0;
        int error = 0;
        Map<String, Integer> byLogger = new java.util.HashMap<>();
        for (Line line : lines) {
            boolean isError = "ERROR".equals(line.level());
            boolean isWarn = "WARN".equals(line.level());
            if (isError) error++;
            if (isWarn) warn++;
            if (isError || isWarn) {
                byLogger.merge(line.logger(), 1, Integer::sum);
            }
        }
        Map<String, Integer> sorted = new LinkedHashMap<>();
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(byLogger.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey(Comparator.naturalOrder())));
        for (Map.Entry<String, Integer> e : entries) sorted.put(e.getKey(), e.getValue());
        return new Aggregate(windowStart, lines.size(), warn, error, sorted);
    }

    /** Test seam. */
    static void reset() {
        RING.clear();
    }

    private DebugLog() {
    }
}
