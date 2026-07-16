package de.bsommerfeld.wsbg.terminal.signals.swarm;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hawkes endogeneity: estimates, for a stream of comment timestamps, the
 * branching ratio n = α/β of a self-exciting point process
 * λ(t) = μ + α · Σ exp(-β(t - tᵢ)) (Hawkes 1971).
 *
 * <p>Numerics: standard EM for the exponential kernel (Lewis &amp; Mohler 2011,
 * "A Nonparametric EM algorithm for multiscale Hawkes processes" - here the
 * parametric variant). The E-step assigns each event the probability of being
 * background (μ) or offspring of an earlier event; the M-step updates μ and α
 * in closed form. β is not estimated but laid over a fixed grid of half-lives
 * (1, 2, 5, 10, 30, 60 minutes); the β with the best log-likelihood wins.
 * The recursion A(i) = e^(-βΔ)(A(i-1)+1) keeps the EM at O(n) per iteration
 * (Ogata 1981).
 *
 * <p>Terminal input: the timestamps of the Reddit comments of a thread or
 * cluster (createdUtc from the scraper snapshot). The value answers: what
 * share of the activity generates itself (comments triggering comments)
 * instead of being driven from outside (news)?
 */
public final class HawkesEndogeneity {

    private static final String ID = "hawkes-endogeneity";
    private static final String TITLE = "Hawkes endogeneity (cascade self-excitation)";
    private static final String DEFINITION =
            "Measures what share of the comment activity generates itself"
                    + " (comments triggering comments) instead of coming from outside.";

    private static final int MIN_EVENTS = 20;
    private static final int THIN_EVENTS = 40;
    /** Half-lives of the β grid, in seconds. */
    private static final double[] HALF_LIVES_SECONDS = {60, 120, 300, 600, 1800, 3600};
    private static final int EM_MAX_ITERATIONS = 200;
    private static final double EM_TOLERANCE = 1e-9;

    private HawkesEndogeneity() {
    }

    /**
     * Estimates the branching ratio over the given event times.
     * At least {@value #MIN_EVENTS} events, otherwise {@link Optional#empty()}.
     */
    public static Optional<SignalReading> measure(List<Instant> events) {
        if (events == null || events.size() < MIN_EVENTS) {
            return Optional.empty();
        }
        List<Instant> sorted = new ArrayList<>(events);
        sorted.sort(Instant::compareTo);
        int n = sorted.size();
        double[] t = new double[n];
        Instant first = sorted.get(0);
        for (int i = 0; i < n; i++) {
            t[i] = (sorted.get(i).toEpochMilli() - first.toEpochMilli()) / 1000.0;
        }
        // Observation window; guarded against degenerate time axes (everything the same moment).
        double horizon = Math.max(t[n - 1], 1.0);

        double bestBranching = 0;
        double bestLogLikelihood = Double.NEGATIVE_INFINITY;
        for (double halfLife : HALF_LIVES_SECONDS) {
            double beta = Math.log(2) / halfLife;
            double[] fit = fitEm(t, horizon, beta);
            if (fit[1] > bestLogLikelihood) {
                bestLogLikelihood = fit[1];
                bestBranching = fit[0];
            }
        }

        double value = Math.min(0.99, Math.max(0, bestBranching));
        String interpretation = interpret(value, n);
        return Optional.of(new SignalReading(
                ID, TITLE, value,
                MathKit.fmt(value, 2) + " (branching ratio, scale 0-1)",
                DEFINITION, interpretation));
    }

    /**
     * EM for fixed β. Returns {branching = α/β, logLikelihood}.
     */
    private static double[] fitEm(double[] t, double horizon, double beta) {
        int n = t.length;
        double mu = n / (2.0 * horizon);
        double alpha = 0.5 * beta;

        // Compensator helper: Σ_j (1 - e^(-β(T - t_j))) does not depend on μ/α.
        double compensatorBase = 0;
        for (double tj : t) {
            compensatorBase += 1 - Math.exp(-beta * (horizon - tj));
        }
        if (compensatorBase <= 0) {
            compensatorBase = 1e-12;
        }

        double logLikelihood = Double.NEGATIVE_INFINITY;
        for (int iter = 0; iter < EM_MAX_ITERATIONS; iter++) {
            double a = 0; // A(i) = Σ_{j<i} e^(-β(t_i - t_j)), recursive
            double sumBackground = 0;
            double sumOffspring = 0;
            double sumLogIntensity = 0;
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    a = Math.exp(-beta * (t[i] - t[i - 1])) * (a + 1);
                }
                double intensity = mu + alpha * a;
                if (intensity <= 0) {
                    intensity = 1e-12;
                }
                sumLogIntensity += Math.log(intensity);
                sumBackground += mu / intensity;
                sumOffspring += alpha * a / intensity;
            }
            double newLogLikelihood =
                    sumLogIntensity - mu * horizon - (alpha / beta) * compensatorBase;

            // M-step
            mu = Math.max(sumBackground / horizon, 1e-12);
            alpha = Math.max(beta * sumOffspring / compensatorBase, 0);

            if (Math.abs(newLogLikelihood - logLikelihood) < EM_TOLERANCE) {
                logLikelihood = newLogLikelihood;
                break;
            }
            logLikelihood = newLogLikelihood;
        }
        return new double[]{alpha / beta, logLikelihood};
    }

    private static String interpret(double value, int eventCount) {
        String band;
        if (value < 0.3) {
            band = "EXOGENOUS - the activity is a reaction to an outside event,"
                    + " the thread is digesting news.";
        } else if (value <= 0.7) {
            band = "MIXED - part of the activity digests an outside event,"
                    + " part is already igniting itself.";
        } else {
            band = "ENDOGENOUS - self-igniting cascade (meme/squeeze dynamics);"
                    + " the same comment count here means swarm behavior,"
                    + " not information processing.";
        }
        if (eventCount < THIN_EVENTS) {
            band += " Caution: only n=" + eventCount + " events - the estimate is"
                    + " correspondingly uncertain.";
        }
        return band;
    }
}
