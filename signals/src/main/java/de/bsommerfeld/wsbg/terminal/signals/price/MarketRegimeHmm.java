package de.bsommerfeld.wsbg.terminal.signals.price;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Arrays;
import java.util.Optional;

/**
 * Market regime detection via a 3-state hidden Markov model.
 *
 * <p><b>Method:</b> On the observation series (e.g. daily returns in percent)
 * an HMM with three states and univariate Gaussian emissions is fitted via
 * Baum-Welch/EM (Rabiner 1989; regime idea after Hamilton 1989).
 * Initialization is DETERMINISTIC: emission means on the empirical quantiles
 * P20/P50/P80, variances on the total variance, transition matrix uniform
 * with diagonal preference (0.8/0.1/0.1). Forward-backward runs scaled
 * (per-timestep normalization against underflow), at most 50 iterations,
 * convergence at 1e-6 on the log-likelihood. States are labeled
 * CALM/NORMAL/STRESS by ascending emission standard deviation; the current
 * regime is the argmax of the filtered forward posterior at the last
 * observation point.
 *
 * <p><b>Terminal inputs:</b> the observations come from the terminal's price
 * series (daily returns from venue quotes or an index series as market
 * proxy).
 */
public final class MarketRegimeHmm {

    /** Below this length no HMM is reliable. */
    private static final int MIN_OBSERVATIONS = 60;
    /** Below this length the interpretation carries a caution suffix. */
    private static final int COMFORTABLE_OBSERVATIONS = 120;
    private static final int STATES = 3;
    private static final int MAX_ITERATIONS = 50;
    private static final double CONVERGENCE = 1e-6;
    private static final double VARIANCE_FLOOR = 1e-8;
    private static final double DENSITY_FLOOR = 1e-300;
    private static final String[] LABELS = {"CALM", "NORMAL", "STRESS"};

    private MarketRegimeHmm() {
    }

    /**
     * Detects the current market regime from the observation series itself.
     *
     * @param observations observation series, e.g. daily returns in percent
     * @return reading, or empty on fewer than {@value #MIN_OBSERVATIONS}
     *         observations or a degenerate (constant) series
     */
    public static Optional<SignalReading> measure(double[] observations) {
        if (observations == null || observations.length < MIN_OBSERVATIONS) {
            return Optional.empty();
        }
        for (double x : observations) {
            if (!Double.isFinite(x)) {
                return Optional.empty();
            }
        }
        if (MathKit.variance(observations) == 0) {
            return Optional.empty();
        }

        int n = observations.length;

        // ---- deterministic initialization ----
        double[] mu = {quantile(observations, 0.20),
                quantile(observations, 0.50),
                quantile(observations, 0.80)};
        double totalVariance = Math.max(MathKit.variance(observations), VARIANCE_FLOOR);
        double[] va = {totalVariance, totalVariance, totalVariance};
        double[][] a = new double[STATES][STATES];
        for (int i = 0; i < STATES; i++) {
            for (int j = 0; j < STATES; j++) {
                a[i][j] = (i == j) ? 0.8 : 0.1;
            }
        }
        double[] pi = {1.0 / STATES, 1.0 / STATES, 1.0 / STATES};

        // ---- Baum-Welch with scaled forward-backward ----
        double[][] alpha = new double[n][STATES];
        double[][] beta = new double[n][STATES];
        double[][] b = new double[n][STATES];
        double[] scale = new double[n];
        double previousLogLikelihood = Double.NEGATIVE_INFINITY;

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            double logLikelihood = eStep(observations, pi, a, mu, va, b, alpha, beta, scale);

            // gamma and xi sums for the M-step
            double[][] gamma = new double[n][STATES];
            for (int t = 0; t < n; t++) {
                double sum = 0;
                for (int k = 0; k < STATES; k++) {
                    gamma[t][k] = alpha[t][k] * beta[t][k];
                    sum += gamma[t][k];
                }
                if (sum > 0) {
                    for (int k = 0; k < STATES; k++) {
                        gamma[t][k] /= sum;
                    }
                }
            }
            double[][] xiSum = new double[STATES][STATES];
            for (int t = 0; t < n - 1; t++) {
                for (int i = 0; i < STATES; i++) {
                    for (int j = 0; j < STATES; j++) {
                        xiSum[i][j] += alpha[t][i] * a[i][j] * b[t + 1][j] * beta[t + 1][j]
                                / scale[t + 1];
                    }
                }
            }

            // ---- M-step ----
            System.arraycopy(gamma[0], 0, pi, 0, STATES);
            for (int i = 0; i < STATES; i++) {
                double rowSum = 0;
                for (int j = 0; j < STATES; j++) {
                    rowSum += xiSum[i][j];
                }
                if (rowSum > 0) {
                    for (int j = 0; j < STATES; j++) {
                        a[i][j] = xiSum[i][j] / rowSum;
                    }
                }
            }
            for (int k = 0; k < STATES; k++) {
                double weight = 0;
                double weightedSum = 0;
                for (int t = 0; t < n; t++) {
                    weight += gamma[t][k];
                    weightedSum += gamma[t][k] * observations[t];
                }
                if (weight > 0) {
                    mu[k] = weightedSum / weight;
                    double weightedSq = 0;
                    for (int t = 0; t < n; t++) {
                        double d = observations[t] - mu[k];
                        weightedSq += gamma[t][k] * d * d;
                    }
                    va[k] = Math.max(weightedSq / weight, VARIANCE_FLOOR);
                }
            }

            if (Math.abs(logLikelihood - previousLogLikelihood) < CONVERGENCE) {
                break;
            }
            previousLogLikelihood = logLikelihood;
        }

