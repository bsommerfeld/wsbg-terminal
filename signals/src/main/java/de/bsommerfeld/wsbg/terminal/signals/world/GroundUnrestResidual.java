package de.bsommerfeld.wsbg.terminal.signals.world;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Bodenunruhe-Residuum: zeigt die Zivilschicht mehr Unruhe, als die
 * ueberregionale Presselage erklaert?
 *
 * <p><b>Methode:</b> Ueber die Historie (ohne den letzten Punkt) wird eine
 * einfache lineare Regression civil = a + b * press per Kleinste-Quadrate-
 * Methode (Legendre/Gauss, klassische OLS-Residualanalyse) geschaetzt. Aus
 * den historischen Residuen entsteht die Vergleichsverteilung; der
 * Signalwert ist der z-Score des aktuellen Residuums (letzter Punkt) gegen
 * diese Verteilung ({@link MathKit#zScore}). Positive Werte heissen: die
 * Zivilschicht ist unruhiger, als die Presselage hergibt.
 *
 * <p><b>Inputs im Terminal:</b> zwei taegliche Index-Reihen aus den
 * Fischernetz-Pegelstaenden der Welt-Kontext-Clients - die Zivilschicht
 * (Blaulicht, Streiks, Oeffis, Kliniken) als aggregierter Unruhe-Index gegen
 * einen aggregierten Index der ueberregionalen Presselage.
 */
public final class GroundUnrestResidual {

    /** Unter dieser Tages-Zahl keine Messung. */
    private static final int MIN_DAYS = 30;
    /** Unter dieser Tages-Zahl traegt die Deutung einen Vorsichts-Zusatz. */
    private static final int COMFORTABLE_DAYS = 60;
    /** Ab hier sind wir messbar frueh (sigma). */
    private static final double EARLY = 2.0;
    /** Ab hier leichte Voreile (sigma). */
    private static final double SLIGHT = 1.0;

    private GroundUnrestResidual() {
    }

    /**
     * Misst die unerklärte Bodenunruhe als z-Score des aktuellen
     * Regressions-Residuums.
     *
     * @param civilIndexSeries Zivilschicht-Unruhe-Index pro Tag
     * @param pressIndexSeries Presselage-Index pro Tag (gleiche Tage)
     * @return Befund, oder empty bei ungleicher Laenge oder weniger als {@value #MIN_DAYS} Tagen
     */
    public static Optional<SignalReading> measure(double[] civilIndexSeries, double[] pressIndexSeries) {
        if (civilIndexSeries == null || pressIndexSeries == null
                || civilIndexSeries.length != pressIndexSeries.length
                || civilIndexSeries.length < MIN_DAYS) {
            return Optional.empty();
        }
        int n = civilIndexSeries.length;
        int h = n - 1; // Historie ohne den aktuellen (letzten) Punkt

        // OLS civil = a + b * press ueber die Historie
        double meanPress = 0;
        double meanCivil = 0;
        for (int i = 0; i < h; i++) {
            meanPress += pressIndexSeries[i];
            meanCivil += civilIndexSeries[i];
        }
        meanPress /= h;
        meanCivil /= h;
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < h; i++) {
            double dx = pressIndexSeries[i] - meanPress;
            sxy += dx * (civilIndexSeries[i] - meanCivil);
            sxx += dx * dx;
        }
        double b = sxx == 0 ? 0 : sxy / sxx;
        double a = meanCivil - b * meanPress;

        double[] residuals = new double[h];
        for (int i = 0; i < h; i++) {
            residuals[i] = civilIndexSeries[i] - (a + b * pressIndexSeries[i]);
        }
        double currentResidual = civilIndexSeries[n - 1] - (a + b * pressIndexSeries[n - 1]);
        double value = MathKit.zScore(currentResidual, residuals);

        String interpretation;
        if (value >= EARLY) {
            interpretation = "WIR SIND FRÜH: die Zivilschicht zeigt " + MathKit.fmt(value, 1)
                    + " sigma mehr Unruhe, als die überregionale Presselage erklärt - die Story "
                    + "ist noch lokal. Das Fenster schließt, sobald die Presse nachzieht, also "
                    + "jetzt lokal nachrecherchieren.";
        } else if (value >= SLIGHT) {
            interpretation = "Leichte Voreile: die Zivilschicht liegt etwas über dem, was die "
                    + "Presselage erklärt - beobachten, noch kein belastbarer Vorsprung.";
        } else {
            interpretation = "Zivilschicht durch die Presselage gedeckt: keine unerklärte "
                    + "Bodenunruhe, kein Informationsvorsprung.";
        }
        if (n < COMFORTABLE_DAYS) {
            interpretation += " Vorsicht: nur " + n
                    + " Tage Historie - die Residuen-Basis ist entsprechend schmal.";
        }

        return Optional.of(new SignalReading(
                "ground-unrest-residual",
                "Bodenunruhe-Residuum (Zivilschicht vs Presse)",
                value,
                MathKit.fmt(value, 2) + " sigma (z-Score des aktuellen Regressions-Residuums)",
                "Misst, ob die Zivilschicht heute mehr Unruhe zeigt, als die überregionale "
                        + "Presselage erklärt - das messbare Netz-sieht-es-vor-der-Redaktion.",
                interpretation));
    }
}
