package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.List;

/**
 * The hedge-fund popularity view of one US-listed company — Insider Monkey's
 * 13F-derived quarterly curve ("how many hedge funds hold X") with the
 * quarter-end price, plus the most recent SEC Form-4 insider transactions from
 * the same page. 13F cadence means ~45 days of filing lag; the current quarter
 * may still be filling up ({@link QuarterPoint#ongoing()}).
 *
 * <p>Unknown doubles are {@code NaN}, unknown longs are {@code -1}, unknown
 * strings are {@code null}. Lists may be empty, never null.
 *
 * @param symbol            the US ticker (UPPER)
 * @param cik               the SEC CIK the page is addressed by
 * @param quarters          the popularity curve, oldest to newest as delivered
 * @param recentInsiderRows recent insider transactions, newest first, capped small
 */
public record HedgeFundPopularity(
        String symbol,
        long cik,
        List<QuarterPoint> quarters,
        List<InsiderRow> recentInsiderRows) {

    /**
     * One 13F filing quarter.
     *
     * @param filingPeriodIso    quarter end (ISO {@code yyyy-MM-dd})
     * @param quarterLabel       human label (e.g. {@code "Q4 2018"})
     * @param funds              hedge funds holding the stock, -1 unknown
     * @param totalShares        total shares held by them (split-adjusted, rounded), -1 unknown
     * @param newPositions       funds that opened a position this quarter; 0 when
     *                           the source omits the quarter (it omits zeros)
     * @param closedPositions    funds that closed out this quarter; 0 when omitted
     * @param quarterEndPriceUsd share price at the quarter end in USD, NaN unknown
     * @param ongoing            true while the quarter's filing window is still open
     */
    public record QuarterPoint(String filingPeriodIso, String quarterLabel, int funds,
            long totalShares, int newPositions, int closedPositions,
            double quarterEndPriceUsd, boolean ongoing) {
    }

    /**
     * One insider transaction row.
     *
     * @param dateIso         transaction date (ISO {@code yyyy-MM-dd}), null unknown
     * @param insider         the reporting person as displayed (usually the last name)
     * @param transaction     the page's section label verbatim ({@code "Purchases"} / {@code "Sales"})
     * @param shares          shares in the transaction, -1 unknown
     * @param priceUsd        price per share in USD, NaN unknown
     * @param totalValueUsd   total transaction value in USD, NaN unknown
     * @param remainingShares shares held after the transaction, -1 unknown
     */
    public record InsiderRow(String dateIso, String insider, String transaction,
            long shares, double priceUsd, double totalValueUsd, long remainingShares) {
    }
}
