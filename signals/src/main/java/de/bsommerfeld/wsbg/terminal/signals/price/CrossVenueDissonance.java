package de.bsommerfeld.wsbg.terminal.signals.price;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Arrays;
import java.util.Optional;

/**
 * Venue-Dissonanz: dieselbe Aktie an zwei Handelsplaetzen im Preisvergleich.
 *
 * <p><b>Methode:</b> Aus den Mittelkursen beider Plaetze wird die
 * Log-Preisdifferenz d = ln(A) - ln(B) gebildet. Der letzte Wert wird als
 * z-Score gegen die vorherigen Werte der Differenzreihe gestellt
 * ({@link MathKit#zScore}); dazu kommt eine Persistenz-Quote: der Anteil der
 * letzten 5 Werte, deren Abstand vom historischen Mittel mehr als eine
 * Standardabweichung betraegt. Theorieanker ist das Law of One Price:
 * identische Papiere duerfen sich nur im Rahmen von Gebuehren- und
 * Latenzrauschen unterscheiden - eine anhaltende Differenz zeigt, von welcher
 * Seite der Druck kommt (Retail-Platz vs institutionell gepraegter Fluss).
 *
 * <p><b>Inputs im Terminal:</b> die Mittelkurse (Bid/Ask-Mid) desselben
 * Papiers von zwei Handelsplaetzen, im Terminal typischerweise die L&amp;S-
 * und die Tradegate-Quote-Reihe; die Venue-Namen kommen als Runtime-Parameter
 * herein.
 */
public final class CrossVenueDissonance {

    /** Unter dieser Reihenlaenge ist kein Differenz-z-Score belastbar. */
    private static final int MIN_SERIES = 40;
    /** Unter dieser Reihenlaenge traegt die Deutung einen Vorsichts-Zusatz. */
    private static final int COMFORTABLE_SERIES = 80;
    /** Ab diesem absoluten z-Score gilt die Differenz als auffaellig. */
    private static final double Z_DISSONANT = 2.0;
    /** Ab dieser Persistenz-Quote gilt die Differenz als anhaltend. */
    private static final double PERSISTENCE_THRESHOLD = 0.6;
    /** Fenster fuer die Persistenz-Quote. */
    private static final int PERSISTENCE_WINDOW = 5;

    private CrossVenueDissonance() {
    }

    /**
     * Misst die aktuelle Preisdifferenz desselben Papiers zwischen zwei Plaetzen.
     *
     * @param midsVenueA Mittelkurse an Platz A, alle &gt; 0
     * @param midsVenueB Mittelkurse an Platz B, gleiche Laenge, alle &gt; 0
     * @param venueAName Anzeigename von Platz A
     * @param venueBName Anzeigename von Platz B
     * @return Befund, oder empty bei ungleich langen oder zu kurzen Reihen
     *         (unter {@value #MIN_SERIES}), nicht-positiven Kursen oder fehlenden Namen
     */
    public static Optional<SignalReading> measure(double[] midsVenueA, double[] midsVenueB,
            String venueAName, String venueBName) {
        if (midsVenueA == null || midsVenueB == null
                || midsVenueA.length != midsVenueB.length
                || midsVenueA.length < MIN_SERIES
                || venueAName == null || venueAName.isBlank()
                || venueBName == null || venueBName.isBlank()) {
            return Optional.empty();
        }
        int n = midsVenueA.length;
        double[] difference = new double[n];
        for (int i = 0; i < n; i++) {
            if (!(midsVenueA[i] > 0) || !(midsVenueB[i] > 0)
                    || !Double.isFinite(midsVenueA[i]) || !Double.isFinite(midsVenueB[i])) {
                return Optional.empty();
            }
            difference[i] = Math.log(midsVenueA[i]) - Math.log(midsVenueB[i]);
        }

        double[] history = Arrays.copyOfRange(difference, 0, n - 1);
        double z = MathKit.zScore(difference[n - 1], history);

        double historyMean = MathKit.mean(history);
        double historyStd = MathKit.std(history);
        double persistence = 0;
        if (historyStd > 0) {
            int outliers = 0;
            for (int i = n - PERSISTENCE_WINDOW; i < n; i++) {
                if (Math.abs(difference[i] - historyMean) > historyStd) {
                    outliers++;
                }
            }
            persistence = (double) outliers / PERSISTENCE_WINDOW;
        }

        String interpretation;
        if (Math.abs(z) >= Z_DISSONANT && persistence >= PERSISTENCE_THRESHOLD) {
            String payingVenue = z > 0 ? venueAName : venueBName;
            interpretation = "VENUE-DISSONANZ: der Druck kommt einseitig von " + payingVenue
                    + " - dieser Platz zahlt anhaltend mehr für dasselbe Papier. "
                    + "Fluss-Herkunft in die Lagebeurteilung aufnehmen.";
        } else if (Math.abs(z) >= Z_DISSONANT) {
            interpretation = "Kurzlebiger Ausreißer zwischen " + venueAName + " und " + venueBName
                    + " ohne Persistenz - wahrscheinlich eine Stale Quote, kein belastbares Signal.";
        } else {
            interpretation = "Plätze im Einklang: " + venueAName + " und " + venueBName
                    + " preisen dasselbe Papier im Rahmen des Gebührenrauschens gleich.";
        }
        if (n < COMFORTABLE_SERIES) {
            interpretation += " Vorsicht: nur " + n
                    + " Kurspaare als Vergleichsbasis - z-Score und Persistenz sind entsprechend unsicher.";
        }

        return Optional.of(new SignalReading(
                "cross-venue-dissonance",
                "Venue-Dissonanz (Preis-Differenzsignal)",
                z,
                MathKit.fmt(z, 2) + " (z-Score der Log-Preisdifferenz; Persistenz "
                        + MathKit.fmt(persistence, 2) + ", positiv = " + venueAName + " zahlt mehr)",
                "Misst, wie weit die aktuelle Log-Preisdifferenz desselben Papiers zwischen zwei "
                        + "Handelsplätzen aus ihrem eigenen historischen Band gelaufen ist und wie "
                        + "anhaltend die Abweichung ist.",
                interpretation));
    }
}
