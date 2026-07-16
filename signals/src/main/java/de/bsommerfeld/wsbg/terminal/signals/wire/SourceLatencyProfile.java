package de.bsommerfeld.wsbg.terminal.signals.wire;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Quellen-Latenz-Profil: erkennt den Exklusiv-Verdacht, wenn eine chronisch
 * langsame Quelle eine Story als Erstdrucker bringt.
 *
 * <p><b>Methode:</b> robuste Lagemasse statt Mittelwerten (Median-Statistik im
 * Sinne der explorativen Datenanalyse nach Tukey, "Exploratory Data Analysis",
 * 1977). Fuer jede Quelle wird der Median ihrer historischen Minuten hinter dem
 * jeweiligen Erstdrucker gebildet (0 = war selbst Erstdrucker). Der Befund ist
 * das Langsamkeits-Ratio: Median der aktuellen Erstdrucker-Quelle geteilt durch
 * den Median aller Quellen-Mediane. Ein Ratio deutlich ueber 1 heisst: eine
 * Quelle, die sonst hinterherdruckt, ist diesmal vorn - das klassische Muster
 * von Eigenmaterial/Exklusivrecherche statt Agentur-Uebernahme.
 *
 * <p><b>Inputs im Terminal:</b> die Story-Cluster der Redaktion tragen pro
 * Mitglied den Quellen-Zeitstempel; daraus laesst sich pro Story die Reihenfolge
 * der Drucker und pro Quelle die Historie "Minuten hinter dem Erstdrucker"
 * ableiten. Diese Historie wird hier als {@code minutesBehindFirstBySource}
 * uebergeben, zusammen mit dem Namen der Quelle, die die aktuelle Story zuerst
 * gedruckt hat.
 */
public final class SourceLatencyProfile {

    /** Stabiler Maschinen-Schluessel dieses Signals. */
    public static final String ID = "source-latency-profile";

    private static final String TITLE = "Quellen-Latenz (Erstdrucker-Analyse)";

    private static final int MIN_OBSERVATIONS = 10;
    private static final int COMFORTABLE_OBSERVATIONS = 20;
    private static final int MIN_SOURCES = 2;
    private static final int COMFORTABLE_SOURCES = 3;

    private SourceLatencyProfile() {
    }

    /**
     * @param firstPrinterNow            Name der Quelle, die die aktuelle Story zuerst gedruckt hat
     * @param minutesBehindFirstBySource pro Quelle die historischen Minuten hinter dem Erstdrucker
     *                                   (0 = war selbst Erstdrucker)
     */
    public static Optional<SignalReading> measure(
            String firstPrinterNow, Map<String, double[]> minutesBehindFirstBySource) {
        if (firstPrinterNow == null || minutesBehindFirstBySource == null) {
            return Optional.empty();
        }
        double[] ownHistory = minutesBehindFirstBySource.get(firstPrinterNow);
        if (ownHistory == null || ownHistory.length < MIN_OBSERVATIONS
                || minutesBehindFirstBySource.size() < MIN_SOURCES) {
            return Optional.empty();
        }

        double ownMedian = median(ownHistory);

        List<Double> perSourceMedians = new ArrayList<>();
        for (double[] history : minutesBehindFirstBySource.values()) {
            if (history != null && history.length > 0) {
                perSourceMedians.add(median(history));
            }
        }
        if (perSourceMedians.size() < MIN_SOURCES) {
            return Optional.empty();
        }
        double[] mediansArray = new double[perSourceMedians.size()];
        for (int i = 0; i < mediansArray.length; i++) {
            mediansArray[i] = perSourceMedians.get(i);
        }
        double fieldMedian = median(mediansArray);
        if (fieldMedian <= 0 || !Double.isFinite(fieldMedian)) {
            // Degeneriertes Feld (alle Quellen chronisch Erstdrucker) - kein belastbares Ratio.
            return Optional.empty();
        }

        double value = ownMedian / fieldMedian;

        String ownMedianText = MathKit.fmt(ownMedian, 1);
        String fieldMedianText = MathKit.fmt(fieldMedian, 1);

        String interpretation;
        if (value >= 1.5) {
            interpretation = "EXKLUSIV-VERDACHT: " + firstPrinterNow
                    + " ist chronisch langsam (Median " + ownMedianText
                    + " min hinter dem Erstdrucker, Feld-Median " + fieldMedianText
                    + " min) und druckt diese Story trotzdem zuerst - die Quelle hat mit hoher"
                    + " Wahrscheinlichkeit Eigenmaterial, die Story ist mehr wert als ihre Reichweite.";
        } else if (value > 0.7) {
            interpretation = "Unauffällig: " + firstPrinterNow + " (Median " + ownMedianText
                    + " min) liegt im normalen Latenz-Feld (Feld-Median " + fieldMedianText
                    + " min) - normale Erstdrucker-Reihenfolge ohne Extra-Aussage.";
        } else {
            interpretation = "Der übliche Schnellste war zuerst: " + firstPrinterNow
                    + " (Median " + ownMedianText + " min) ist ohnehin flotter als das Feld (Feld-Median "
                    + fieldMedianText + " min) - kein Zusatzsignal aus der Reihenfolge.";
        }
        if (ownHistory.length < COMFORTABLE_OBSERVATIONS
                || minutesBehindFirstBySource.size() < COMFORTABLE_SOURCES) {
            interpretation += " Vorsicht: dünne Datenlage (wenige Beobachtungen bzw. Quellen)"
                    + " - Befund nur als schwaches Indiz lesen.";
        }

        String formattedValue = MathKit.fmt(value, 2)
                + "x (Langsamkeits-Ratio, >1 = chronisch langsamer als das Feld)";
        String definition = "Verhältnis des Latenz-Medians der aktuellen Erstdrucker-Quelle"
                + " (Minuten hinter dem jeweils schnellsten Drucker) zum Median aller"
                + " Quellen-Mediane - misst, ob eine sonst langsame Quelle diesmal vorn liegt.";

        return Optional.of(new SignalReading(ID, TITLE, value, formattedValue, definition, interpretation));
    }

    private static double median(double[] xs) {
        double[] sorted = xs.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        if (n % 2 == 1) {
            return sorted[n / 2];
        }
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }
}
