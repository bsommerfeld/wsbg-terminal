package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.List;
import java.util.Optional;

/**
 * Conditional hit rate: tests an arbitrary signal for whether it still works
 * under high retail attention.
 *
 * <p>Numerics: the trials are split into two buckets (high vs low attention),
 * per bucket the hit rate is estimated with a Wilson 90% interval (Wilson
 * 1927, z = 1.645); the signal value is the difference rateHigh - rateLow.
 * Background is the crowding literature (McLean/Pontiff 2016: published
 * anomalies lose significant return after publication) - public, hyped
 * signals are devalued by the attention itself. If the two intervals
 * overlap, the reading explicitly states that the difference is not
 * statistically secured.
 *
 * <p>Terminal input: historical signal firings from the market memory
 * (success = event definition met), the attention flag from the in-house
 * Reddit barometer or the mention counters of the Subject Registry at firing
 * time.
 */
public final class ConditionalHitRate {

    private static final String ID = "conditional-hit-rate";
    private static final String TITLE = "Conditional hit rate (crowding test)";
    private static final String DEFINITION =
            "Measures whether a signal still hits under high retail attention"
                    + " (hit rate under high minus under low attention) -"
                    + " crowding devalues public signals.";

    private static final int MIN_PER_BUCKET = 10;
    private static final int THIN_PER_BUCKET = 30;
    private static final double Z_90 = 1.645;
    private static final double DEVALUED_THRESHOLD = -0.15;
    private static final double AMPLIFIED_THRESHOLD = 0.15;

    /** A single historical signal case: hit yes/no under high/low attention. */
    public record Trial(boolean success, boolean highAttention) {
    }

    private ConditionalHitRate() {
    }

    /**
     * Computes the hit-rate difference between high and low attention.
     * Requires at least {@value #MIN_PER_BUCKET} trials in EACH bucket,
     * otherwise {@link Optional#empty()}.
     *
     * @param signalLabel label of the tested signal (display only)
     * @param trials      historical cases of the signal
     */
    public static Optional<SignalReading> measure(String signalLabel, List<Trial> trials) {
        if (signalLabel == null || trials == null) {
            return Optional.empty();
        }
        int nHigh = 0, sHigh = 0, nLow = 0, sLow = 0;
        for (Trial trial : trials) {
            if (trial == null) {
                continue;
            }
            if (trial.highAttention()) {
                nHigh++;
                if (trial.success()) sHigh++;
            } else {
                nLow++;
                if (trial.success()) sLow++;
            }
        }
        if (nHigh < MIN_PER_BUCKET || nLow < MIN_PER_BUCKET) {
            return Optional.empty();
        }
        double rateHigh = (double) sHigh / nHigh;
        double rateLow = (double) sLow / nLow;
        double[] ciHigh = MathKit.wilsonInterval(sHigh, nHigh, Z_90);
        double[] ciLow = MathKit.wilsonInterval(sLow, nLow, Z_90);
        double value = rateHigh - rateLow;

        String formatted = fmtSigned(value * 100) + " percentage points hit-rate difference"
                + " (high attention " + MathKit.fmt(rateHigh * 100, 0)
                + " %, low " + MathKit.fmt(rateLow * 100, 0) + " %)";
        return Optional.of(new SignalReading(ID, TITLE, value, formatted, DEFINITION,
                interpret(signalLabel, value, rateHigh, sHigh, nHigh, ciHigh,
                        rateLow, sLow, nLow, ciLow)));
    }

    private static String interpret(
            String signalLabel, double value,
            double rateHigh, int sHigh, int nHigh, double[] ciHigh,
            double rateLow, int sLow, int nLow, double[] ciLow) {
        String buckets = " Signal '" + signalLabel + "': hit rate under high"
                + " attention " + bucket(rateHigh, nHigh, ciHigh)
                + ", under low " + bucket(rateLow, nLow, ciLow) + ".";
        String band;
        if (value <= DEVALUED_THRESHOLD) {
            band = "DEVALUED UNDER ATTENTION: on hyped names the signal works"
                    + " worse to inverse - check its contrarian use there instead"
                    + " of following it blindly.";
        } else if (value >= AMPLIFIED_THRESHOLD) {
            band = "The signal amplifies under attention - it hits better on"
                    + " hyped names than on unnoticed ones.";
        } else {
            band = "No robust difference between high and low attention - the"
                    + " signal behaves crowding-neutral.";
        }
        band += buckets;
        if (overlaps(ciHigh, ciLow)) {
            band += " The two intervals overlap clearly - the difference is not"
                    + " statistically secured.";
        }
        if (nHigh < THIN_PER_BUCKET || nLow < THIN_PER_BUCKET) {
            band += " Caution: small buckets (n=" + nHigh + " and n=" + nLow
                    + "), the rates are accordingly shaky.";
        }
        return band;
    }

    private static String bucket(double rate, int n, double[] ci) {
        return MathKit.fmt(rate * 100, 0) + " % (n=" + n + ", 90% CI "
                + MathKit.fmt(ci[0], 2) + "-" + MathKit.fmt(ci[1], 2) + ")";
    }

    private static boolean overlaps(double[] a, double[] b) {
        return Math.max(a[0], b[0]) <= Math.min(a[1], b[1]);
    }

    private static String fmtSigned(double v) {
        return (v >= 0 ? "+" : "") + MathKit.fmt(v, 1);
    }
}
