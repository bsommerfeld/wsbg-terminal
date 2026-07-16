package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Map;
import java.util.Optional;

/**
 * Estimate fan: relative change of the dispersion of multi-year estimates
 * between two snapshots.
 *
 * <p>Numerics: per snapshot the coefficient of variation CV = std / |mean| is
 * computed over the yearly estimates (sample std, n-1; the CV as a scale-free
 * dispersion measure goes back to Pearson 1896); the signal value is the
 * relative change (cvNow - cvPrev) / cvPrev. If the fan was fully closed
 * before (cvPrev = 0) and opens, that counts as +1. What is measured is
 * whether the fan of multi-year estimates opens (steeper, more uncertain
 * growth path) or collapses - the change marks the moment analysts rewrite
 * the equity story before there is a headline.
 *
 * <p>Terminal input: two time-shifted Consorsbank KeyFigures snapshots of the
 * same stock with consensus estimates per year (e.g. EPS up to 2029).
 */
public final class EstimateFanDivergence {

    private static final String ID = "estimate-fan-divergence";
    private static final String TITLE = "Estimate fan (multi-year divergence)";
    private static final String DEFINITION =
            "Measures whether the fan of multi-year estimates has opened since"
                    + " the last snapshot (path becomes more uncertain) or collapsed"
                    + " (path becomes plannable) - as the relative change of the"
                    + " coefficient of variation across the estimate years.";

    private static final int MIN_YEARS = 3;
    private static final int THIN_YEARS = 4;
    private static final double OPEN_THRESHOLD = 0.2;
    private static final double COLLAPSE_THRESHOLD = -0.2;

    private EstimateFanDivergence() {
    }

    /**
     * Computes the relative change of the estimate fan. Both snapshots need
     * at least {@value #MIN_YEARS} estimate years and a mean != 0, otherwise
     * {@link Optional#empty()}.
     *
     * @param currentByYear  estimates per year from the current snapshot
     * @param previousByYear estimates per year from the previous snapshot
     */
    public static Optional<SignalReading> measure(
            Map<Integer, Double> currentByYear,
            Map<Integer, Double> previousByYear) {
        double cvNow = cvOrNaN(currentByYear);
        double cvPrev = cvOrNaN(previousByYear);
        if (Double.isNaN(cvNow) || Double.isNaN(cvPrev)) {
            return Optional.empty();
        }
        double value;
        if (cvPrev == 0) {
            value = cvNow > 0 ? 1.0 : 0.0;
        } else {
            value = (cvNow - cvPrev) / cvPrev;
        }
        int minYears = Math.min(currentByYear.size(), previousByYear.size());

        String formatted = fmtSigned(value) + " relative fan change (CV now "
                + MathKit.fmt(cvNow, 3) + ", before " + MathKit.fmt(cvPrev, 3) + ")";
        return Optional.of(new SignalReading(
                ID, TITLE, value, formatted, DEFINITION,
                interpret(value, cvNow, cvPrev, minYears)));
    }

    /** CV = std/|mean| over the yearly values, NaN on too-thin data or zero mean. */
    private static double cvOrNaN(Map<Integer, Double> byYear) {
        if (byYear == null || byYear.size() < MIN_YEARS) {
            return Double.NaN;
        }
        double[] values = new double[byYear.size()];
        int i = 0;
        for (Double v : byYear.values()) {
            if (v == null || !Double.isFinite(v)) {
                return Double.NaN;
            }
            values[i++] = v;
        }
        double mean = MathKit.mean(values);
        if (mean == 0) {
            return Double.NaN;
        }
        return MathKit.std(values) / Math.abs(mean);
    }

    private static String interpret(double value, double cvNow, double cvPrev, int minYears) {
        String cvs = " (CV now " + MathKit.fmt(cvNow, 3) + " against before "
                + MathKit.fmt(cvPrev, 3) + ")";
        String band;
        if (value >= OPEN_THRESHOLD) {
            band = "FAN OPENING: uncertainty about the multi-year path is"
                    + " rising" + cvs + " - the stock is turning into a story"
                    + " stock, the valuation multiple becomes more vulnerable.";
        } else if (value <= COLLAPSE_THRESHOLD) {
            band = "FAN COLLAPSING: the estimates are converging to substance"
                    + cvs + " - the story becomes plannable, the path loses"
                    + " surprise potential in both directions.";
        } else {
            band = "The fan is stable" + cvs + " - uncertainty about the"
                    + " multi-year path has not changed notably.";
        }
        if (minYears < THIN_YEARS) {
            band += " Caution: only " + minYears + " estimate years in the smaller"
                    + " snapshot, the coefficient of variation is accordingly shaky.";
        }
        return band;
    }

    private static String fmtSigned(double v) {
        return (v >= 0 ? "+" : "") + MathKit.fmt(v, 2);
    }
}
