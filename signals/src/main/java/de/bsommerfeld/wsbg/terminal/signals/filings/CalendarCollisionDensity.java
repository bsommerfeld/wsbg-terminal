package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Kalender-Kollisionsdichte: misst, wie ueberladen der heutige
 * Ereignis-Kalender im Vergleich zur eigenen Historie ist.
 *
 * <p><b>Methode:</b> Das heutige Ereignis-Gewicht wird als empirisches
 * Perzentil gegen die Verteilung der historischen Tagesgewichte eingeordnet
 * (Mid-Rank bei Gleichstand). Literaturanker: limited attention / Ablenkung
 * durch konkurrierende Nachrichten - an Tagen mit vielen gleichzeitigen
 * Ereignissen reagieren Kurse nachweislich verzoegert (Hirshleifer/Lim/Teoh
 * 2009, "Driven to Distraction").
 *
 * <p><b>Inputs im Terminal:</b> gewichtete Tages-Ereignislast aus dem
 * Kalender-Briefing (Earnings-Termine, EZB/Fed-Entscheide, Makro-Daten,
 * Hauptversammlungen) plus Meldungsaufkommen der Ad-hoc-Feeds (fn-adhoc,
 * EQS).
 */
public final class CalendarCollisionDensity {

    private static final int MIN_HISTORY = 30;
    private static final int THIN_HISTORY = 45;

    private CalendarCollisionDensity() {
    }

    /**
     * @param historicalDailyEventWeights historische Tagesgewichte
     *                                    (mindestens {@value #MIN_HISTORY} Tage)
     * @param todayWeight                 heutiges Ereignis-Gewicht
     * @return Befund, oder empty bei zu duenner Historie
     */
    public static Optional<SignalReading> measure(double[] historicalDailyEventWeights, double todayWeight) {
        if (historicalDailyEventWeights == null || historicalDailyEventWeights.length < MIN_HISTORY) {
            return Optional.empty();
        }
        int n = historicalDailyEventWeights.length;
        double percentile = MathKit.empiricalPercentile(todayWeight, historicalDailyEventWeights);

        String formatted = "Perzentil " + MathKit.fmt(percentile, 2)
                + " (heutiges Gewicht " + MathKit.fmt(todayWeight, 1) + ", n=" + n + " Tage)";

        String interpretation;
        if (percentile >= 0.9) {
            interpretation = "KOLLISIONSTAG: kleine Meldungen preisen sich heute verzoegert ein"
                    + " - die unbeachtete Small-Cap-Meldung von heute ist der beste Kandidat fuer noch"
                    + " nicht verdaute Information, gezielt nachfassen.";
        } else if (percentile >= 0.5) {
            interpretation = "Voller Tag: leichter Ablenkungs-Effekt moeglich - einzelne Meldungen"
                    + " koennen etwas verzoegert verarbeitet werden.";
        } else {
            interpretation = "Ruhiger Kalender: Meldungen werden sofort verarbeitet - was heute nicht"
                    + " reagiert, hat den Markt vermutlich wirklich nicht ueberzeugt.";
        }
        if (n < THIN_HISTORY) {
            interpretation += " Vorsicht: nur n=" + n + " historische Tage"
                    + " - duenne Datenbasis, Perzentil mit Zurueckhaltung lesen.";
        }

        return Optional.of(new SignalReading(
                "calendar-collision-density",
                "Kalender-Kollisionsdichte",
                percentile,
                formatted,
                "Misst, wie ueberladen der heutige Ereignis-Kalender gegen die Historie ist - an"
                        + " Kollisionstagen verarbeitet der Markt Informationen nachweislich schlechter"
                        + " (limited attention).",
                interpretation));
    }
}
