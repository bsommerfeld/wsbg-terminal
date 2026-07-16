package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Map;
import java.util.Optional;

/**
 * Schätzungs-Fächer: relative Änderung der Streuung der Mehrjahres-Schätzungen
 * zwischen zwei Snapshots.
 *
 * <p>Numerik: pro Snapshot wird über die Jahres-Schätzungen der
 * Variationskoeffizient CV = std / |mean| gebildet (Stichproben-Std, n-1;
 * der CV als skalenfreies Streuungsmaß geht auf Pearson 1896 zurück), der
 * Signalwert ist die relative Änderung (cvJetzt - cvVorher) / cvVorher.
 * War der Fächer vorher komplett geschlossen (cvVorher = 0) und öffnet sich,
 * wird das als +1 gewertet. Gemessen wird damit, ob der Fächer der
 * Mehrjahres-Schätzungen sich öffnet (steiler, unsicherer Wachstumspfad)
 * oder kollabiert - die Änderung markiert den Moment, in dem Analysten die
 * Equity-Story umschreiben, bevor es eine Headline gibt.
 *
 * <p>Input im Terminal: zwei zeitversetzte Consorsbank-KeyFigures-Snapshots
 * derselben Aktie mit Konsens-Schätzwerten je Jahr (z.B. EPS bis 2029).
 */
public final class EstimateFanDivergence {

    private static final String ID = "estimate-fan-divergence";
    private static final String TITLE = "Schätzungs-Fächer (Mehrjahres-Divergenz)";
    private static final String DEFINITION =
            "Misst, ob der Fächer der Mehrjahres-Schätzungen sich seit dem letzten"
                    + " Snapshot öffnet (Pfad wird unsicherer) oder kollabiert (Pfad"
                    + " wird planbar) - als relative Änderung des"
                    + " Variationskoeffizienten über die Schätzjahre.";

    private static final int MIN_YEARS = 3;
    private static final int THIN_YEARS = 4;
    private static final double OPEN_THRESHOLD = 0.2;
    private static final double COLLAPSE_THRESHOLD = -0.2;

    private EstimateFanDivergence() {
    }

    /**
     * Berechnet die relative Änderung des Schätzungs-Fächers. Beide Snapshots
     * brauchen mindestens {@value #MIN_YEARS} Schätzjahre und einen Mittelwert
     * != 0, sonst {@link Optional#empty()}.
     *
     * @param currentByYear  Schätzwerte je Jahr aus dem aktuellen Snapshot
     * @param previousByYear Schätzwerte je Jahr aus dem vorherigen Snapshot
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

        String formatted = fmtSigned(value) + " relative Fächer-Änderung (CV jetzt "
                + MathKit.fmt(cvNow, 3) + ", vorher " + MathKit.fmt(cvPrev, 3) + ")";
        return Optional.of(new SignalReading(
                ID, TITLE, value, formatted, DEFINITION,
                interpret(value, cvNow, cvPrev, minYears)));
    }

    /** CV = std/|mean| über die Jahreswerte, NaN bei zu dünner Lage oder Mittelwert 0. */
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
        String cvs = " (CV jetzt " + MathKit.fmt(cvNow, 3) + " gegen vorher "
                + MathKit.fmt(cvPrev, 3) + ")";
        String band;
        if (value >= OPEN_THRESHOLD) {
            band = "FÄCHER ÖFFNET SICH: die Unsicherheit über den Mehrjahres-Pfad"
                    + " steigt" + cvs + " - das Papier wird zur Story-Aktie, das"
                    + " Bewertungs-Multiple wird verwundbarer.";
        } else if (value <= COLLAPSE_THRESHOLD) {
            band = "FÄCHER KOLLABIERT: die Schätzungen konvergieren zur Substanz"
                    + cvs + " - die Story wird planbar, der Pfad verliert"
                    + " Überraschungspotenzial in beide Richtungen.";
        } else {
            band = "Der Fächer ist stabil" + cvs + " - die Unsicherheit über den"
                    + " Mehrjahres-Pfad hat sich nicht nennenswert verändert.";
        }
        if (minYears < THIN_YEARS) {
            band += " Vorsicht: nur " + minYears + " Schätzjahre im kleineren"
                    + " Snapshot, der Variationskoeffizient ist entsprechend wackelig.";
        }
        return band;
    }

    private static String fmtSigned(double v) {
        return (v >= 0 ? "+" : "") + MathKit.fmt(v, 2);
    }
}
