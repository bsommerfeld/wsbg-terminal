package de.bsommerfeld.wsbg.terminal.currency;

import java.util.List;
import java.util.Map;

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
 *   <li>{@link #dxy}/{@link #dxyPreviousClose} — the ICE Dollar-Index level and
 *       its day-change basis (Yahoo, own lazy cadence): dollar strength against
 *       the whole basket as context for the single pair.</li>
 *   <li>{@link #crosses}/{@link #crossesDate} — the latest ECB fixes for the
 *       classic EUR crosses (GBP, CHF, JPY, in display order) and their date.</li>
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
        String ecbDate,
        Double dxy,
        Double dxyPreviousClose,
        Map<String, Double> crosses,
        String crossesDate) {

    public FxDetails {
        spark = spark == null ? List.of() : spark;
        history = history == null ? List.of() : history;
        crosses = crosses == null ? Map.of() : crosses;
    }

    /** Pre-context shape (tests / callers without the DXY + crosses extras). */
    public FxDetails(Double previousClose, Double dayHigh, Double dayLow,
            Double week52High, Double week52Low, List<double[]> spark,
            List<double[]> history, Double ecbRate, String ecbDate) {
        this(previousClose, dayHigh, dayLow, week52High, week52Low, spark, history,
                ecbRate, ecbDate, null, null, Map.of(), null);
    }
}
