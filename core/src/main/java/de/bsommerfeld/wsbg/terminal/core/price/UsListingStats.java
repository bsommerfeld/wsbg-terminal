package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.List;

/**
 * The US listing view of one company — what NASDAQ's own site shows under a
 * ticker's tabs: listing identity, the FINRA short-interest history, SEC Form-4
 * insider trades with their 3/12-month aggregate, 13F institutional ownership,
 * the analyst consensus with price targets, and the quarterly earnings-surprise
 * track record. US-listed symbols only; other markets resolve to empty.
 *
 * <p>Every leg is present-with-empty: a company without a tab (no analysts, no
 * insider filings) carries an empty list / {@code null} sub-record — the record
 * itself still answers. Unknown doubles are {@code NaN}, unknown longs are
 * {@code -1}, unknown strings are {@code null}.
 *
 * @param symbol                the US ticker (UPPER)
 * @param companyName           the listing name (e.g. "Apple Inc. Common Stock"), null unknown
 * @param exchange              the listing venue tier (e.g. "NASDAQ-GS", "NASDAQ-CM"), null unknown
 * @param sector                NASDAQ's sector label, null unknown
 * @param industry              NASDAQ's industry label, null unknown
 * @param marketCapUsd          market capitalization in USD, NaN unknown
 * @param avgDailyVolume        average daily share volume, -1 unknown
 * @param dividendYieldPercent  current dividend yield in percent, NaN unknown (or none)
 * @param shortInterest         bi-monthly FINRA settlement points, newest first, may be empty
 * @param insiderActivity       the 3/12-month insider trade aggregate, null when the tab is missing
 * @param insiderTrades         individual insider transactions, newest first, may be empty
 * @param institutionalOwnership 13F ownership summary + top holders, null when the tab is missing
 * @param analystRatings        consensus + price targets, null when uncovered
 * @param earningsSurprises     reported quarters vs consensus, newest first, may be empty
 * @param fetchedAtEpochSeconds wall-clock second the tabs were read
 */
public record UsListingStats(
        String symbol,
        String companyName,
        String exchange,
        String sector,
        String industry,
        double marketCapUsd,
        long avgDailyVolume,
        double dividendYieldPercent,
        List<ShortInterestPoint> shortInterest,
        InsiderActivity insiderActivity,
        List<InsiderTrade> insiderTrades,
        InstitutionalOwnership institutionalOwnership,
        AnalystRatings analystRatings,
        List<EarningsSurprise> earningsSurprises,
        long fetchedAtEpochSeconds) {

    /**
     * One FINRA short-interest settlement point.
     *
     * @param settlementDateIso   settlement date (ISO {@code yyyy-MM-dd})
     * @param shortInterestShares total shares held short, -1 unknown
     * @param avgDailyShareVolume average daily volume over the period, -1 unknown
     * @param daysToCover         short interest / average volume, NaN unknown
     */
    public record ShortInterestPoint(String settlementDateIso, long shortInterestShares,
            long avgDailyShareVolume, double daysToCover) {
    }

    /**
     * The insider-trades tab's aggregate counters.
     *
     * @param buys3m        open-market buys in the last 3 months, -1 unknown
     * @param sells3m       sells in the last 3 months, -1 unknown
     * @param buys12m       open-market buys in the last 12 months, -1 unknown
     * @param sells12m      sells in the last 12 months, -1 unknown
     * @param netShares3m   net shares (bought − sold) last 3 months; negative = net selling
     * @param netShares12m  net shares last 12 months; {@code Long.MIN_VALUE} unknown
     *                      (net is legitimately negative, so -1 can't mark it)
     */
    public record InsiderActivity(long buys3m, long sells3m, long buys12m, long sells12m,
            long netShares3m, long netShares12m) {
    }

    /**
     * One SEC Form-4 insider transaction.
     *
     * @param dateIso      transaction date (ISO {@code yyyy-MM-dd}), null unknown
     * @param insider      the reporting person, verbatim (usually "LAST FIRST")
     * @param relation     the person's role ("Director", "Officer", …), null unknown
     * @param transaction  NASDAQ's transaction label verbatim ("Buy", "Sell",
     *                     "Automatic Sell", "Acquisition (Non Open Market)", …)
     * @param direction    classified: {@code "buy"} / {@code "sell"} / {@code "other"}
     * @param sharesTraded shares in the transaction, -1 unknown
     * @param priceUsd     the reported price per share in USD, NaN unknown
     * @param sharesHeld   shares held after the transaction, -1 unknown
     */
    public record InsiderTrade(String dateIso, String insider, String relation,
            String transaction, String direction,
            long sharesTraded, double priceUsd, long sharesHeld) {
    }

    /**
     * 13F institutional ownership.
     *
     * @param ownershipPercent      institutional ownership in percent of shares
     *                              outstanding, NaN unknown
     * @param totalHolders          number of institutional holders, -1 unknown
     * @param totalSharesHeld       total institutional shares, -1 unknown
     * @param totalValueMillionsUsd total value of holdings in millions USD, NaN unknown
     * @param topHolders            the largest positions, biggest first, may be empty
     */
    public record InstitutionalOwnership(double ownershipPercent, long totalHolders,
            long totalSharesHeld, double totalValueMillionsUsd, List<Holder> topHolders) {

        /**
         * One institutional position.
         *
         * @param name                    the holder's name
         * @param sharesHeld              shares held, -1 unknown
         * @param marketValueThousandsUsd position value in THOUSANDS of USD
         *                                (NASDAQ's own unit), NaN unknown
         * @param asOfDateIso             filing date (ISO), null unknown
         */
        public record Holder(String name, long sharesHeld, double marketValueThousandsUsd,
                String asOfDateIso) {
        }
    }

    /**
     * The analyst consensus.
     *
     * @param consensusLabel     NASDAQ's mean rating label ("Buy" / "Hold" / …), null unknown
     * @param analystCount       analysts offering recommendations, -1 unknown (often
     *                           MORE than buy+hold+sell — targets come from a subset)
     * @param buy                buy recommendations behind the price target, -1 unknown
     * @param hold               hold recommendations, -1 unknown
     * @param sell               sell recommendations, -1 unknown
     * @param meanPriceTargetUsd consensus price target in USD, NaN unknown
     * @param highPriceTargetUsd highest target, NaN unknown
     * @param lowPriceTargetUsd  lowest target, NaN unknown
     */
    public record AnalystRatings(String consensusLabel, int analystCount,
            int buy, int hold, int sell,
            double meanPriceTargetUsd, double highPriceTargetUsd, double lowPriceTargetUsd) {
    }

    /**
     * One reported quarter vs the consensus estimate.
     *
     * @param fiscalQuarter   the fiscal quarter label verbatim (e.g. "Mar 2026")
     * @param reportedDateIso the report date (ISO {@code yyyy-MM-dd}), null unknown
     * @param epsActual       reported EPS in USD, NaN unknown
     * @param epsConsensus    consensus EPS forecast, NaN unknown
     * @param surprisePercent surprise in percent (negative = miss), NaN unknown
     */
    public record EarningsSurprise(String fiscalQuarter, String reportedDateIso,
            double epsActual, double epsConsensus, double surprisePercent) {
    }
}
