package de.bsommerfeld.wsbg.terminal.currency;

import java.util.List;

/**
 * The rich context around one EUR/USD rate — everything the detail widget shows
 * beyond the bare figure. Every field is best-effort: {@code null}/empty means
 * the sources didn't carry it this tick, the UI degrades gracefully.
 *
 * <ul>
 *   <li>{@link #previousClose} — the day-change basis (Yahoo's previous close;
 *       tick-to-tick direction lives on the quote itself).</li>
 *   <li>{@link #dayHigh}/{@link #dayLow} — today's range.</li>
 *   <li>{@link #week52High}/{@link #week52Low} — the 52-week band (Yahoo meta,
 *       else derived from the ECB series).</li>
 *   <li>{@link #spark} — intraday {@code [epochMs, rate]} pairs (Yahoo).</li>
 *   <li>{@link #history} — ~1y of daily {@code [epochMs, rate]} points
 *       (official ECB reference rates via Frankfurter).</li>
 *   <li>{@link #ecbRate}/{@link #ecbDate} — the latest ECB fix (one per
 *       business day, ~16:00 CET) and its ISO date.</li>
 * </ul>
 */
public record FxDetails(
        Double previousClose,
        Double dayHigh,
        Double dayLow,
        Double week52High,
        Double week52Low,
        List<double[]> spark,
        List<double[]> history,
        Double ecbRate,
        String ecbDate) {

    public FxDetails {
        spark = spark == null ? List.of() : spark;
        history = history == null ? List.of() : history;
    }
}
