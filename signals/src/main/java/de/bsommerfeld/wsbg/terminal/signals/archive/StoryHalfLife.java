package de.bsommerfeld.wsbg.terminal.signals.archive;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Lifetime percentile of a running story via Weibull survival analysis.
 *
 * <p><b>Method:</b> A Weibull distribution (Weibull 1951; the standard model
 * of survival analysis, cf. Lawless, "Statistical Models and Methods for
 * Lifetime Data") is fitted to the historical story lifetimes by maximum
 * likelihood. The shape parameter k is solved from the MLE equation by Newton
 * iteration (Sum x^k ln x / Sum x^k - 1/k - mean(ln x) = 0); the scale
 * parameter lambda then follows in closed form as (Sum x^k / n)^(1/k). The
 * signal value is F(t) = 1 - exp(-(t/lambda)^k), i.e. the percentile the
 * current story duration occupies in the lifetime distribution typical for
 * this story type.
 *
 * <p><b>Terminal inputs:</b> the historical lifetimes come from the story
 * clusters (time from first to last assigned line, reconstructed from the
 * headline archive JSONL); the current age comes from the living cluster on
 * the news wire.
 */
public final class StoryHalfLife {

    /** Below this number of historical lifetimes no fit is possible. */
    private static final int MIN_LIFETIMES = 8;
    /** Below this number the interpretation carries a caution note. */
    private static final int COMFORTABLE_LIFETIMES = 20;

    private StoryHalfLife() {
    }

    /**
     * Measures how far the current story has used up its typical lifetime.
     *
     * @param historicalLifetimes completed story lifetimes of this story type
     * @param currentAge          age of the running story
     * @return reading, or empty with fewer than {@value #MIN_LIFETIMES} positive lifetimes
     */
    public static Optional<SignalReading> measure(List<Duration> historicalLifetimes, Duration currentAge) {
        if (historicalLifetimes == null || currentAge == null || currentAge.isNegative()) {
            return Optional.empty();
        }
        double[] hours = historicalLifetimes.stream()
                .filter(d -> d != null && !d.isNegative() && !d.isZero())
                .mapToDouble(d -> d.toMillis() / 3_600_000.0)
                .filter(h -> h > 0)
                .toArray();
        if (hours.length < MIN_LIFETIMES) {
            return Optional.empty();
        }

        double k = fitShape(hours);
        double lambda = fitScale(hours, k);
        double t = currentAge.toMillis() / 3_600_000.0;
        double value = 1 - Math.exp(-Math.pow(t / lambda, k));
        value = Math.max(0, Math.min(1, value));

        String lambdaText = lambda >= 72
                ? MathKit.fmt(lambda / 24.0, 1) + " days"
                : MathKit.fmt(lambda, 1) + " hours";
        String fitText = "Weibull fit: shape k=" + MathKit.fmt(k, 2)
                + ", characteristic lifetime lambda=" + lambdaText + ".";

        String interpretation;
        if (value > 0.9) {
            interpretation = "STRUCTURAL: the story clearly outlives its expected lifetime - "
                    + "this is no longer an episode, the situation itself has changed "
                    + "(chronicle-relevant). " + fitText;
        } else if (value >= 0.5) {
            interpretation = "Mature: the story has most of its typical lifetime behind it - "
                    + "plan for it to fade out. " + fitText;
        } else {
            interpretation = "Episodically normal: the story sits within the usual lifetime window "
                    + "of its type - no particular conclusion. " + fitText;
        }
        if (hours.length < COMFORTABLE_LIFETIMES) {
            interpretation += " Caution: only n=" + hours.length
                    + " historical lifetimes back this fit - the percentile is accordingly uncertain.";
        }

        return Optional.of(new SignalReading(
                "story-half-life",
                "Story lifetime (Weibull survival)",
                value,
                MathKit.fmt(value * 100, 0) + " % lifetime percentile (scale 0-1)",
                "Measures how far the current story has already used up the lifetime "
                        + "typical for its story type.",
                interpretation));
    }

    // ---- Weibull-MLE ----

    /**
     * Newton iteration on the shape MLE equation
     * g(k) = Sum x^k ln x / Sum x^k - 1/k - mean(ln x) = 0.
     */
    private static double fitShape(double[] xs) {
        int n = xs.length;
        double[] ln = new double[n];
        double meanLn = 0;
        for (int i = 0; i < n; i++) {
            ln[i] = Math.log(xs[i]);
            meanLn += ln[i];
        }
        meanLn /= n;

        double k = 1.0;
        for (int iter = 0; iter < 200; iter++) {
            double sPow = 0, sPowLn = 0, sPowLn2 = 0;
            for (int i = 0; i < n; i++) {
                double p = Math.pow(xs[i], k);
                sPow += p;
                sPowLn += p * ln[i];
                sPowLn2 += p * ln[i] * ln[i];
            }
            double g = sPowLn / sPow - 1 / k - meanLn;
            double gPrime = (sPowLn2 * sPow - sPowLn * sPowLn) / (sPow * sPow) + 1 / (k * k);
            double next = k - g / gPrime;
            if (!Double.isFinite(next) || next <= 0) {
                next = k / 2;
            }
            next = Math.max(1e-3, Math.min(100, next));
            if (Math.abs(next - k) < 1e-10) {
                k = next;
                break;
            }
            k = next;
        }
        return k;
    }

    /** Closed-form solution for lambda given k: (Sum x^k / n)^(1/k). */
    private static double fitScale(double[] xs, double k) {
        double s = 0;
        for (double x : xs) {
            s += Math.pow(x, k);
        }
        return Math.pow(s / xs.length, 1 / k);
    }
}
