package de.bsommerfeld.wsbg.terminal.signals.flow;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * How crowded the SELLING side of a wave is, from the share of each session's
 * volume that printed as a short sale.
 *
 * <p><b>Method:</b> the level of the short share is deliberately never read.
 * Most of it is market makers hedging the other side of ordinary customer
 * orders, so a name can sit at 50 % short volume forever and mean nothing by
 * it - a fact that has misled more retail readers than almost any other public
 * number. What carries information is the DEVIATION from this name's own
 * recent normal: when the short share climbs while a wave runs, sellers are
 * arriving into the crowd rather than the crowd simply buying.
 *
 * <p>Measured as a z-score of the latest sessions against the trailing
 * baseline, the same shape {@link HypePhase} uses for volume - and, like it,
 * always against the name's OWN record, never a market-wide constant.
 *
 * <p><b>Terminal inputs:</b> FINRA's daily short-sale volume files, which
 * reach back years, so this axis is at full depth on its first request.
 */
public final class ShortSideCrowding {

    /** Below this many sessions there is no measurement. */
    private static final int MIN_DAYS = 25;
    /** Sessions averaged into the current reading. */
    private static final int RECENT = 3;
    /** Sessions of baseline kept clear of the recent stretch. */
    private static final int BASELINE_GAP = 5;
    /** From this z-score the short side is crowding in. */
    private static final double CROWDING = 1.5;
    /** From this z-score the short side is stepping away. */
    private static final double RETREAT = -1.5;

    private ShortSideCrowding() {
    }

    /**
     * Measures the short side against the name's own normal.
     *
     * @param shortShare per-session short share of volume, oldest first
     *                   (0-1); non-finite entries disqualify the series
     * @return the reading, or empty when the series is too short
     */
    public static Optional<SignalReading> measure(double[] shortShare) {
        if (shortShare == null || shortShare.length < MIN_DAYS) return Optional.empty();
        for (double v : shortShare) {
            if (!Double.isFinite(v) || v < 0 || v > 1) return Optional.empty();
        }
        int n = shortShare.length;
        int baselineTo = n - RECENT - BASELINE_GAP;
        if (baselineTo < MIN_DAYS - RECENT - BASELINE_GAP) return Optional.empty();
        double[] baseline = new double[baselineTo];
        System.arraycopy(shortShare, 0, baseline, 0, baselineTo);
        double[] recent = new double[RECENT];
        System.arraycopy(shortShare, n - RECENT, recent, 0, RECENT);

        double now = MathKit.mean(recent);
        double z = MathKit.zScore(now, baseline);
        double base = MathKit.mean(baseline);

        String interpretation;
        if (z >= CROWDING) {
            interpretation = "CROWDING IN: the short share has risen well above this name's own "
                    + "normal - sellers are arriving into the move, not just the crowd buying.";
        } else if (z <= RETREAT) {
            interpretation = "STEPPING AWAY: the short share has fallen well below this name's "
                    + "own normal - the selling side is lighter than it usually is here.";
        } else {
            interpretation = "The short share sits inside this name's own normal range - "
                    + "the selling side is neither crowding in nor stepping away.";
        }
        return Optional.of(new SignalReading(
                "short-side-crowding",
                "Short side against own normal",
                z,
                MathKit.fmt(now * 100, 0) + " % of volume printed short (own normal "
                        + MathKit.fmt(base * 100, 0) + " %, " + MathKit.fmt(z, 1)
                        + " standard deviations)",
                "Deviation of the recent short-sale share of volume from this name's own "
                        + "trailing average, over " + n + " sessions.",
                interpretation + " NEVER read the LEVEL as bearishness: most short volume is "
                        + "market makers hedging ordinary customer orders, and this figure is "
                        + "day-to-day trading, NOT the twice-monthly short interest position."));
    }
}
