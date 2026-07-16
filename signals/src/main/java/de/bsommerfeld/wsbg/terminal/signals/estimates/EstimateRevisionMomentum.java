package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Schätzungs-Revisions-Momentum: mittlere prozentuale Revision der
 * Analysten-Schätzungen zwischen zwei Snapshots, normiert auf 30 Tage.
 *
 * <p>Numerik: pro gemeinsamem Schätzjahr wird die relative Revision
 * (neu - alt) / |alt| * 100 gebildet, darüber der Mittelwert, anschließend
 * lineare Skalierung auf ein 30-Tage-Fenster (Wert * 30 / Tage zwischen den
 * Snapshots). Gemessen wird bewusst NICHT das Niveau der Schätzung (das ist
 * eingepreist), sondern ihre Änderungsrate - Estimate-Revisions-Momentum ist
 * einer der robustesten dokumentierten Kapitalmarkt-Faktoren aus der
 * PEAD-Familie (Post-Earnings-Announcement-Drift, Ball/Brown 1968; Chan,
 * Jegadeesh, Lakonishok 1996 zu Earnings-Revision-Strategien).
 *
 * <p>Input im Terminal: zwei zeitversetzte Consorsbank-KeyFigures-Snapshots
 * derselben Aktie (Schätzjahr zu Konsens-Schätzwert, z.B. EPS je Jahr bis
 * 2029) plus der Tagesabstand der Snapshots.
 */
public final class EstimateRevisionMomentum {

    private static final String ID = "estimate-revision-momentum";
    private static final String TITLE = "Schätzungs-Revisions-Momentum";
    private static final String DEFINITION =
            "Misst, wie stark die Analysten ihre Schätzungen zuletzt nach oben oder"
                    + " unten geschrieben haben (mittlere Revision in % je 30 Tage) -"
                    + " nicht das Niveau, sondern die Änderungsrate der Story.";

    private static final double MIN_DAYS = 7;
    private static final double UP_THRESHOLD = 1.0;
    private static final double DOWN_THRESHOLD = -1.0;
    private static final int THIN_YEARS = 2;

    private EstimateRevisionMomentum() {
    }

    /**
     * Berechnet die auf 30 Tage skalierte mittlere Schätzungs-Revision.
     * Mindestens 1 gemeinsames Schätzjahr mit altem Wert != 0 und ein
     * Snapshot-Abstand von mindestens 7 Tagen, sonst {@link Optional#empty()}.
     *
     * @param oldEstimatesByYear   Schätzwerte je Jahr aus dem älteren Snapshot
     * @param newEstimatesByYear   Schätzwerte je Jahr aus dem neueren Snapshot
     * @param daysBetweenSnapshots Tage zwischen den beiden Snapshots
     */
    public static Optional<SignalReading> measure(
            Map<Integer, Double> oldEstimatesByYear,
            Map<Integer, Double> newEstimatesByYear,
            double daysBetweenSnapshots) {
        if (oldEstimatesByYear == null || newEstimatesByYear == null
                || !Double.isFinite(daysBetweenSnapshots) || daysBetweenSnapshots < MIN_DAYS) {
            return Optional.empty();
        }
        double sum = 0;
        int years = 0;
        for (Map.Entry<Integer, Double> entry : new TreeMap<>(oldEstimatesByYear).entrySet()) {
            Double oldValue = entry.getValue();
            Double newValue = newEstimatesByYear.get(entry.getKey());
            if (oldValue == null || newValue == null
                    || !Double.isFinite(oldValue) || !Double.isFinite(newValue)
                    || oldValue == 0) {
                continue;
            }
            sum += (newValue - oldValue) / Math.abs(oldValue) * 100.0;
            years++;
        }
        if (years < 1) {
            return Optional.empty();
        }
        double value = (sum / years) * (30.0 / daysBetweenSnapshots);

        String formatted = fmtSigned(value) + " %/30 Tage (über " + years
                + " gemeinsame(s) Schätzjahr(e), Snapshot-Abstand "
                + MathKit.fmt(daysBetweenSnapshots, 0) + " Tage)";
        return Optional.of(new SignalReading(
                ID, TITLE, value, formatted, DEFINITION, interpret(value, years)));
    }

    private static String interpret(double value, int years) {
        String band;
        if (value >= UP_THRESHOLD) {
            band = "AUFWÄRTS-MOMENTUM: die Analysten schreiben die Story gerade hoch ("
                    + fmtSigned(value) + " % je 30 Tage über " + years
                    + " Schätzjahr(e)) - historisch setzt sich so eine Revisionswelle"
                    + " im Kurs über Wochen fort.";
        } else if (value <= DOWN_THRESHOLD) {
            band = "ABWÄRTS-MOMENTUM: die Story wird gerade heruntergeschrieben ("
                    + fmtSigned(value) + " % je 30 Tage über " + years
                    + " Schätzjahr(e)) - historisch driftet der Kurs solchen"
                    + " Abwärtsrevisionen über Wochen hinterher.";
        } else {
            band = "Keine nennenswerte Revision (" + fmtSigned(value)
                    + " % je 30 Tage über " + years + " Schätzjahr(e)) - die"
                    + " Analysten-Story steht still, das Signal ist neutral.";
        }
        if (years < THIN_YEARS) {
            band += " Vorsicht: nur " + years + " gemeinsames Schätzjahr in beiden"
                    + " Snapshots, der Mittelwert steht auf dünnem Eis.";
        }
        return band;
    }

    private static String fmtSigned(double v) {
        return (v >= 0 ? "+" : "") + MathKit.fmt(v, 2);
    }
}
