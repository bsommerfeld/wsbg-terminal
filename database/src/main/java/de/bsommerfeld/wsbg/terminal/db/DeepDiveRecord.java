package de.bsommerfeld.wsbg.terminal.db;

/**
 * One generated KI-DD research report — a FIXED snapshot of everything the
 * terminal knew about one subject at generation time, unlike the watchlist's
 * continuously revised dossier. Archived permanently (JSONL, append-only).
 *
 * @param id             stable id ("dd-" + random), the archive identity
 * @param subject        the subject name as requested
 * @param canonicalName  the resolved canonical name (null when unresolved)
 * @param ticker         resolved ticker, null for themes/unresolved
 * @param isin           desk-stamped ISIN, null when unstamped
 * @param createdAtEpoch generation wall-clock (epoch seconds)
 * @param report         the full report, markdown with {@code ## } sections
 * @param priceAtTime    verified price at generation time, null when none
 * @param priceCurrency  its currency ("EUR", "PTS", …), null when no price
 * @param evidenceCount  room mentions that fed the report
 * @param newsCount      news items that fed the report
 * @param durationMs     wall-clock generation time (all passes)
 * @param charts         the report's figures (server-rendered SVG, frozen at
 *                       generation time so UI and PDF show the SAME picture);
 *                       may be null on records from before the chart layer —
 *                       read through {@link #chartsOrEmpty()}
 * @param signals        the house-computed quant signals that fed the material,
 *                       frozen as numbers so report runs form a time series;
 *                       null on records from before the signal layer — read
 *                       through {@link #signalsOrEmpty()}
 */
public record DeepDiveRecord(
        String id,
        String subject,
        String canonicalName,
        String ticker,
        String isin,
        long createdAtEpoch,
        String report,
        Double priceAtTime,
        String priceCurrency,
        int evidenceCount,
        int newsCount,
        long durationMs,
        java.util.List<ChartFigure> charts,
        java.util.List<SignalValue> signals) {

    /** Pre-chart archive lines deserialize with {@code charts == null}. */
    public java.util.List<ChartFigure> chartsOrEmpty() {
        return charts == null ? java.util.List.of() : charts;
    }

    /** Pre-signal archive lines deserialize with {@code signals == null}. */
    public java.util.List<SignalValue> signalsOrEmpty() {
        return signals == null ? java.util.List.of() : signals;
    }

    /** Pre-signal shape — keeps existing positional call sites compiling. */
    public DeepDiveRecord(String id, String subject, String canonicalName, String ticker,
            String isin, long createdAtEpoch, String report, Double priceAtTime,
            String priceCurrency, int evidenceCount, int newsCount, long durationMs,
            java.util.List<ChartFigure> charts) {
        this(id, subject, canonicalName, ticker, isin, createdAtEpoch, report, priceAtTime,
                priceCurrency, evidenceCount, newsCount, durationMs, charts, null);
    }

    /**
     * One figure of the report: a self-contained SVG (colors as CSS custom
     * properties with light-mode fallbacks, so the UI themes it and the PDF
     * renders the fallbacks) anchored to one of the report's {@code ## }
     * sections by ordinal.
     *
     * @param section 0-based index of the report section the figure belongs under
     * @param title   the figure caption
     * @param note    source attribution shown beside the caption (e.g. "Consorsbank")
     * @param svg     the inline SVG markup (self-contained, no scripts)
     */
    public record ChartFigure(int section, String title, String note, String svg) {
    }
}
