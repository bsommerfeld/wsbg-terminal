package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Sandbagging-Profil: misst per Beta-Binomial-Posterior, ob eine Firma ihre
 * Prognosen systematisch schlaegt - und ob die aktuelle Ueberraschung selbst
 * gegen dieses Muster noch aussergewoehnlich ist.
 *
 * <p><b>Methode:</b> Beta(1,1)-Posterior fuer die Beat-Wahrscheinlichkeit,
 * P(beat) = (beats + 1) / (reports + 2). Liegt eine aktuelle
 * Surprise-Prozentzahl und genug Surprise-Historie vor, wird zusaetzlich die
 * standardisierte Ueberraschung z = (aktuell - Mittel) / Std berechnet.
 * Literaturanker: Erwartungsmanagement und "meet or beat"-Verhalten
 * (Bartov/Givoly/Hayn 2002; Matsumoto 2002) - der eingepreiste Beat traegt
 * keine Information, erst die Abweichung vom eigenen Muster bewegt.
 *
 * <p><b>Inputs im Terminal:</b> historische Beat/Miss-Bilanz und
 * Surprise-Prozente aus dem Kalender-Briefing (Ist vs. Prognose) sowie
 * Ergebnis-Meldungen der Ad-hoc-Feeds (fn-adhoc, EQS) und EDGAR-Filings.
 */
public final class EarningsSurpriseProfile {

    private static final int MIN_REPORTS = 4;
    private static final int MIN_SURPRISE_HISTORY = 5;
    private static final int THIN_REPORTS = 8;

    private EarningsSurpriseProfile() {
    }

    /**
     * @param historicalBeats         Anzahl historischer Beats
     * @param historicalReports       Anzahl historischer Reports (mindestens {@value #MIN_REPORTS})
     * @param currentSurprisePct      aktuelle Ueberraschung in Prozent, oder null wenn unbekannt
     * @param historicalSurprisesPct  historische Ueberraschungen in Prozent (fuer den z-Score,
     *                                mindestens {@value #MIN_SURPRISE_HISTORY} Werte noetig)
     * @return Befund, oder empty bei zu duenner Datenlage
     */
    public static Optional<SignalReading> measure(int historicalBeats, int historicalReports,
                                                  Double currentSurprisePct, double[] historicalSurprisesPct) {
        if (historicalReports < MIN_REPORTS || historicalBeats < 0 || historicalBeats > historicalReports) {
            return Optional.empty();
        }
        double posterior = (historicalBeats + 1.0) / (historicalReports + 2.0);
        double[] ci = MathKit.jeffreysInterval(historicalBeats, historicalReports, 0.90);

        Double z = null;
        if (currentSurprisePct != null && historicalSurprisesPct != null
                && historicalSurprisesPct.length >= MIN_SURPRISE_HISTORY) {
            double sd = MathKit.std(historicalSurprisesPct);
            if (sd > 0 && Double.isFinite(sd)) {
                z = (currentSurprisePct - MathKit.mean(historicalSurprisesPct)) / sd;
            }
        }

        String formatted = "P(Beat)=" + MathKit.fmt(posterior, 2)
                + " (90%-Intervall " + MathKit.fmt(ci[0], 2) + "-" + MathKit.fmt(ci[1], 2)
                + ", n=" + historicalReports
                + (z != null ? ", Surprise-z=" + MathKit.fmt(z, 2) : "") + ")";

        String zNote = z != null
                ? " Standardisierte Ueberraschung z=" + MathKit.fmt(z, 2) + " bei n=" + historicalReports + "."
                : " Keine standardisierte Ueberraschung berechenbar (zu wenig Surprise-Historie), n="
                + historicalReports + ".";

        String interpretation;
        if (posterior >= 0.7 && z != null && z >= 1) {
            interpretation = "ECHTE UEBERRASCHUNG: selbst gegen das eigene Sandbagging-Muster"
                    + " aussergewoehnlich - das bewegt." + zNote;
        } else if (posterior >= 0.7) {
            interpretation = "BEAT IST HIER DER NORMALFALL: die aktuelle Zahl ist kein echtes Signal,"
                    + " der Markt kennt das Muster." + zNote;
        } else if (posterior < 0.5) {
            interpretation = "Ehrlicher Streuer: kein systematisches Erwartungsmanagement erkennbar"
                    + " - hier zaehlt schon der einfache Beat/Miss." + zNote;
        } else {
            interpretation = "Gemischtes Beat-Profil: kein klares Muster in der Historie"
                    + " - die aktuelle Zahl fuer sich bewerten." + zNote;
        }
        if (historicalReports < THIN_REPORTS) {
            interpretation += " Vorsicht: nur n=" + historicalReports + " Reports"
                    + " - duenne Datenbasis, Profil mit Zurueckhaltung lesen.";
        }

        return Optional.of(new SignalReading(
                "earnings-surprise-profile",
                "Sandbagging-Profil (Beta-Binomial)",
                posterior,
                formatted,
                "Misst das Erwartungsmanagement einer Firma - schlaegt sie ihre Prognosen systematisch"
                        + " (Sandbagging), ist ein Beat der Normalfall und vom Markt eingepreist.",
                interpretation));
    }
}
