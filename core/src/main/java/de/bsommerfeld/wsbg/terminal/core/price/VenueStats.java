package de.bsommerfeld.wsbg.terminal.core.price;

/**
 * Order-book and turnover statistics for one instrument at one trading venue —
 * the depth data a plain price snapshot ({@code MarketSnapshot}) doesn't carry:
 * the live bid/ask WITH sizes, the day's traded volume in shares and euros, and
 * the number of executions. Currently fed by Tradegate (the one German retail
 * venue that publishes all of this keylessly, by ISIN); the record is
 * venue-neutral so a second provider can slot in behind the same seam.
 *
 * <p>Missing scalar figures are {@link Double#NaN}; missing counts are {@code -1}
 * (the {@code MarketSnapshot} conventions).
 *
 * @param venue            display label of the quoting venue (e.g. {@code "Tradegate"})
 * @param bid              best bid, EUR
 * @param ask              best ask, EUR
 * @param bidSize          shares behind the bid, {@code -1} unknown
 * @param askSize          shares behind the ask, {@code -1} unknown
 * @param last             last traded price, EUR
 * @param dayChangePercent day move in percent as the venue reports it
 * @param dayHigh          day high, EUR
 * @param dayLow           day low, EUR
 * @param previousClose    previous close, EUR
 * @param volumeShares     shares traded so far today, {@code -1} unknown
 * @param turnoverEur      EUR turnover so far today, {@code -1} unknown
 * @param executions       number of trades so far today, {@code -1} unknown
 * @param fetchedAtEpochSeconds wall-clock second the stats were fetched
 */
public record VenueStats(
        String venue,
        double bid,
        double ask,
        long bidSize,
        long askSize,
        double last,
        double dayChangePercent,
        double dayHigh,
        double dayLow,
        double previousClose,
        long volumeShares,
        long turnoverEur,
        long executions,
        long fetchedAtEpochSeconds) {

    /** Spread in percent of the mid, or {@link Double#NaN} when bid/ask are absent. */
    public double spreadPercent() {
        if (!Double.isFinite(bid) || !Double.isFinite(ask) || bid <= 0 || ask <= 0) return Double.NaN;
        double mid = (bid + ask) / 2.0;
        return mid > 0 ? (ask - bid) / mid * 100.0 : Double.NaN;
    }

    /** True when the stats carry a usable bid/ask pair. */
    public boolean hasQuote() {
        return Double.isFinite(bid) && Double.isFinite(ask);
    }
}
