package de.bsommerfeld.wsbg.terminal.signals.world;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Calibration of a physical lead chain via cross-correlation analysis.
 *
 * <p><b>Method:</b> for every shift lag = 1..maxLagDays the cross-correlation
 * corr(cause[t], effect[t+lag]) is computed
 * ({@link MathKit#crossCorrelationAtLag}) - classic cross-correlation-function
 * analysis after Box/Jenkins ("Time Series Analysis", 1970). The best lag is
 * the argmax of the correlation; the signal value is the Pearson correlation
 * at exactly this lag. No curating, only measuring: which cause series runs
 * against which effect series is decided by the caller at runtime.
 *
 * <p><b>Terminal inputs:</b> two daily fishing-net gauge series of the
 * world-context clients, e.g. a maritime or oil gauge as cause against a
 * price or politics gauge as effect; civil-layer gauges (emergency services,
 * strikes, transit) are also admissible as the cause series.
 */
public final class SupplyChainLag {

    /** Minimum overlap required on top of the maximum lag. */
    private static final int MIN_OVERLAP = 20;
    /** Below this overlap the interpretation carries a caution suffix. */
    private static final int COMFORTABLE_OVERLAP = 40;
    /** From here the lead chain counts as confirmed. */
    private static final double CONFIRMED = 0.4;
    /** From here a weak lead applies. */
    private static final double WEAK = 0.2;

    private SupplyChainLag() {
    }

    /**
     * Calibrates whether and by how many days of lead the cause series
     * precedes the effect series.
     *
     * @param causeSeries  cause gauge per day
     * @param effectSeries effect gauge per day (same days)
     * @param maxLagDays   maximum shift checked, in days (at least 1)
     * @param causeLabel   runtime name of the cause series
     * @param effectLabel  runtime name of the effect series
     * @return reading, or empty on unequal lengths, maxLagDays &lt; 1 or
     *         fewer than maxLagDays + {@value #MIN_OVERLAP} days
     */
    public static Optional<SignalReading> measure(double[] causeSeries, double[] effectSeries,
                                                  int maxLagDays, String causeLabel, String effectLabel) {
        if (causeSeries == null || effectSeries == null || causeLabel == null || effectLabel == null
                || maxLagDays < 1
                || causeSeries.length != effectSeries.length
                || causeSeries.length < maxLagDays + MIN_OVERLAP) {
            return Optional.empty();
        }

        int bestLag = 1;
        double bestCorr = Double.NEGATIVE_INFINITY;
        for (int lag = 1; lag <= maxLagDays; lag++) {
            double c = MathKit.crossCorrelationAtLag(causeSeries, effectSeries, lag);
            if (c > bestCorr) {
                bestCorr = c;
                bestLag = lag;
            }
        }
        double value = bestCorr;

        String interpretation;
        if (value >= CONFIRMED) {
            interpretation = "SUPPLY CHAIN CONFIRMED: " + causeLabel + " leads " + effectLabel
                    + " by ~" + bestLag + " days (r=" + MathKit.fmt(value, 2)
                    + "). If " + causeLabel + " spikes NOW, the window is open until the lag "
                    + "has passed - the rare case of a real, legal information edge, because "
                    + "the delay runs days to weeks.";
        } else if (value >= WEAK) {
            interpretation = "Weak lead: " + causeLabel + " precedes " + effectLabel
                    + " only loosely at the best lag of ~" + bestLag + " days (r="
                    + MathKit.fmt(value, 2)
                    + ") - use at most as a side indication next to harder evidence.";
        } else {
            interpretation = "No robust lead: even at the best lag (" + bestLag
                    + " days) " + causeLabel + " does not measurably couple to " + effectLabel
                    + " (r=" + MathKit.fmt(value, 2) + ") - discard this chain.";
        }
        if (causeSeries.length < maxLagDays + COMFORTABLE_OVERLAP) {
            interpretation += " Caution: only " + causeSeries.length + " days of data against up to "
                    + maxLagDays + " days of shift - the lag estimate is uncertain accordingly.";
        }

        return Optional.of(new SignalReading(
                "supply-chain-lag",
                "Lead-chain calibration (cross-correlation)",
                value,
                MathKit.fmt(value, 2) + " (Pearson r, scale -1 to 1, best lag " + bestLag + " days)",
                "Measures whether and by how many days of delay the cause series (" + causeLabel
                        + ") leads the effect series (" + effectLabel
                        + ") - calibrates a physical lead chain purely from the data.",
                interpretation));
    }
}
