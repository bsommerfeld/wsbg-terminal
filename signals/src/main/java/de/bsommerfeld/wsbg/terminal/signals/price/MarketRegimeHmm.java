package de.bsommerfeld.wsbg.terminal.signals.price;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Arrays;
import java.util.Optional;

/**
 * Marktregime-Erkennung ueber ein 3-Zustands-Hidden-Markov-Modell.
 *
 * <p><b>Methode:</b> Auf der Beobachtungsreihe (z.B. Tagesrenditen in Prozent)
 * wird ein HMM mit drei Zustaenden und univariaten Gauss-Emissionen per
 * Baum-Welch/EM geschaetzt (Rabiner 1989; Regime-Idee nach Hamilton 1989).
 * Die Initialisierung ist DETERMINISTISCH: Emissions-Mittelwerte auf den
 * empirischen Quantilen P20/P50/P80, Varianzen auf der Gesamtvarianz,
 * Uebergangsmatrix uniform mit Diagonal-Praeferenz (0.8/0.1/0.1). Das
 * Forward-Backward laeuft skaliert (Normalisierung pro Zeitschritt gegen
 * Unterlauf), maximal 50 Iterationen, Konvergenz bei 1e-6 auf der
 * Log-Likelihood. Die Zustaende werden nach Emissions-Standardabweichung
 * aufsteigend RUHE/NORMAL/STRESS gelabelt; das aktuelle Regime ist das argmax
 * der gefilterten Forward-Posterior am letzten Beobachtungspunkt.
 *
 * <p><b>Inputs im Terminal:</b> die Beobachtungen kommen aus den Kursreihen
 * des Terminals (Tagesrenditen aus L&amp;S/Tradegate-Kursen bzw. einer
 * Index-Reihe als Marktproxy).
 */
public final class MarketRegimeHmm {

    /** Unter dieser Laenge ist kein HMM belastbar. */
    private static final int MIN_OBSERVATIONS = 60;
    /** Unter dieser Laenge traegt die Deutung einen Vorsichts-Zusatz. */
    private static final int COMFORTABLE_OBSERVATIONS = 120;
    private static final int STATES = 3;
    private static final int MAX_ITERATIONS = 50;
    private static final double CONVERGENCE = 1e-6;
    private static final double VARIANCE_FLOOR = 1e-8;
    private static final double DENSITY_FLOOR = 1e-300;
    private static final String[] LABELS = {"RUHE", "NORMAL", "STRESS"};

    private MarketRegimeHmm() {
    }

    /**
     * Erkennt das aktuelle Marktregime aus der Beobachtungsreihe selbst.
     *
     * @param observations Beobachtungsreihe, z.B. Tagesrenditen in Prozent
     * @return Befund, oder empty bei weniger als {@value #MIN_OBSERVATIONS}
     *         Beobachtungen oder degenerierter (konstanter) Reihe
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

        // ---- deterministische Initialisierung ----
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

        // ---- Baum-Welch mit skaliertem Forward-Backward ----
        double[][] alpha = new double[n][STATES];
        double[][] beta = new double[n][STATES];
        double[][] b = new double[n][STATES];
        double[] scale = new double[n];
        double previousLogLikelihood = Double.NEGATIVE_INFINITY;

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            double logLikelihood = eStep(observations, pi, a, mu, va, b, alpha, beta, scale);

            // gamma und xi-Summen fuer den M-Step
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

            // ---- M-Step ----
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

        // finaler Forward-Pass mit den konvergierten Parametern: gefilterte Posterior
        eStep(observations, pi, a, mu, va, b, alpha, beta, scale);
        double[] posterior = alpha[n - 1];

        // ---- Zustaende nach Emissions-Standardabweichung aufsteigend labeln ----
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

        String interpretation = "Alle anderen Signale in diesem Regime lesen - dieselbe Euphorie "
                + "oder derselbe Spread bedeutet im Stress-Regime etwas anderes als im Ruhe-Regime. ";
        interpretation += switch (regimeIndex) {
            case 0 -> "Aktuell RUHE: Ausschläge ernst nehmen, sie sind hier selten und tragen "
                    + "entsprechend Gewicht.";
            case 1 -> "Aktuell NORMAL: Standardbasisraten gelten, keine Sonderbehandlung.";
            default -> "Aktuell STRESS: Korrelationen springen auf 1, Einzeltitel-Signale abwerten "
                    + "und der Liquidität misstrauen.";
        };
        if (n < COMFORTABLE_OBSERVATIONS) {
            interpretation += " Vorsicht: nur " + n
                    + " Beobachtungen für die Regime-Schätzung - die Zustandszuordnung ist entsprechend unsicher.";
        }

        return Optional.of(new SignalReading(
                "market-regime-hmm",
                "Marktregime (Hidden-Markov-Modell)",
                regimeIndex,
                label + " (p=" + MathKit.fmt(probability, 2) + ")",
                "Erkennt das aktuelle Marktregime aus den Daten selbst - ein 3-Zustands-HMM mit "
                        + "Gauß-Emissionen, gelabelt nach Volatilität - nicht als Prognose, sondern "
                        + "als Konditionierung aller übrigen Signale.",
                interpretation));
    }

    /**
     * Skalierter Forward-Backward-Pass; fuellt b, alpha, beta, scale und gibt
     * die Log-Likelihood zurueck.
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

    /** Normiert das Array in-place auf Summe 1 und gibt die alte Summe zurueck. */
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

    /** Empirisches Quantil mit linearer Interpolation (deterministisch). */
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
