package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.List;

/**
 * The street's action trail for one listing — the analyst-rating HISTORY
 * (who upgraded/downgraded/re-set a target, when, from what to what) plus the
 * page-level consensus and, for US listings, the exchange short-interest facts
 * including the <b>percent of float</b> no other seam carries. Currently fed by
 * MarketBeat's server-rendered stock pages (keyless, no bot wall, probed
 * 2026-07-14); venue-neutral so another provider can slot in behind the seam.
 *
 * <p>Missing scalars are {@link Double#NaN} / {@code -1}; missing strings are
 * {@code null}. Money figures carry their currency STRING verbatim-normalized
 * ({@code "USD"}, {@code "GBX"}, {@code "EUR"}, …) because a non-US listing's
 * targets arrive in the listing currency, and GBX (pence) must never be read
 * as pounds.
 *
 * @param consensusRating       the page's consensus label (e.g. "Moderate Buy"), null unknown
 * @param consensusTarget       consensus price target in {@code consensusCurrency}, NaN unknown
 * @param consensusCurrency     currency of the consensus target, null unknown
 * @param actions               individual analyst actions, newest first, capped, may be empty
 * @param shortStats            the US short-interest facts, null when the tab is missing
 *                              (non-US listing / uncovered symbol)
 * @param fetchedAtEpochSeconds wall-clock second the pages were read
 */
public record AnalystActions(
        String consensusRating,
        double consensusTarget,
        String consensusCurrency,
        List<Action> actions,
        UsShortStats shortStats,
        long fetchedAtEpochSeconds) {

    /**
     * One analyst action — a row of the rating history or of the day's
     * ratings table.
     *
     * @param symbol         the ticker the action belongs to, null on the per-symbol
     *                       history (the caller asked by symbol); set on daily-table rows
     * @param companyName    the company name, null except on daily-table rows
     * @param dateIso        action date (ISO {@code yyyy-MM-dd}), null unknown
     * @param brokerage      the issuing firm, verbatim (e.g. "Morgan Stanley")
     * @param analyst        the named analyst, null when the row carries none
     * @param actionType     the provider's action label verbatim ("Upgrade",
     *                       "Downgrade", "Reiterated Rating", "Initiated Coverage",
     *                       "Boost Target", "Lower Target", "Set Target",
     *                       "Target Set by", …)
     * @param ratingOld      the prior rating half, null unknown (new coverage / target-only)
     * @param ratingNew      the new rating half, null unknown
     * @param targetOld      prior price target in {@code targetCurrency}, NaN unknown
     *                       (the provider marks "no prior target" as a zero — mapped to NaN)
     * @param targetNew      new price target, NaN unknown
     * @param targetCurrency currency of both target halves ("USD", "GBX", …), null
     *                       when neither half carries a figure
     */
    public record Action(
            String symbol,
            String companyName,
            String dateIso,
            String brokerage,
            String analyst,
            String actionType,
            String ratingOld,
            String ratingNew,
            double targetOld,
            double targetNew,
            String targetCurrency) {
    }

    /**
     * The US exchange short-interest facts — bi-monthly settlement data. The
     * <b>percentOfFloat</b> is the one figure the German register
     * ({@link ShortInterest}) and FINRA's daily short VOLUME cannot answer.
     *
     * @param currentShares     shares currently held short, -1 unknown
     * @param priorShares       shares short at the previous settlement, -1 unknown
     * @param dollarVolumeUsd   dollar volume sold short in USD, NaN unknown
     * @param daysToCover       short interest / average daily volume, NaN unknown
     * @param percentOfFloat    short interest as percent of the public float, NaN unknown
     * @param settlementDateIso the settlement ("last record") date (ISO), null unknown
     */
    public record UsShortStats(
            long currentShares,
            long priorShares,
            double dollarVolumeUsd,
            double daysToCover,
            double percentOfFloat,
            String settlementDateIso) {
    }
}
