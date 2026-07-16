package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.List;
import java.util.Optional;

/**
 * Bedingte Trefferquote: prüft ein beliebiges Signal darauf, ob es unter
 * hoher Retail-Aufmerksamkeit noch funktioniert.
 *
 * <p>Numerik: die Trials werden in zwei Buckets geteilt (hohe vs. niedrige
 * Aufmerksamkeit), pro Bucket wird die Trefferquote mit Wilson-90%-Intervall
 * (Wilson 1927, z = 1.645) geschätzt; der Signalwert ist die Differenz
 * rateHoch - rateNiedrig. Hintergrund ist die Crowding-Literatur (McLean/
 * Pontiff 2016: publizierte Anomalien verlieren nach Veröffentlichung
 * deutlich an Rendite) - öffentliche, gehypte Signale werden durch die
 * Aufmerksamkeit selbst entwertet. Überlappen die beiden Intervalle, wird
 * ausdrücklich gesagt, dass der Unterschied statistisch nicht gesichert ist.
 *
 * <p>Input im Terminal: historische Signal-Auslösungen aus dem
 * Markt-Gedächtnis (Erfolg = Ereignis-Definition erfüllt), das
 * Aufmerksamkeits-Flag aus dem eigenen Reddit-Barometer bzw. den
 * Nennungs-Zählern der Subject Registry zum Auslöse-Zeitpunkt.
 */
public final class ConditionalHitRate {

    private static final String ID = "conditional-hit-rate";
    private static final String TITLE = "Bedingte Trefferquote (Crowding-Prüfstand)";
    private static final String DEFINITION =
            "Misst, ob ein Signal unter hoher Retail-Aufmerksamkeit noch trifft"
                    + " (Trefferquote bei hoher minus bei niedriger Aufmerksamkeit) -"
                    + " Crowding entwertet öffentliche Signale.";

    private static final int MIN_PER_BUCKET = 10;
    private static final int THIN_PER_BUCKET = 30;
    private static final double Z_90 = 1.645;
    private static final double DEVALUED_THRESHOLD = -0.15;
    private static final double AMPLIFIED_THRESHOLD = 0.15;

    /** Ein einzelner historischer Signal-Fall: Treffer ja/nein unter hoher/niedriger Aufmerksamkeit. */
    public record Trial(boolean success, boolean highAttention) {
    }

    private ConditionalHitRate() {
    }

    /**
     * Berechnet die Trefferquoten-Differenz zwischen hoher und niedriger
     * Aufmerksamkeit. Mindestens {@value #MIN_PER_BUCKET} Trials in JEDEM
     * Bucket, sonst {@link Optional#empty()}.
     *
     * @param signalLabel Bezeichnung des geprüften Signals (nur zur Anzeige)
     * @param trials      historische Fälle des Signals
     */
    public static Optional<SignalReading> measure(String signalLabel, List<Trial> trials) {
        if (signalLabel == null || trials == null) {
            return Optional.empty();
        }
        int nHigh = 0, sHigh = 0, nLow = 0, sLow = 0;
        for (Trial trial : trials) {
            if (trial == null) {
                continue;
            }
            if (trial.highAttention()) {
                nHigh++;
                if (trial.success()) sHigh++;
            } else {
                nLow++;
                if (trial.success()) sLow++;
            }
        }
        if (nHigh < MIN_PER_BUCKET || nLow < MIN_PER_BUCKET) {
            return Optional.empty();
        }
        double rateHigh = (double) sHigh / nHigh;
        double rateLow = (double) sLow / nLow;
        double[] ciHigh = MathKit.wilsonInterval(sHigh, nHigh, Z_90);
        double[] ciLow = MathKit.wilsonInterval(sLow, nLow, Z_90);
        double value = rateHigh - rateLow;

        String formatted = fmtSigned(value * 100) + " Prozentpunkte Trefferquoten-Differenz"
                + " (hohe Aufmerksamkeit " + MathKit.fmt(rateHigh * 100, 0)
                + " %, niedrige " + MathKit.fmt(rateLow * 100, 0) + " %)";
        return Optional.of(new SignalReading(ID, TITLE, value, formatted, DEFINITION,
                interpret(signalLabel, value, rateHigh, sHigh, nHigh, ciHigh,
                        rateLow, sLow, nLow, ciLow)));
    }

    private static String interpret(
            String signalLabel, double value,
            double rateHigh, int sHigh, int nHigh, double[] ciHigh,
            double rateLow, int sLow, int nLow, double[] ciLow) {
        String buckets = " Signal '" + signalLabel + "': Trefferquote bei hoher"
                + " Aufmerksamkeit " + bucket(rateHigh, nHigh, ciHigh)
                + ", bei niedriger " + bucket(rateLow, nLow, ciLow) + ".";
        String band;
        if (value <= DEVALUED_THRESHOLD) {
            band = "ENTWERTET UNTER AUFMERKSAMKEIT: bei gehypten Papieren"
                    + " funktioniert das Signal schlechter bis invers - dort den"
                    + " kontraeren Nutzen prüfen, statt ihm blind zu folgen.";
        } else if (value >= AMPLIFIED_THRESHOLD) {
            band = "Das Signal verstärkt sich unter Aufmerksamkeit - es trifft"
                    + " bei gehypten Papieren besser als bei unbeachteten.";
        } else {
            band = "Kein belastbarer Unterschied zwischen hoher und niedriger"
                    + " Aufmerksamkeit - das Signal verhält sich crowding-neutral.";
        }
        band += buckets;
        if (overlaps(ciHigh, ciLow)) {
            band += " Die beiden Intervalle überlappen deutlich - der Unterschied"
                    + " ist statistisch nicht gesichert.";
        }
        if (nHigh < THIN_PER_BUCKET || nLow < THIN_PER_BUCKET) {
            band += " Vorsicht: kleine Buckets (n=" + nHigh + " bzw. n=" + nLow
                    + "), die Quoten sind entsprechend wackelig.";
        }
        return band;
    }

    private static String bucket(double rate, int n, double[] ci) {
        return MathKit.fmt(rate * 100, 0) + " % (n=" + n + ", 90%-CI "
                + MathKit.fmt(ci[0], 2) + "-" + MathKit.fmt(ci[1], 2) + ")";
    }

    private static boolean overlaps(double[] a, double[] b) {
        return Math.max(a[0], b[0]) <= Math.min(a[1], b[1]);
    }

    private static String fmtSigned(double v) {
        return (v >= 0 ? "+" : "") + MathKit.fmt(v, 1);
    }
}
