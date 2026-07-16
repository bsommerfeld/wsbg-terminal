package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Sentiment divergence cage vs Wall Street: z-score of the current difference
 * between the in-house Reddit barometer and an external Fear&amp;Greed index,
 * plus a CUSUM shift detector.
 *
 * <p>Numerics: from both 0-100 series the divergence series
 * d = cage - external is built; the signal value is the z-score of the last d
 * against the history of the series. Additionally a one-sided CUSUM runs in
 * both directions (Page 1954; reference value k = 0.5 sigma, threshold
 * h = 4 sigma, the standard parametrization of the SPC literature) over d -
 * if it fires at the current edge, there is a fresh, persistent shift of the
 * divergence that a single z-score does not yet show. Background is the
 * sentiment-spread literature (retail vs institutional as a contrarian
 * indicator, cf. Baker/Wurgler 2006).
 *
 * <p>Terminal input: the history of the in-house Reddit sentiment barometer
 * (0-100) and the equally-clocked history of an external Fear&amp;Greed index
 * (0-100), both from the market memory.
 */
public final class SentimentDivergence {

    private static final String ID = "sentiment-divergence";
    private static final String TITLE = "Sentiment divergence cage vs Wall Street (CUSUM)";
    private static final String DEFINITION =
            "Measures how unusually far the Reddit barometer currently deviates"
                    + " from the external Fear&Greed (z-score of the current"
                    + " divergence against its own history), plus a CUSUM detector"
                    + " for a fresh shift of the divergence.";

    private static final int MIN_LENGTH = 20;
    private static final int THIN_LENGTH = 40;
    private static final double Z_THRESHOLD = 1.5;
    private static final double CUSUM_K_SIGMA = 0.5;
    private static final double CUSUM_H_SIGMA = 4.0;

    private SentimentDivergence() {
    }

    /**
     * Computes z-score and CUSUM status of the divergence series. Both series
     * must have equal length and at least {@value #MIN_LENGTH} points,
     * otherwise {@link Optional#empty()}.
     *
     * @param cageSeries0to100     history of the in-house Reddit barometer (0-100)
     * @param externalSeries0to100 equally-clocked history of the external Fear&amp;Greed (0-100)
     */
    public static Optional<SignalReading> measure(
            double[] cageSeries0to100, double[] externalSeries0to100) {
        if (cageSeries0to100 == null || externalSeries0to100 == null
                || cageSeries0to100.length != externalSeries0to100.length
                || cageSeries0to100.length < MIN_LENGTH) {
            return Optional.empty();
        }
        int n = cageSeries0to100.length;
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(cageSeries0to100[i]) || !Double.isFinite(externalSeries0to100[i])) {
                return Optional.empty();
            }
            d[i] = cageSeries0to100[i] - externalSeries0to100[i];
        }
        double last = d[n - 1];
        double value = MathKit.zScore(last, d);
        int cusum = cusumDirection(d);

        String formatted = "z = " + fmtSigned(value) + " (current divergence "
                + fmtSigned(last) + " points on the 0-100 scale)";
        return Optional.of(new SignalReading(
                ID, TITLE, value, formatted, DEFINITION, interpret(value, cusum, n)));
    }

    /**
     * One-sided CUSUM in both directions (k = 0.5 sigma, h = 4 sigma).
     * Returns +1 if an upward alarm is active at the end of the series, -1 on
     * a downward alarm, 0 otherwise (also for a degenerate series).
     */
    private static int cusumDirection(double[] d) {
        double sigma = MathKit.std(d);
        if (sigma == 0 || !Double.isFinite(sigma)) {
            return 0;
        }
        double target = MathKit.mean(d);
        double k = CUSUM_K_SIGMA * sigma;
        double h = CUSUM_H_SIGMA * sigma;
        double sPlus = 0, sMinus = 0;
        for (double x : d) {
            sPlus = Math.max(0, sPlus + (x - target - k));
            sMinus = Math.max(0, sMinus + (target - x - k));
        }
        if (sPlus > h) return 1;
        if (sMinus > h) return -1;
        return 0;
    }

    private static String interpret(double value, int cusum, int n) {
        String band;
        if (value >= Z_THRESHOLD) {
            band = "CAGE GREEDY, WALL STREET FEARFUL: the classic contrarian"
                    + " constellation - retail buys into institutional fear,"
                    + " historically a bad omen for the retail side.";
        } else if (value <= -Z_THRESHOLD) {
            band = "CAGE FEARFUL, WALL STREET GREEDY: retail capitulation amid"
                    + " institutional confidence - often a late bottoming pattern.";
        } else {
            band = "Cage and Wall Street are in sync - the current divergence"
                    + " lies within the normal noise of its history.";
        }
        if (cusum > 0) {
            band += " Additionally: fresh shift of the divergence just recently"
                    + " (upward CUSUM alarm, the cage is persistently detaching"
                    + " upward from Wall Street).";
        } else if (cusum < 0) {
            band += " Additionally: fresh shift of the divergence just recently"
                    + " (downward CUSUM alarm, the cage is persistently detaching"
                    + " downward from Wall Street).";
        }
        if (n < THIN_LENGTH) {
            band += " Caution: only " + n + " points of history, z-score and CUSUM"
                    + " are accordingly shaky.";
        }
        return band;
    }

    private static String fmtSigned(double v) {
        return (v >= 0 ? "+" : "") + MathKit.fmt(v, 2);
    }
}
