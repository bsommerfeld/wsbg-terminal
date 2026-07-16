package de.bsommerfeld.wsbg.terminal.signals.price;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Spread anxiety sensor: the market-maker's risk premium as a leading clue.
 *
 * <p><b>Method:</b> The relative bid-ask spread (ask - bid divided by the
 * mid price) is z-scored against the instrument's own spread history
 * ({@link MathKit#zScore}). The theory anchor is the microstructure
 * literature on adverse selection (Glosten/Milgrom 1985): the spread is the
 * premium the market-maker charges against informed counterparties. If it
 * blows out with neither a price move nor a fresh headline to explain it,
 * the professional fears something non-public - exactly that combination
 * arms the signal.
 *
 * <p><b>Terminal inputs:</b> bid/ask come live from the venue quotes, the
 * historical relative spreads from the running price snapshots of the same
 * instrument, the recent absolute price move from the price series, and the
 * headline flag from the headline archive's ticker attribution.
 */
public final class SpreadAnxiety {

    /** Below this history length no z-score is reliable. */
    private static final int MIN_HISTORY = 30;
    /** Below this history length the interpretation carries a caution suffix. */
    private static final int COMFORTABLE_HISTORY = 60;
    /** At or above this z-score the risk premium counts as blown out. */
    private static final double Z_WIDE = 2.0;
    /** At or below this z-score the spread counts as unusually tight. */
    private static final double Z_TIGHT = -1.5;
    /** Below this absolute move (in percent) the price counts as quiet. */
    private static final double QUIET_RETURN_PCT = 0.5;

    private SpreadAnxiety() {
    }

    /**
     * Measures the current market-maker risk premium against its own history.
     *
     * @param bid                       current bid, must be &gt; 0
     * @param ask                       current ask, must be &gt;= bid
     * @param historicalRelativeSpreads historical relative spreads of the same instrument
     * @param recentAbsReturnPct        absolute recent price move in percent
     * @param freshHeadlinePresent      whether a fresh headline exists for this instrument
     * @return reading, or empty on invalid quotes or fewer than
     *         {@value #MIN_HISTORY} historical spreads
     */
    public static Optional<SignalReading> measure(double bid, double ask,
            double[] historicalRelativeSpreads, double recentAbsReturnPct,
            boolean freshHeadlinePresent) {
        if (!Double.isFinite(bid) || !Double.isFinite(ask) || bid <= 0 || ask <= 0 || ask < bid) {
            return Optional.empty();
        }
        if (historicalRelativeSpreads == null || historicalRelativeSpreads.length < MIN_HISTORY) {
            return Optional.empty();
        }

        double mid = (bid + ask) / 2;
        double relativeSpread = (ask - bid) / mid;
        double z = MathKit.zScore(relativeSpread, historicalRelativeSpreads);

        String interpretation;
        if (z >= Z_WIDE && recentAbsReturnPct < QUIET_RETURN_PCT && !freshHeadlinePresent) {
            interpretation = "ANXIETY SENSOR FIRING: the market-maker's risk premium is rising "
                    + "with no public cause - neither a price move nor a fresh headline explains "
                    + "the jump. Leading clue, high-priority research trigger.";
        } else if (z >= Z_WIDE) {
            interpretation = "Risk premium elevated but consistent with the news flow - "
                    + "a price move or fresh headline explains the wide spread, "
                    + "no extra signal.";
        } else if (z <= Z_TIGHT) {
            interpretation = "Unusually tight spread - the market-maker sees calm waters "
                    + "and charges less risk premium than usual.";
        } else {
            interpretation = "Unremarkable: the spread sits inside its own historical band.";
        }
        if (historicalRelativeSpreads.length < COMFORTABLE_HISTORY) {
            interpretation += " Caution: only " + historicalRelativeSpreads.length
                    + " historical spreads as comparison base - the z-score is accordingly uncertain.";
        }

        return Optional.of(new SignalReading(
                "spread-anxiety",
                "Spread anxiety sensor (market-maker risk premium)",
                z,
                MathKit.fmt(z, 2) + " (z-score of the relative spread; currently "
                        + MathKit.fmt(relativeSpread * 100, 3) + " %)",
                "Measures how far the market-maker's current risk premium - the relative "
                        + "bid-ask spread - deviates from its own historical normal band.",
                interpretation));
    }
}
