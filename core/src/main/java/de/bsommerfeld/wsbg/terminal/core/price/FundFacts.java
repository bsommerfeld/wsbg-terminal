package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.List;

/**
 * Profile facts for a FUND/ETF — the counterpart to the stock-only
 * {@link InstrumentFacts}: costs, size, benchmark and the top holdings.
 * Currently fed by onvista's funds snapshot (keyless, by ISIN).
 *
 * @param name                  the fund's official name
 * @param terPercent            total expense ratio in percent, NaN unknown
 * @param volumeEur             fund volume in EUR, NaN unknown
 * @param benchmark             benchmark name (e.g. "MSCI World NR USD"), null unknown
 * @param morningstarRating     Morningstar stars 1-5, -1 unknown
 * @param description           prose fund description (German), null unknown
 * @param topHoldings           the largest holdings, largest first (may be empty)
 * @param fetchedAtEpochSeconds wall-clock second the facts were fetched
 */
public record FundFacts(
        String name,
        double terPercent,
        double volumeEur,
        String benchmark,
        int morningstarRating,
        String description,
        List<Holding> topHoldings,
        long fetchedAtEpochSeconds) {

    /** One fund holding with its portfolio weight. */
    public record Holding(String name, double weightPercent) {
    }
}
