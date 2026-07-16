package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Regime-konditionale Basisrate: dieselbe Ereignis-Basisrate, aber getrennt
 * nach Marktregime geschätzt und nur für das AKTUELLE Regime ausgegeben.
 *
 * <p>Numerik: pro Regime wird die Rate mit Jeffreys-90%-Intervall geschätzt
 * (Beta(s+0.5, n-s+0.5)-Quantile, Brown/Cai/DasGupta 2001; dieselbe Logik
 * wie in {@link BaseRateConfidence}, deren Einstufung hier wiederverwendet
 * wird). Der Signalwert ist die Rate im aktuellen Regime; die Raten der
 * anderen Regime werden kontrastierend mit ausgegeben. Hintergrund ist die
 * Regime-Switching-Literatur (Hamilton 1989): bedingte Wahrscheinlichkeiten
 * wechseln mit dem Regime, eine unkonditionale Basisrate mittelt Regime
 * zusammen, die am Markt nie gleichzeitig gelten.
 *
 * <p>Input im Terminal: Event-Basisraten aus dem Markt-Gedächtnis, nach dem
 * HMM-Regime-Label zum Ereignis-Zeitpunkt gebucketet, plus das aktuelle
 * HMM-Regime.
 */
public final class RegimeConditionalBaseRate {

    private static final String ID = "regime-conditional-base-rate";
    private static final String TITLE = "Regime-konditionale Basisrate";
    private static final String DEFINITION =
            "Misst die Basisrate eines Ereignisses getrennt nach Marktregime und"
                    + " gibt die Rate des aktuellen Regimes aus - dieselbe Basisrate"
                    + " gilt nicht regimeübergreifend.";

    private RegimeConditionalBaseRate() {
    }

    /**
     * Berechnet die Basisrate im aktuellen Regime mit Jeffreys-90%-Intervall
     * und kontrastiert die übrigen Regime. Das aktuelle Regime muss in der
     * Map liegen und dort n &gt;= 1 mit gültigen Zählern haben, sonst
     * {@link Optional#empty()}.
     *
     * @param eventLabel            Bezeichnung des Ereignisses (nur zur Anzeige)
     * @param successesAndNByRegime je Regime {Treffer, Fallzahl}
     * @param currentRegime         Label des aktuellen HMM-Regimes
     */
    public static Optional<SignalReading> measure(
            String eventLabel,
            Map<String, int[]> successesAndNByRegime,
            String currentRegime) {
        if (eventLabel == null || successesAndNByRegime == null || currentRegime == null) {
            return Optional.empty();
        }
        int[] current = successesAndNByRegime.get(currentRegime);
        if (!valid(current)) {
            return Optional.empty();
        }
        int successes = current[0];
        int n = current[1];
        double rate = (double) successes / n;
        double[] ci = MathKit.jeffreysInterval(successes, n, BaseRateConfidence.CI_LEVEL);
        String grade = BaseRateConfidence.grade(n, ci);

        StringBuilder contrast = new StringBuilder();
        for (Map.Entry<String, int[]> entry : new TreeMap<>(successesAndNByRegime).entrySet()) {
            if (entry.getKey().equals(currentRegime) || !valid(entry.getValue())) {
                continue;
            }
            int s = entry.getValue()[0];
            int m = entry.getValue()[1];
            contrast.append(" Im Regime '").append(entry.getKey()).append("' dagegen ")
                    .append(MathKit.fmt((double) s / m * 100, 1))
                    .append(" % (n=").append(m).append(").");
        }

        String formatted = MathKit.fmt(rate * 100, 1) + " % im Regime '" + currentRegime
                + "' (n=" + n + ", 90%-CI " + MathKit.fmt(ci[0], 2) + "-"
                + MathKit.fmt(ci[1], 2) + ", " + grade + ")";
        String interpretation = "Basisrate für '" + eventLabel + "' im aktuellen Regime '"
                + currentRegime + "': " + MathKit.fmt(rate * 100, 1) + " % bei n=" + n
                + " Fällen, 90%-CI " + MathKit.fmt(ci[0], 2) + "-" + MathKit.fmt(ci[1], 2)
                + ", Einstufung " + grade + " - " + BaseRateConfidence.gradeAdvice(grade)
                + contrast
                + " Kernsatz: dieselbe Basisrate gilt NICHT regimeübergreifend -"
                + " nur die Zahl des aktuellen Regimes verwenden.";
        return Optional.of(new SignalReading(ID, TITLE, rate, formatted, DEFINITION, interpretation));
    }

    private static boolean valid(int[] bucket) {
        return bucket != null && bucket.length >= 2
                && bucket[1] >= 1 && bucket[0] >= 0 && bucket[0] <= bucket[1];
    }
}
