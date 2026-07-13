package de.bsommerfeld.wsbg.terminal.core.price;

/**
 * Slow-moving company/instrument profile facts — the "what IS this" data a
 * quote can't tell: sector, size, valuation, workforce. Currently fed by
 * onvista's stocks snapshot (keyless, by ISIN); venue-neutral so another
 * provider can slot in behind the same seam. Stocks only — funds/ETFs/crypto
 * have no company profile and simply resolve to empty.
 *
 * <p>Missing scalars are {@link Double#NaN} / {@code -1}; missing strings are
 * {@code null}. Valuation figures carry their fiscal-year LABEL (e.g.
 * {@code "2025"}, {@code "26/27e"} — a trailing {@code e} marks an estimate)
 * so a consumer never presents an estimate as an actual.
 *
 * @param companyName      official company name (e.g. "RHEINMETALL AG")
 * @param country          localized country name (e.g. "Deutschland", "USA")
 * @param sector           top-level sector (e.g. "Industrie"), or null
 * @param branch           finer branch (e.g. "Maschinenbau"), or null
 * @param marketCapEur     company market capitalization in EUR
 * @param employees        workforce of the latest ACTUAL fiscal year, -1 unknown
 * @param employeesLabel   fiscal-year label the employee figure belongs to
 * @param peRatio          price/earnings ratio, NaN unknown
 * @param peLabel          fiscal-year label of the P/E figure
 * @param divYieldPercent  dividend yield in percent, NaN unknown
 * @param divLabel         fiscal-year label of the dividend yield
 * @param avgVolume30d     average daily volume over ~30 trading days (shares),
 *                         NaN unknown — the yardstick that makes a day's venue
 *                         volume readable
 * @param fetchedAtEpochSeconds wall-clock second the facts were fetched
 */
public record InstrumentFacts(
        String companyName,
        String country,
        String sector,
        String branch,
        double marketCapEur,
        long employees,
        String employeesLabel,
        double peRatio,
        String peLabel,
        double divYieldPercent,
        String divLabel,
        double avgVolume30d,
        long fetchedAtEpochSeconds) {
}
