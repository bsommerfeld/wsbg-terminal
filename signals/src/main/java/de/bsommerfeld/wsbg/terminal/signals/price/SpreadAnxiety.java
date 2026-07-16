package de.bsommerfeld.wsbg.terminal.signals.price;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Spread-Angstsensor: die Risikopraemie des Market-Makers als Vorlauf-Indiz.
 *
 * <p><b>Methode:</b> Der relative Bid-Ask-Spread (ask - bid geteilt durch den
 * Mittelkurs) wird als z-Score gegen die eigene Spread-Historie gestellt
 * ({@link MathKit#zScore}). Theorieanker ist die Mikrostruktur-Literatur zur
 * adversen Selektion (Glosten/Milgrom 1985): der Spread ist die Praemie, die
 * der Market-Maker gegen informierte Gegenparteien verlangt. Reisst er auf,
 * ohne dass eine Kursbewegung oder eine frische Headline den Anlass liefert,
 * fuerchtet der Profi etwas Nicht-Oeffentliches - genau diese Kombination
 * schaltet das Signal scharf.
 *
 * <p><b>Inputs im Terminal:</b> bid/ask kommen live aus den L&amp;S- bzw.
 * Tradegate-Quotes, die historischen relativen Spreads aus den laufenden
 * Kurs-Snapshots desselben Papiers, die juengste absolute Kursbewegung aus der
 * Kursreihe und das Headline-Flag aus der Ticker-Zuordnung des
 * Headline-Archivs.
 */
public final class SpreadAnxiety {

    /** Unter dieser Historien-Laenge ist kein z-Score belastbar. */
    private static final int MIN_HISTORY = 30;
    /** Unter dieser Historien-Laenge traegt die Deutung einen Vorsichts-Zusatz. */
    private static final int COMFORTABLE_HISTORY = 60;
    /** Ab diesem z-Score gilt die Risikopraemie als aufgerissen. */
    private static final double Z_WIDE = 2.0;
    /** Unter diesem z-Score gilt der Spread als ungewoehnlich eng. */
    private static final double Z_TIGHT = -1.5;
    /** Unter dieser absoluten Bewegung (in Prozent) gilt der Kurs als ruhig. */
    private static final double QUIET_RETURN_PCT = 0.5;

    private SpreadAnxiety() {
    }

    /**
     * Misst die aktuelle Market-Maker-Risikopraemie gegen ihre eigene Historie.
     *
     * @param bid                       aktueller Geldkurs, muss &gt; 0 sein
     * @param ask                       aktueller Briefkurs, muss &gt;= bid sein
     * @param historicalRelativeSpreads historische relative Spreads desselben Papiers
     * @param recentAbsReturnPct        absolute juengste Kursbewegung in Prozent
     * @param freshHeadlinePresent      ob eine frische Headline zu diesem Papier vorliegt
     * @return Befund, oder empty bei ungueltigen Quotes oder weniger als
     *         {@value #MIN_HISTORY} historischen Spreads
     */
    public static Optional<SignalReading> measure(double bid, double ask,
            double[] historicalRelativeSpreads, double recentAbsReturnPct,
            boolean freshHeadlinePresent) {
        if (!Double.isFinite(bid) || !Double.isFinite(ask) || bid <= 0 || ask <= 0 || ask < bid) {
            return Optional.empty();
        }
        if (historicalRelativeSpreads == null || historicalRelativeSpreads.length < MIN_HISTORY) {
            return Optional.empty();
        }

        double mid = (bid + ask) / 2;
        double relativeSpread = (ask - bid) / mid;
        double z = MathKit.zScore(relativeSpread, historicalRelativeSpreads);

        String interpretation;
        if (z >= Z_WIDE && recentAbsReturnPct < QUIET_RETURN_PCT && !freshHeadlinePresent) {
            interpretation = "ANGSTSENSOR SCHLÄGT AN: die Risikoprämie des Market-Makers steigt "
                    + "ohne öffentlichen Anlass - weder Kursbewegung noch frische Headline erklären "
                    + "den Sprung. Vorlauf-Indiz, Rechercheanlass mit hoher Priorität.";
        } else if (z >= Z_WIDE) {
            interpretation = "Risikoprämie erhöht, aber konsistent zur Nachrichtenlage - "
                    + "Kursbewegung oder frische Headline erklären den breiten Spread, "
                    + "kein Zusatzsignal.";
        } else if (z <= Z_TIGHT) {
            interpretation = "Ungewöhnlich enger Spread - der Market-Maker sieht ruhiges "
                    + "Fahrwasser und verlangt weniger Risikoprämie als üblich.";
        } else {
            interpretation = "Unauffällig: der Spread liegt im normalen Band der eigenen Historie.";
        }
        if (historicalRelativeSpreads.length < COMFORTABLE_HISTORY) {
            interpretation += " Vorsicht: nur " + historicalRelativeSpreads.length
                    + " historische Spreads als Vergleichsbasis - der z-Score ist entsprechend unsicher.";
        }

        return Optional.of(new SignalReading(
                "spread-anxiety",
                "Spread-Angstsensor (Market-Maker-Risikoprämie)",
                z,
                MathKit.fmt(z, 2) + " (z-Score des relativen Spreads; aktuell "
                        + MathKit.fmt(relativeSpread * 100, 3) + " %)",
                "Misst, wie stark die aktuelle Risikoprämie des Market-Makers - der relative "
                        + "Bid-Ask-Spread - vom eigenen historischen Normalband abweicht.",
                interpretation));
    }
}
