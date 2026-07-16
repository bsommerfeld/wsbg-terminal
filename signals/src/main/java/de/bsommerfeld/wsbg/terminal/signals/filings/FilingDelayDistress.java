package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Publikations-Verspaetung als Distress-Signal: misst, wie spaet ein
 * Pflichtdokument gegen das eigene historische Einreichverhalten kommt.
 *
 * <p><b>Methode:</b> z-Score des aktuellen Delays (in Tagen) gegen Mittelwert
 * und Standardabweichung der eigenen historischen Delays, ergaenzt um das
 * empirische Perzentil. Literaturanker: verspaetete Abschluesse als
 * Distress-Praediktor in der Bilanzforschung (Whittred/Zimmer 1984; "late
 * filings" und 12b-25-NT-Meldungen als Negativ-Signal, Bartov/Konchitchki
 * 2017) - das Nicht-Erscheinen ist selbst das Datum.
 *
 * <p><b>Inputs im Terminal:</b> Publikations- und Faelligkeitstermine aus dem
 * Bundesanzeiger (Jahres-/Konzernabschluesse), BaFin-Veroeffentlichungen,
 * EDGAR-Filings und dem Kalender-Briefing (angekuendigte vs. tatsaechliche
 * Berichtstermine).
 */
public final class FilingDelayDistress {

    private static final int MIN_HISTORY = 3;
    private static final int THIN_HISTORY = 5;

    private FilingDelayDistress() {
    }

    /**
     * @param ownHistoricalDelaysDays eigene historische Verspaetungen in Tagen
     *                                (mindestens {@value #MIN_HISTORY} Werte)
     * @param currentDelayDays        aktuelle Verspaetung in Tagen
     * @return Befund, oder empty bei zu duenner Historie
     */
    public static Optional<SignalReading> measure(double[] ownHistoricalDelaysDays, double currentDelayDays) {
        if (ownHistoricalDelaysDays == null || ownHistoricalDelaysDays.length < MIN_HISTORY) {
            return Optional.empty();
        }
        int n = ownHistoricalDelaysDays.length;
        double z = MathKit.zScore(currentDelayDays, ownHistoricalDelaysDays);
        double percentile = MathKit.empiricalPercentile(currentDelayDays, ownHistoricalDelaysDays);

        String formatted = "z=" + MathKit.fmt(z, 2)
                + " (aktuell " + MathKit.fmt(currentDelayDays, 1) + " Tage, Perzentil "
                + MathKit.fmt(percentile * 100, 0) + "%, n=" + n + ")";

        String interpretation;
        if (z >= 1.5) {
            interpretation = "VERSPAETUNGS-FLAG: signifikant spaeter als je zuvor ueblich"
                    + " - Distress-Hypothese aufnehmen und nach Begleitindizien suchen"
                    + " (Wirtschaftspruefer, Refinanzierung).";
        } else if (z >= 0) {
            interpretation = "Im eigenen Rahmen: die Verspaetung liegt innerhalb des historischen"
                    + " Einreichverhaltens - kein eigenstaendiges Signal.";
        } else {
            interpretation = "Frueher als ueblich: das Dokument kommt vor dem eigenen historischen"
                    + " Rhythmus - eher Entwarnung, Management liefert.";
        }
        if (n <= THIN_HISTORY) {
            interpretation += " Vorsicht: nur n=" + n + " historische Einreichungen"
                    + " - duenne Datenbasis, z-Score mit Zurueckhaltung lesen.";
        }

        return Optional.of(new SignalReading(
                "filing-delay-distress",
                "Publikations-Verspaetung (Distress-Signal)",
                z,
                formatted,
                "Misst die Verspaetung eines Pflichtdokuments gegen das eigene historische"
                        + " Einreichverhalten - in der Bilanzforschung ein validierter Distress-Praediktor;"
                        + " das Nicht-Erscheinen ist selbst das Datum.",
                interpretation));
    }
}
