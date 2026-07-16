package de.bsommerfeld.wsbg.terminal.signals.swarm;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hawkes-Endogenität: schätzt für einen Strom von Kommentar-Zeitstempeln den
 * Branching Ratio n = α/β eines selbsterregenden Punktprozesses
 * λ(t) = μ + α · Σ exp(-β(t - tᵢ)) (Hawkes 1971).
 *
 * <p>Numerik: Standard-EM für den Exponentialkern (Lewis &amp; Mohler 2011,
 * "A Nonparametric EM algorithm for multiscale Hawkes processes" - hier die
 * parametrische Variante). Im E-Schritt wird jedem Event die Wahrscheinlichkeit
 * zugerechnet, Hintergrund (μ) oder Nachkomme eines früheren Events zu sein;
 * der M-Schritt aktualisiert μ und α in geschlossener Form. β wird nicht
 * mitgeschätzt, sondern über ein festes Gitter von Halbwertszeiten
 * (1, 2, 5, 10, 30, 60 Minuten) gelegt; gewählt wird das β mit der besten
 * Log-Likelihood. Die Rekursion A(i) = e^(-βΔ)(A(i-1)+1) hält den EM bei O(n)
 * pro Iteration (Ogata 1981).
 *
 * <p>Input im Terminal: die Zeitstempel der Reddit-Kommentare eines Threads
 * bzw. Clusters (createdUtc aus dem Scraper-Snapshot). Der Wert beantwortet:
 * welcher Anteil der Aktivität erzeugt sich selbst (Kommentare, die Kommentare
 * auslösen), statt von außen (News) getrieben zu sein?
 */
public final class HawkesEndogeneity {

    private static final String ID = "hawkes-endogeneity";
    private static final String TITLE = "Hawkes-Endogenität (Kaskaden-Selbstzündung)";
    private static final String DEFINITION =
            "Misst, welcher Anteil der Kommentar-Aktivität sich selbst erzeugt"
                    + " (Kommentare, die Kommentare auslösen) statt von außen zu kommen.";

    private static final int MIN_EVENTS = 20;
    private static final int THIN_EVENTS = 40;
    /** Halbwertszeiten des β-Gitters in Sekunden. */
    private static final double[] HALF_LIVES_SECONDS = {60, 120, 300, 600, 1800, 3600};
    private static final int EM_MAX_ITERATIONS = 200;
    private static final double EM_TOLERANCE = 1e-9;

    private HawkesEndogeneity() {
    }

    /**
     * Schätzt den Branching Ratio über die gegebenen Event-Zeitpunkte.
     * Mindestens {@value #MIN_EVENTS} Events, sonst {@link Optional#empty()}.
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
        // Beobachtungsfenster; gegen entartete Zeitachsen (alles derselbe Moment) abgesichert.
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
                MathKit.fmt(value, 2) + " (Branching Ratio, Skala 0-1)",
                DEFINITION, interpretation));
    }

    /**
     * EM für festes β. Rückgabe {branching = α/β, logLikelihood}.
     */
    private static double[] fitEm(double[] t, double horizon, double beta) {
        int n = t.length;
        double mu = n / (2.0 * horizon);
        double alpha = 0.5 * beta;

        // Kompensator-Hilfsgröße: Σ_j (1 - e^(-β(T - t_j))) hängt nicht von μ/α ab.
        double compensatorBase = 0;
        for (double tj : t) {
            compensatorBase += 1 - Math.exp(-beta * (horizon - tj));
        }
        if (compensatorBase <= 0) {
            compensatorBase = 1e-12;
        }

        double logLikelihood = Double.NEGATIVE_INFINITY;
        for (int iter = 0; iter < EM_MAX_ITERATIONS; iter++) {
            double a = 0; // A(i) = Σ_{j<i} e^(-β(t_i - t_j)), rekursiv
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

            // M-Schritt
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
            band = "Der Prozess ist exogen: die Aktivität ist Reaktion auf ein äußeres"
                    + " Ereignis, der Thread verarbeitet News.";
        } else if (value <= 0.7) {
            band = "Der Prozess ist gemischt: ein Teil der Aktivität verarbeitet ein"
                    + " äußeres Ereignis, ein Teil zündet sich bereits selbst.";
        } else {
            band = "Der Prozess ist endogen: eine selbstzündende Kaskade"
                    + " (Meme-/Squeeze-Dynamik) - dieselbe Kommentarzahl bedeutet hier"
                    + " Schwarmverhalten, nicht Informationsverarbeitung.";
        }
        if (eventCount < THIN_EVENTS) {
            band += " Vorsicht: nur " + eventCount + " Events, die Schätzung ist"
                    + " entsprechend unsicher.";
        }
        return band;
    }
}
