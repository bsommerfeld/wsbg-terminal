package de.bsommerfeld.wsbg.terminal.signals.world;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Kalibrierung einer physischen Vorlaufkette per Kreuzkorrelationsanalyse.
 *
 * <p><b>Methode:</b> Fuer jede Verschiebung lag = 1..maxLagDays wird die
 * Kreuzkorrelation corr(Ursache[t], Wirkung[t+lag]) gerechnet
 * ({@link MathKit#crossCorrelationAtLag}) - klassische Cross-Correlation-
 * Function-Analyse nach Box/Jenkins ("Time Series Analysis", 1970). Der
 * beste Lag ist das Argmax der Korrelation; der Signalwert ist die
 * Pearson-Korrelation an genau diesem Lag. Kein Kuratieren, nur Messen:
 * welche Ursache-Reihe gegen welche Wirkungs-Reihe laeuft, entscheidet der
 * Aufrufer zur Laufzeit.
 *
 * <p><b>Inputs im Terminal:</b> zwei taegliche Fischernetz-Pegelreihen der
 * Welt-Kontext-Clients, z.B. ein Maritim- oder Oel-Pegel als Ursache gegen
 * einen Preis- oder Politik-Pegel als Wirkung; auch Zivilschicht-Pegel
 * (Blaulicht, Streiks, Oeffis) sind als Ursache-Reihe zulaessig.
 */
public final class SupplyChainLag {

    /** Zusaetzlich zum maximalen Lag noetige Mindest-Ueberlappung. */
    private static final int MIN_OVERLAP = 20;
    /** Unter dieser Ueberlappung traegt die Deutung einen Vorsichts-Zusatz. */
    private static final int COMFORTABLE_OVERLAP = 40;
    /** Ab hier gilt die Vorlaufkette als bestaetigt. */
    private static final double CONFIRMED = 0.4;
    /** Ab hier gilt ein schwacher Vorlauf. */
    private static final double WEAK = 0.2;

    private SupplyChainLag() {
    }

    /**
     * Kalibriert, ob und mit wie vielen Tagen Vorlauf die Ursache-Reihe der
     * Wirkungs-Reihe vorausgeht.
     *
     * @param causeSeries  Ursache-Pegel pro Tag
     * @param effectSeries Wirkungs-Pegel pro Tag (gleiche Tage)
     * @param maxLagDays   maximal geprüfte Verschiebung in Tagen (mindestens 1)
     * @param causeLabel   Laufzeit-Name der Ursache-Reihe
     * @param effectLabel  Laufzeit-Name der Wirkungs-Reihe
     * @return Befund, oder empty bei ungleicher Laenge, maxLagDays &lt; 1 oder
     *         weniger als maxLagDays + {@value #MIN_OVERLAP} Tagen
     */
    public static Optional<SignalReading> measure(double[] causeSeries, double[] effectSeries,
                                                  int maxLagDays, String causeLabel, String effectLabel) {
        if (causeSeries == null || effectSeries == null || causeLabel == null || effectLabel == null
                || maxLagDays < 1
                || causeSeries.length != effectSeries.length
                || causeSeries.length < maxLagDays + MIN_OVERLAP) {
            return Optional.empty();
        }

        int bestLag = 1;
        double bestCorr = Double.NEGATIVE_INFINITY;
        for (int lag = 1; lag <= maxLagDays; lag++) {
            double c = MathKit.crossCorrelationAtLag(causeSeries, effectSeries, lag);
            if (c > bestCorr) {
                bestCorr = c;
                bestLag = lag;
            }
        }
        double value = bestCorr;

        String interpretation;
        if (value >= CONFIRMED) {
            interpretation = "VORLAUFKETTE BESTÄTIGT: " + causeLabel + " läuft " + effectLabel
                    + " ~" + bestLag + " Tage voraus (r=" + MathKit.fmt(value, 2)
                    + "). Schlägt " + causeLabel + " JETZT aus, ist das Fenster offen, bis der Lag "
                    + "verstrichen ist - der seltene Fall von echtem, legalem Informationsvorsprung, "
                    + "weil die Verzögerung Tage bis Wochen beträgt.";
        } else if (value >= WEAK) {
            interpretation = "Schwacher Vorlauf: " + causeLabel + " geht " + effectLabel
                    + " am besten Lag von ~" + bestLag + " Tagen nur lose voraus (r="
                    + MathKit.fmt(value, 2)
                    + ") - höchstens als Nebenindiz neben härteren Belegen verwenden.";
        } else {
            interpretation = "Kein belastbarer Vorlauf: auch am besten Lag (" + bestLag
                    + " Tage) koppelt " + causeLabel + " nicht messbar an " + effectLabel
                    + " (r=" + MathKit.fmt(value, 2) + ") - diese Kette verwerfen.";
        }
        if (causeSeries.length < maxLagDays + COMFORTABLE_OVERLAP) {
            interpretation += " Vorsicht: nur " + causeSeries.length + " Tage Daten bei bis zu "
                    + maxLagDays + " Tagen Verschiebung - die Lag-Schätzung ist entsprechend unsicher.";
        }

        return Optional.of(new SignalReading(
                "supply-chain-lag",
                "Vorlaufketten-Kalibrierung (Kreuzkorrelation)",
                value,
                MathKit.fmt(value, 2) + " (Pearson-r, Skala -1 bis 1, bester Lag " + bestLag + " Tage)",
                "Misst, ob und mit wie vielen Tagen Verzögerung die Ursache-Reihe (" + causeLabel
                        + ") der Wirkungs-Reihe (" + effectLabel
                        + ") vorausläuft - kalibriert eine physische Vorlaufkette rein aus den Daten.",
                interpretation));
    }
}
