package de.bsommerfeld.wsbg.terminal.signals.archive;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Lebensdauer-Perzentil einer laufenden Story via Weibull-Survival-Analyse.
 *
 * <p><b>Methode:</b> Auf die historischen Story-Lebensdauern wird eine
 * Weibull-Verteilung (Weibull 1951; Standardmodell der Survival-Analyse,
 * vgl. Lawless, "Statistical Models and Methods for Lifetime Data") per
 * Maximum-Likelihood gefittet. Der Shape-Parameter k wird ueber die
 * MLE-Gleichung per Newton-Iteration geloest
 * (Sum x^k ln x / Sum x^k - 1/k - mean(ln x) = 0), der Scale-Parameter
 * lambda folgt danach geschlossen als (Sum x^k / n)^(1/k). Der Signalwert
 * ist F(t) = 1 - exp(-(t/lambda)^k), also das Perzentil, das die aktuelle
 * Story-Dauer in der fuer diesen Story-Typ ueblichen Lebensdauer-Verteilung
 * einnimmt.
 *
 * <p><b>Inputs im Terminal:</b> die historischen Lebensdauern kommen aus den
 * Story-Clustern (Zeit von erster bis letzter zugeordneter Zeile, rekonstruiert
 * aus dem Headline-Archiv JSONL), die aktuelle Dauer aus dem lebenden Cluster
 * auf dem News-Wire.
 */
public final class StoryHalfLife {

    /** Unter dieser Zahl historischer Lebensdauern ist kein Fit moeglich. */
    private static final int MIN_LIFETIMES = 8;
    /** Unter dieser Zahl traegt die Deutung einen Vorsichts-Zusatz. */
    private static final int COMFORTABLE_LIFETIMES = 20;

    private StoryHalfLife() {
    }

    /**
     * Misst, wie weit die aktuelle Story ihre uebliche Lebensdauer ausgeschoepft hat.
     *
     * @param historicalLifetimes abgeschlossene Story-Lebensdauern dieses Story-Typs
     * @param currentAge          Alter der laufenden Story
     * @return Befund, oder empty bei weniger als {@value #MIN_LIFETIMES} positiven Lebensdauern
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
                ? MathKit.fmt(lambda / 24.0, 1) + " Tagen"
                : MathKit.fmt(lambda, 1) + " Stunden";
        String fitText = "Weibull-Fit: Form k=" + MathKit.fmt(k, 2)
                + ", charakteristische Lebensdauer lambda=" + lambdaText + ".";

        String interpretation;
        if (value > 0.9) {
            interpretation = "STRUKTURELL: die Story überlebt ihre erwartete Lebensdauer deutlich - "
                    + "das ist keine Episode mehr, die Lage selbst hat sich geändert "
                    + "(Chronik-relevant). " + fitText;
        } else if (value >= 0.5) {
            interpretation = "Reif: die Story hat den Grossteil ihrer üblichen Lebensdauer hinter sich - "
                    + "Auslaufen einplanen. " + fitText;
        } else {
            interpretation = "Episodisch normal: die Story liegt im üblichen Lebensdauer-Fenster ihres Typs - "
                    + "kein besonderer Schluss. " + fitText;
        }
        if (hours.length < COMFORTABLE_LIFETIMES) {
            interpretation += " Vorsicht: der Fit stützt sich auf nur " + hours.length
                    + " historische Lebensdauern - das Perzentil ist entsprechend unsicher.";
        }

        return Optional.of(new SignalReading(
                "story-half-life",
                "Story-Lebensdauer (Weibull-Survival)",
                value,
                MathKit.fmt(value * 100, 0) + " % Lebensdauer-Perzentil (Skala 0-1)",
                "Misst, wie weit die aktuelle Story ihre für diesen Story-Typ übliche "
                        + "Lebensdauer schon ausgeschöpft hat.",
                interpretation));
    }

    // ---- Weibull-MLE ----

    /**
     * Newton-Iteration auf der Shape-MLE-Gleichung
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

    /** Geschlossene Loesung fuer lambda gegeben k: (Sum x^k / n)^(1/k). */
    private static double fitScale(double[] xs, double k) {
        double s = 0;
        for (double x : xs) {
            s += Math.pow(x, k);
        }
        return Math.pow(s / xs.length, 1 / k);
    }
}
