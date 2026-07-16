package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Sentiment-Divergenz Käfig vs Wall Street: z-Score der aktuellen Differenz
 * zwischen dem eigenen Reddit-Barometer und einem externen
 * Fear&amp;Greed-Index, plus CUSUM-Umschwung-Detektor.
 *
 * <p>Numerik: aus beiden 0-100-Reihen wird die Divergenzreihe
 * d = käfig - extern gebildet; der Signalwert ist der z-Score des letzten d
 * gegen die Historie der Reihe. Zusätzlich läuft ein einseitiger CUSUM in
 * beide Richtungen (Page 1954; Referenzwert k = 0.5 Sigma, Schwelle
 * h = 4 Sigma, Standard-Parametrisierung der SPC-Literatur) über d - schlägt
 * er am aktuellen Rand an, liegt ein frischer, anhaltender Umschwung der
 * Divergenz vor, den ein einzelner z-Score noch nicht zeigt. Hintergrund ist
 * die Sentiment-Spread-Literatur (Retail vs. institutionell als
 * Kontra-Indikator, vgl. Baker/Wurgler 2006).
 *
 * <p>Input im Terminal: die Historie des eigenen Reddit-Stimmungs-Barometers
 * (0-100) und die gleichgetaktete Historie eines externen
 * Fear&amp;Greed-Index (0-100), beide aus dem Markt-Gedächtnis.
 */
public final class SentimentDivergence {

    private static final String ID = "sentiment-divergence";
    private static final String TITLE = "Sentiment-Divergenz Käfig vs Wall Street (CUSUM)";
    private static final String DEFINITION =
            "Misst, wie ungewöhnlich weit das Reddit-Barometer gerade vom externen"
                    + " Fear&Greed abweicht (z-Score der aktuellen Divergenz gegen"
                    + " ihre eigene Historie), plus CUSUM-Detektor für einen"
                    + " frischen Umschwung der Divergenz.";

    private static final int MIN_LENGTH = 20;
    private static final int THIN_LENGTH = 40;
    private static final double Z_THRESHOLD = 1.5;
    private static final double CUSUM_K_SIGMA = 0.5;
    private static final double CUSUM_H_SIGMA = 4.0;

    private SentimentDivergence() {
    }

    /**
     * Berechnet z-Score und CUSUM-Status der Divergenzreihe. Beide Reihen
     * müssen gleich lang sein und mindestens {@value #MIN_LENGTH} Punkte
     * haben, sonst {@link Optional#empty()}.
     *
     * @param cageSeries0to100     Historie des eigenen Reddit-Barometers (0-100)
     * @param externalSeries0to100 gleichgetaktete Historie des externen Fear&amp;Greed (0-100)
     */
    public static Optional<SignalReading> measure(
            double[] cageSeries0to100, double[] externalSeries0to100) {
        if (cageSeries0to100 == null || externalSeries0to100 == null
                || cageSeries0to100.length != externalSeries0to100.length
                || cageSeries0to100.length < MIN_LENGTH) {
            return Optional.empty();
        }
        int n = cageSeries0to100.length;
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(cageSeries0to100[i]) || !Double.isFinite(externalSeries0to100[i])) {
                return Optional.empty();
            }
            d[i] = cageSeries0to100[i] - externalSeries0to100[i];
        }
        double last = d[n - 1];
        double value = MathKit.zScore(last, d);
        int cusum = cusumDirection(d);

        String formatted = "z = " + fmtSigned(value) + " (aktuelle Divergenz "
                + fmtSigned(last) + " Punkte auf der 0-100-Skala)";
        return Optional.of(new SignalReading(
                ID, TITLE, value, formatted, DEFINITION, interpret(value, cusum, n)));
    }

    /**
     * Einseitiger CUSUM in beide Richtungen (k = 0.5 Sigma, h = 4 Sigma).
     * Rückgabe: +1 wenn am Reihenende ein Aufwärts-Alarm aktiv ist, -1 bei
     * Abwärts-Alarm, 0 sonst (auch bei degenerierter Reihe).
     */
    private static int cusumDirection(double[] d) {
        double sigma = MathKit.std(d);
        if (sigma == 0 || !Double.isFinite(sigma)) {
            return 0;
        }
        double target = MathKit.mean(d);
        double k = CUSUM_K_SIGMA * sigma;
        double h = CUSUM_H_SIGMA * sigma;
        double sPlus = 0, sMinus = 0;
        for (double x : d) {
            sPlus = Math.max(0, sPlus + (x - target - k));
            sMinus = Math.max(0, sMinus + (target - x - k));
        }
        if (sPlus > h) return 1;
        if (sMinus > h) return -1;
        return 0;
    }

    private static String interpret(double value, int cusum, int n) {
        String band;
        if (value >= Z_THRESHOLD) {
            band = "KÄFIG GIERIG, WALL STREET ÄNGSTLICH: klassische"
                    + " Kontra-Konstellation - Retail kauft in institutionelle Angst"
                    + " hinein, historisch ein schlechtes Vorzeichen für die"
                    + " Retail-Seite.";
        } else if (value <= -Z_THRESHOLD) {
            band = "KÄFIG ÄNGSTLICH, WALL STREET GIERIG: Retail-Kapitulation bei"
                    + " institutioneller Zuversicht - oft ein spätes Boden-Muster.";
        } else {
            band = "Käfig und Wall Street sind im Einklang - die aktuelle"
                    + " Divergenz liegt im normalen Rauschen ihrer Historie.";
        }
        if (cusum > 0) {
            band += " Zusätzlich: frischer Umschwung der Divergenz seit kurzem"
                    + " (CUSUM-Alarm nach oben, der Käfig löst sich anhaltend nach"
                    + " oben von der Wall Street).";
        } else if (cusum < 0) {
            band += " Zusätzlich: frischer Umschwung der Divergenz seit kurzem"
                    + " (CUSUM-Alarm nach unten, der Käfig löst sich anhaltend nach"
                    + " unten von der Wall Street).";
        }
        if (n < THIN_LENGTH) {
            band += " Vorsicht: nur " + n + " Punkte Historie, z-Score und CUSUM"
                    + " sind entsprechend wackelig.";
        }
        return band;
    }

    private static String fmtSigned(double v) {
        return (v >= 0 ? "+" : "") + MathKit.fmt(v, 2);
    }
}
