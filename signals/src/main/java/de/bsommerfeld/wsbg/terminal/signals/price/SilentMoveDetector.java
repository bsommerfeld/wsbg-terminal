package de.bsommerfeld.wsbg.terminal.signals.price;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Stiller Move: Event-Study-Zerlegung der heutigen Rendite in Markt und Rest.
 *
 * <p><b>Methode:</b> Aus der gemeinsamen Historie wird per OLS das Beta des
 * Papiers gegen den Markt geschaetzt (Marktmodell der Event-Study-Literatur,
 * Fama/Fisher/Jensen/Roll 1969, kanonisch bei MacKinlay 1997). Die abnormale
 * Rendite ist die heutige Asset-Rendite minus Beta mal Markt-Rendite; sie wird
 * als z-Score gegen die historischen Residuen desselben Modells gestellt
 * ({@link MathKit#zScore}). Bleibt nach Abzug des Marktes ein grosser,
 * unerklaerter Rest UND gibt es keine Headline, die ihn attribuiert, handelt
 * jemand auf Information, die wir nicht haben.
 *
 * <p><b>Inputs im Terminal:</b> die Renditereihen kommen aus den
 * Kurs-Historien des Terminals (L&amp;S/Tradegate fuer das Papier, eine
 * Index-Reihe als Marktproxy), das Attributions-Flag aus der Ticker-Zuordnung
 * des Headline-Archivs zum aktuellen Tick.
 */
public final class SilentMoveDetector {

    /** Unter dieser Historien-Laenge ist kein Beta belastbar. */
    private static final int MIN_HISTORY = 30;
    /** Unter dieser Historien-Laenge traegt die Deutung einen Vorsichts-Zusatz. */
    private static final int COMFORTABLE_HISTORY = 60;
    /** Ab diesem absoluten z-Score gilt der Rest als abnormal. */
    private static final double Z_ABNORMAL = 2.5;

    private SilentMoveDetector() {
    }

    /**
     * Misst, wie ungewoehnlich der marktbereinigte Rest der heutigen Rendite ist.
     *
     * @param assetReturnsPct      historische Renditen des Papiers in Prozent
     * @param marketReturnsPct     historische Renditen des Marktproxys in Prozent (gleiche Laenge)
     * @param currentAssetReturnPct  heutige Rendite des Papiers in Prozent
     * @param currentMarketReturnPct heutige Rendite des Marktproxys in Prozent
     * @param attributableHeadline ob eine Headline vorliegt, die den Move erklaert
     * @return Befund, oder empty bei ungleich langen Reihen, weniger als
     *         {@value #MIN_HISTORY} Punkten oder degeneriertem Markt
     */
    public static Optional<SignalReading> measure(double[] assetReturnsPct,
            double[] marketReturnsPct, double currentAssetReturnPct,
            double currentMarketReturnPct, boolean attributableHeadline) {
        if (assetReturnsPct == null || marketReturnsPct == null
                || assetReturnsPct.length != marketReturnsPct.length
                || assetReturnsPct.length < MIN_HISTORY
                || !Double.isFinite(currentAssetReturnPct)
                || !Double.isFinite(currentMarketReturnPct)) {
            return Optional.empty();
        }

        int n = assetReturnsPct.length;
        double marketMean = MathKit.mean(marketReturnsPct);
        double assetMean = MathKit.mean(assetReturnsPct);
        double covariance = 0;
        double marketSq = 0;
        for (int i = 0; i < n; i++) {
            double dm = marketReturnsPct[i] - marketMean;
            covariance += dm * (assetReturnsPct[i] - assetMean);
            marketSq += dm * dm;
        }
        if (marketSq == 0) {
            return Optional.empty();
        }
        double beta = covariance / marketSq;

        double[] residuals = new double[n];
        for (int i = 0; i < n; i++) {
            residuals[i] = assetReturnsPct[i] - beta * marketReturnsPct[i];
        }
        double abnormalReturnPct = currentAssetReturnPct - beta * currentMarketReturnPct;
        double z = MathKit.zScore(abnormalReturnPct, residuals);

        String interpretation;
        if (Math.abs(z) >= Z_ABNORMAL && !attributableHeadline) {
            interpretation = "STILLER MOVE: nach Abzug des Marktes bleibt ein Rest, den keine "
                    + "Headline erklärt - jemand handelt auf Information, die wir nicht haben. "
                    + "Automatischer Rechercheauftrag: Quellen zu diesem Papier aktiv abgrasen.";
        } else if (Math.abs(z) >= Z_ABNORMAL) {
            interpretation = "Move ist attribuiert: eine Headline erklärt den abnormalen Rest - "
                    + "jetzt die Stärke der Reaktion gegen die Basisrate des Ereignistyps prüfen.";
        } else {
            interpretation = "Im Rahmen: der heutige Move ist durch Markt und normales Rauschen "
                    + "gedeckt, kein unerklärter Rest.";
        }
        interpretation += " Zerlegung: Beta " + MathKit.fmt(beta, 2)
                + " gegen den Markt, abnormale Rendite " + MathKit.fmt(abnormalReturnPct, 2) + " %.";
        if (n < COMFORTABLE_HISTORY) {
            interpretation += " Vorsicht: nur " + n
                    + " historische Punkte für Beta und Residuen - die Zerlegung ist entsprechend unsicher.";
        }

        return Optional.of(new SignalReading(
                "silent-move-detector",
                "Stiller Move (Event-Study-Attribution)",
                z,
                MathKit.fmt(z, 2) + " (z-Score der abnormalen Rendite; abnormal "
                        + MathKit.fmt(abnormalReturnPct, 2) + " %, Beta " + MathKit.fmt(beta, 2) + ")",
                "Zerlegt die heutige Rendite nach dem Marktmodell der Event-Study in Beta mal "
                        + "Markt plus Rest und misst, wie ungewöhnlich dieser Rest gegen die "
                        + "eigenen historischen Residuen ist.",
                interpretation));
    }
}
