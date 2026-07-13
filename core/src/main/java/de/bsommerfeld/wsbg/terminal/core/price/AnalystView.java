package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.List;

/**
 * The street's current read on a stock — analyst rating distribution, consensus
 * price target and the upcoming corporate events (earnings dates). Currently fed
 * by Consorsbank's financial-info API (keyless, by ISIN); venue-neutral so
 * another provider can slot in behind the same seam. Stocks only — an ETF/fund
 * ISIN has no covering analysts and simply resolves to empty.
 *
 * <p>Missing scalars are {@link Double#NaN} / {@code -1}; a distribution that
 * never loaded has {@code total <= 0}. The five-tier split follows the sell-side
 * scale (Buy / Overweight / Hold / Underweight / Sell); the {@code *3m} columns
 * are the same distribution three months ago — the trend a single snapshot
 * can't show. {@code upgrades}/{@code downgrades} count recent rating changes.
 *
 * @param buy                      analysts currently at Buy
 * @param overweight               … at Overweight/Accumulate
 * @param hold                     … at Hold/Neutral
 * @param underweight              … at Underweight/Reduce
 * @param sell                     … at Sell
 * @param total                    covering analysts now (≤ 0 = no coverage data)
 * @param buy3m                    Buy count three months ago (-1 unknown)
 * @param overweight3m             Overweight count three months ago
 * @param hold3m                   Hold count three months ago
 * @param underweight3m            Underweight count three months ago
 * @param sell3m                   Sell count three months ago
 * @param upgrades                 recent upward rating revisions (-1 unknown)
 * @param downgrades               recent downward rating revisions (-1 unknown)
 * @param targetPrice              consensus price target, NaN unknown
 * @param targetCurrency           ISO currency of the target (e.g. "EUR"), null unknown
 * @param expectedUpsidePercent    implied move to the target from the source's
 *                                 reference price, NaN unknown
 * @param lastUpdateEpochSeconds   the source's own data timestamp (0 unknown)
 * @param events                   upcoming corporate events, soonest first
 *                                 (never null, may be empty)
 * @param fetchedAtEpochSeconds    wall-clock second this view was fetched
 */
public record AnalystView(
        int buy,
        int overweight,
        int hold,
        int underweight,
        int sell,
        int total,
        int buy3m,
        int overweight3m,
        int hold3m,
        int underweight3m,
        int sell3m,
        int upgrades,
        int downgrades,
        double targetPrice,
        String targetCurrency,
        double expectedUpsidePercent,
        long lastUpdateEpochSeconds,
        List<CorporateEvent> events,
        long fetchedAtEpochSeconds) {

    /** One dated corporate event (earnings report, AGM, …). */
    public record CorporateEvent(long atEpochSeconds, String type, String title) {
    }

    /** Whether any rating distribution loaded (an events-only view returns false). */
    public boolean hasRatings() {
        return total > 0;
    }

    /** The next event at or after {@code nowEpochSeconds}, or {@code null}. */
    public CorporateEvent nextEvent(long nowEpochSeconds) {
        for (CorporateEvent e : events) {
            if (e.atEpochSeconds() >= nowEpochSeconds) return e;
        }
        return null;
    }
}
