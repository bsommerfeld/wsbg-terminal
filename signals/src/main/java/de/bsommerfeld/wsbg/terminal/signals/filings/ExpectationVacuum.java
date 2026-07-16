package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Erwartungs-Vakuum: Kombinations-Signal aus Kalender-Naehe und
 * Reddit-Aufmerksamkeit - sucht Termine, auf die niemand schaut.
 *
 * <p><b>Methode:</b> Produkt dreier normierter Faktoren:
 * Naehe = 1 - Tage/14 (Termin steht kurz bevor),
 * Stille = 1 - min(1, Erwaehnungen/Baseline) (kaum Erwaehnungen relativ zur
 * eigenen Baseline) und Streuung = normierte Aufmerksamkeits-Entropie
 * (keine fokussierte Aufmerksamkeit). Literaturanker: limited attention und
 * Unteradaption an oeffentliche Termine (Hirshleifer/Teoh 2003;
 * DellaVigna/Pollet 2009) - ohne eingepreiste Erwartung ist die Bewegung pro
 * Informationseinheit maximal.
 *
 * <p><b>Inputs im Terminal:</b> Termin-Naehe aus dem Kalender-Briefing
 * (Earnings, EZB/Fed, Makro), Erwaehnungsraten aus dem Reddit-Feed und die
 * normierte Entropie aus dem Aufmerksamkeits-Entropie-Signal.
 */
public final class ExpectationVacuum {

    private static final long MAX_DAYS = 14;
    private static final double THIN_BASELINE = 1.0;

    private ExpectationVacuum() {
    }

    /**
     * @param daysToEvent                 Tage bis zum Termin (0 bis {@value #MAX_DAYS})
     * @param mentionsPerDay              aktuelle Erwaehnungen pro Tag
     * @param baselineMentionsPerDay      historische Baseline der Erwaehnungen pro Tag (> 0)
     * @param normalizedAttentionEntropy  normierte Aufmerksamkeits-Entropie in [0,1]
     * @return Befund, oder empty wenn der Termin ausserhalb des Fensters liegt oder die Baseline fehlt
     */
    public static Optional<SignalReading> measure(long daysToEvent, double mentionsPerDay,
                                                  double baselineMentionsPerDay,
                                                  double normalizedAttentionEntropy) {
        if (daysToEvent < 0 || daysToEvent > MAX_DAYS || !(baselineMentionsPerDay > 0)) {
            return Optional.empty();
        }
        double proximity = 1.0 - daysToEvent / (double) MAX_DAYS;
        double silence = 1.0 - Math.min(1.0, mentionsPerDay / baselineMentionsPerDay);
        double spread = Math.max(0.0, Math.min(1.0, normalizedAttentionEntropy));
        double value = proximity * silence * spread;

        String formatted = MathKit.fmt(value, 2) + " (Skala 0-1; Naehe " + MathKit.fmt(proximity, 2)
                + " x Stille " + MathKit.fmt(silence, 2) + " x Streuung " + MathKit.fmt(spread, 2)
                + ", Termin in " + daysToEvent + " Tag(en))";

        String interpretation;
        if (value >= 0.6) {
            interpretation = "ERWARTUNGS-VAKUUM: es gibt keine eingepreiste Erwartung - maximale"
                    + " Bewegung pro Informationseinheit, idealer Kandidat fuer eine vorbereitende DD."
                    + " Termin in " + daysToEvent + " Tag(en).";
        } else if (value >= 0.3) {
            interpretation = "Duenn beachtet: der Termin laeuft unter dem Radar, aber nicht voellig"
                    + " unbeobachtet - ein Blick vorab kann sich lohnen."
                    + " Termin in " + daysToEvent + " Tag(en).";
        } else {
            interpretation = "Erwartung ist gebildet: der Termin ist eingepreist - hier ueberrascht nur"
                    + " noch eine Abweichung vom Konsens."
                    + " Termin in " + daysToEvent + " Tag(en).";
        }
        if (baselineMentionsPerDay < THIN_BASELINE) {
            interpretation += " Vorsicht: sehr niedrige Erwaehnungs-Baseline ("
                    + MathKit.fmt(baselineMentionsPerDay, 2) + "/Tag)"
                    + " - duenne Datenbasis, Stille-Faktor mit Zurueckhaltung lesen.";
        }

        return Optional.of(new SignalReading(
                "expectation-vacuum",
                "Erwartungs-Vakuum (Kombi-Signal)",
                value,
                formatted,
                "Dreht den Kalender um - sucht Termine, auf die NIEMAND schaut: keine Erwaehnungen,"
                        + " keine fokussierte Aufmerksamkeit, Termin steht kurz bevor.",
                interpretation));
    }
}
