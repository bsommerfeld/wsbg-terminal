package de.bsommerfeld.wsbg.terminal.signals.world;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Ground-unrest residual: does the civil layer show more unrest than the
 * national press picture explains?
 *
 * <p><b>Method:</b> over the history (excluding the last point) a simple
 * linear regression civil = a + b * press is fitted via least squares
 * (Legendre/Gauss, classic OLS residual analysis). The historical residuals
 * form the reference distribution; the signal value is the z-score of the
 * current residual (last point) against this distribution
 * ({@link MathKit#zScore}). Positive values mean: the civil layer is more
 * restless than the press picture accounts for.
 *
 * <p><b>Terminal inputs:</b> two daily index series from the fishing-net
 * gauge levels of the world-context clients - the civil layer (emergency
 * services, strikes, transit, clinics) as an aggregated unrest index against
 * an aggregated index of the national press picture.
 */
public final class GroundUnrestResidual {

    /** Below this day count no measurement. */
    private static final int MIN_DAYS = 30;
    /** Below this day count the interpretation carries a caution suffix. */
    private static final int COMFORTABLE_DAYS = 60;
    /** From here we are measurably early (sigma). */
    private static final double EARLY = 2.0;
    /** From here a slight head start applies (sigma). */
    private static final double SLIGHT = 1.0;

    private GroundUnrestResidual() {
    }

    /**
     * Measures the unexplained ground unrest as the z-score of the current
     * regression residual.
     *
     * @param civilIndexSeries civil-layer unrest index per day
     * @param pressIndexSeries press-picture index per day (same days)
     * @return reading, or empty on unequal lengths or fewer than {@value #MIN_DAYS} days
     */
    public static Optional<SignalReading> measure(double[] civilIndexSeries, double[] pressIndexSeries) {
        if (civilIndexSeries == null || pressIndexSeries == null
                || civilIndexSeries.length != pressIndexSeries.length
                || civilIndexSeries.length < MIN_DAYS) {
            return Optional.empty();
        }
        int n = civilIndexSeries.length;
        int h = n - 1; // history without the current (last) point

        // OLS civil = a + b * press over the history
        double meanPress = 0;
        double meanCivil = 0;
        for (int i = 0; i < h; i++) {
            meanPress += pressIndexSeries[i];
            meanCivil += civilIndexSeries[i];
        }
        meanPress /= h;
        meanCivil /= h;
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < h; i++) {
            double dx = pressIndexSeries[i] - meanPress;
            sxy += dx * (civilIndexSeries[i] - meanCivil);
            sxx += dx * dx;
        }
        double b = sxx == 0 ? 0 : sxy / sxx;
        double a = meanCivil - b * meanPress;

        double[] residuals = new double[h];
        for (int i = 0; i < h; i++) {
            residuals[i] = civilIndexSeries[i] - (a + b * pressIndexSeries[i]);
        }
        double currentResidual = civilIndexSeries[n - 1] - (a + b * pressIndexSeries[n - 1]);
        double value = MathKit.zScore(currentResidual, residuals);

        String interpretation;
        if (value >= EARLY) {
            interpretation = "WE ARE EARLY: the civil layer shows " + MathKit.fmt(value, 1)
                    + " sigma more unrest than the national press explains - ground unrest rising "
                    + "while the national press is still quiet, the story is still local; the "
                    + "window closes as soon as the press catches up, so dig in locally now.";
        } else if (value >= SLIGHT) {
            interpretation = "Slight head start: the civil layer sits somewhat above what the "
                    + "press picture explains - watch it, no robust edge yet.";
        } else {
            interpretation = "Civil layer covered by the press picture: no unexplained ground "
                    + "unrest, no information edge.";
        }
        if (n < COMFORTABLE_DAYS) {
            interpretation += " Caution: only " + n
                    + " days of history - the residual base is thin accordingly.";
        }

        return Optional.of(new SignalReading(
                "ground-unrest-residual",
                "Ground-unrest residual (civil layer vs press)",
                value,
                MathKit.fmt(value, 2) + " sigma (z-score of the current regression residual)",
                "Measures whether the civil layer shows more unrest today than the national "
                        + "press picture explains - the measurable net-sees-it-before-the-newsroom.",
                interpretation));
    }
}