        // final forward pass with the converged parameters: filtered posterior
        eStep(observations, pi, a, mu, va, b, alpha, beta, scale);
        double[] posterior = alpha[n - 1];

        // ---- label states by ascending emission standard deviation ----
        Integer[] order = {0, 1, 2};
        Arrays.sort(order, (x, y) -> Double.compare(va[x], va[y]));
        int currentState = 0;
        for (int k = 1; k < STATES; k++) {
            if (posterior[k] > posterior[currentState]) {
                currentState = k;
            }
        }
        int regimeIndex = 0;
        for (int r = 0; r < STATES; r++) {
            if (order[r] == currentState) {
                regimeIndex = r;
            }
        }
        String label = LABELS[regimeIndex];
        double probability = posterior[currentState];

        String interpretation = "Read every other signal conditioned on this regime - the same "
                + "euphoria or the same spread means something different under STRESS than under CALM. ";
        interpretation += switch (regimeIndex) {
            case 0 -> "Currently CALM: take swings seriously, they are rare here and carry "
                    + "corresponding weight.";
            case 1 -> "Currently NORMAL: standard base rates apply, no special treatment.";
            default -> "Currently STRESS: correlations jump to 1, discount single-name signals "
                    + "and distrust liquidity.";
        };
        if (n < COMFORTABLE_OBSERVATIONS) {
            interpretation += " Caution: only " + n
                    + " observations for the regime fit - the state assignment is accordingly uncertain.";
        }

        return Optional.of(new SignalReading(
                "market-regime-hmm",
                "Market regime (hidden Markov model)",
                regimeIndex,
                label + " (p=" + MathKit.fmt(probability, 2) + ")",
                "Detects the current market regime from the data itself - a 3-state HMM with "
                        + "Gaussian emissions, labeled by volatility - not as a forecast but as "
                        + "the conditioning of every other signal.",
                interpretation));
    }

    /**
     * Scaled forward-backward pass; fills b, alpha, beta, scale and returns
     * the log-likelihood.
     */
    private static double eStep(double[] obs, double[] pi, double[][] a, double[] mu, double[] va,
            double[][] b, double[][] alpha, double[][] beta, double[] scale) {
        int n = obs.length;
        for (int t = 0; t < n; t++) {
            for (int k = 0; k < STATES; k++) {
                b[t][k] = Math.max(gaussianDensity(obs[t], mu[k], va[k]), DENSITY_FLOOR);
            }
        }
        double logLikelihood = 0;
        for (int k = 0; k < STATES; k++) {
            alpha[0][k] = pi[k] * b[0][k];
        }
        scale[0] = normalize(alpha[0]);
        logLikelihood += Math.log(scale[0]);
        for (int t = 1; t < n; t++) {
            for (int k = 0; k < STATES; k++) {
                double s = 0;
                for (int j = 0; j < STATES; j++) {
                    s += alpha[t - 1][j] * a[j][k];
                }
                alpha[t][k] = s * b[t][k];
            }
            scale[t] = normalize(alpha[t]);
            logLikelihood += Math.log(scale[t]);
        }
        for (int k = 0; k < STATES; k++) {
            beta[n - 1][k] = 1;
        }
        for (int t = n - 2; t >= 0; t--) {
            for (int k = 0; k < STATES; k++) {
                double s = 0;
                for (int j = 0; j < STATES; j++) {
                    s += a[k][j] * b[t + 1][j] * beta[t + 1][j];
                }
                beta[t][k] = s / scale[t + 1];
            }
        }
        return logLikelihood;
    }

    /** Normalizes the array in place to sum 1 and returns the old sum. */
    private static double normalize(double[] xs) {
        double sum = 0;
        for (double x : xs) {
            sum += x;
        }
        if (sum <= 0 || !Double.isFinite(sum)) {
            Arrays.fill(xs, 1.0 / xs.length);
            return DENSITY_FLOOR;
        }
        for (int k = 0; k < xs.length; k++) {
            xs[k] /= sum;
        }
        return sum;
    }

    private static double gaussianDensity(double x, double mean, double variance) {
        double d = x - mean;
        return Math.exp(-d * d / (2 * variance)) / Math.sqrt(2 * Math.PI * variance);
    }

    /** Empirical quantile with linear interpolation (deterministic). */
    private static double quantile(double[] xs, double p) {
        double[] sorted = xs.clone();
        Arrays.sort(sorted);
        double position = p * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = Math.min(lower + 1, sorted.length - 1);
        double fraction = position - lower;
        return sorted[lower] + fraction * (sorted[upper] - sorted[lower]);
    }
}
