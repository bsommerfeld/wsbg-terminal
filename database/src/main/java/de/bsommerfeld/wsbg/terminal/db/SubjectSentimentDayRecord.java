package de.bsommerfeld.wsbg.terminal.db;

import java.util.Map;

/**
 * One subject's room-sentiment balance for one calendar day — the daily
 * "Sentiment-Blatt": how the room stood on day X, folded deterministically
 * from the permanent headline archive (never from a model call). {@code date}
 * is the ISO day in the app's home zone, {@code subjectKey} the ticker (UPPER)
 * or the {@code name:…} unit key for ticker-less subjects — dated records make
 * name drift tolerable, each day stands alone.
 *
 * <p>{@code sentimentCounts} tallies the day's headline sentiments,
 * {@code majority} is the most frequent label, {@code arc} the day's
 * chronological trajectory with consecutive duplicates collapsed
 * ("BULLISH → MIXED"; empty below two distinct steps).
 */
public record SubjectSentimentDayRecord(
        String date,
        String subjectKey,
        String canonicalName,
        int headlineCount,
        Map<String, Integer> sentimentCounts,
        String majority,
        String arc) {

    public String identity() {
        return date + "|" + subjectKey;
    }
}
