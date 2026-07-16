package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Regime-conditional base rate: the same event base rate, but estimated
 * separately per market regime and reported for the CURRENT regime only.
 *
 * <p>Numerics: per regime the rate is estimated with a Jeffreys 90% interval
 * (Beta(s+0.5, n-s+0.5) quantiles, Brown/Cai/DasGupta 2001; the same logic as
 * in {@link BaseRateConfidence}, whose grading is reused here). The signal
 * value is the rate in the current regime; the rates of the other regimes
 * are reported as contrast. Background is the regime-switching literature
 * (Hamilton 1989): conditional probabilities switch with the regime, an
 * unconditional base rate averages regimes together that never hold in the
 * market at the same time.
 *
 * <p>Terminal input: event base rates from the market memory, bucketed by
 * the HMM regime label at event time, plus the current HMM regime.
 */
public final class RegimeConditionalBaseRate {

    private static final String ID = "regime-conditional-base-rate";
    private static final String TITLE = "Regime-conditional base rate";
    private static final String DEFINITION =
            "Measures the base rate of an event separately per market regime and"
                    + " reports the rate of the current regime - the same base rate"
                    + " does not hold across regimes.";

    private RegimeConditionalBaseRate() {
    }

    /**
     * Computes the base rate in the current regime with a Jeffreys 90%
     * interval and contrasts the remaining regimes. The current regime must
     * be present in the map with n &gt;= 1 and valid counters, otherwise
     * {@link Optional#empty()}.
     *
     * @param eventLabel            label of the event (display only)
     * @param successesAndNByRegime per regime {hits, case count}
     * @param currentRegime         label of the current HMM regime
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
            contrast.append(" In regime '").append(entry.getKey()).append("' by contrast ")
                    .append(MathKit.fmt((double) s / m * 100, 1))
                    .append(" % (n=").append(m).append(").");
        }

        String formatted = MathKit.fmt(rate * 100, 1) + " % in regime '" + currentRegime
                + "' (n=" + n + ", 90% CI " + MathKit.fmt(ci[0], 2) + "-"
                + MathKit.fmt(ci[1], 2) + ", " + grade + ")";
        String interpretation = "Base rate for '" + eventLabel + "' in the current regime '"
                + currentRegime + "': " + MathKit.fmt(rate * 100, 1) + " % over n=" + n
                + " cases, 90% CI " + MathKit.fmt(ci[0], 2) + "-" + MathKit.fmt(ci[1], 2)
                + ", grade " + grade + " - " + BaseRateConfidence.gradeAdvice(grade)
                + contrast
                + " Core rule: the same base rate does NOT hold across regimes -"
                + " use only the number of the current regime.";
        return Optional.of(new SignalReading(ID, TITLE, rate, formatted, DEFINITION, interpretation));
    }

    private static boolean valid(int[] bucket) {
        return bucket != null && bucket.length >= 2
                && bucket[1] >= 1 && bucket[0] >= 0 && bucket[0] <= bucket[1];
    }
}
