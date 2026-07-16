package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Base rate with confidence grade: proportion estimate with a Jeffreys 90%
 * interval and an explicit reliability class.
 *
 * <p>Numerics: rate = successes / n, plus the Jeffreys interval
 * (Beta(s+0.5, n-s+0.5) quantiles; Brown/Cai/DasGupta 2001 recommend it as
 * the standard interval for binomial proportions). Grading: ROBUST at
 * n &gt;= 50 and interval width &lt;= 0.15, INDICATIVE at n &gt;= 15,
 * otherwise ANECDOTAL. The grade prevents confident statistics on thin ice -
 * a base rate without a reliability grade is worthless to dangerous in a
 * prompt.
 *
 * <p>Terminal input: event base rates from the market memory (event label,
 * success count, case count).
 */
public final class BaseRateConfidence {

    private static final String ID = "base-rate-confidence";
    private static final String TITLE = "Base rate with confidence grade";
    private static final String DEFINITION =
            "Measures the historical base rate of an event from the market"
                    + " memory and grades how statistically reliable that rate is"
                    + " (Jeffreys 90% interval).";

    static final double CI_LEVEL = 0.90;
    static final int ROBUST_MIN_N = 50;
    static final double ROBUST_MAX_WIDTH = 0.15;
    static final int INDICATIVE_MIN_N = 15;

    static final String ROBUST = "ROBUST";
    static final String INDICATIVE = "INDICATIVE";
    static final String ANECDOTAL = "ANECDOTAL";

    private BaseRateConfidence() {
    }

    /**
     * Computes base rate, Jeffreys 90% interval and grade.
     * Requires n &gt;= 1 and successes in [0, n], otherwise
     * {@link Optional#empty()}.
     *
     * @param eventLabel label of the event (display only)
     * @param successes  number of hits
     * @param n          number of cases
     */
    public static Optional<SignalReading> measure(String eventLabel, int successes, int n) {
        if (eventLabel == null || n < 1 || successes < 0 || successes > n) {
            return Optional.empty();
        }
        double rate = (double) successes / n;
        double[] ci = MathKit.jeffreysInterval(successes, n, CI_LEVEL);
        String grade = grade(n, ci);

        String formatted = MathKit.fmt(rate * 100, 1) + " % (n=" + n + ", 90% CI "
                + MathKit.fmt(ci[0], 2) + "-" + MathKit.fmt(ci[1], 2) + ", " + grade + ")";
        String interpretation = "Base rate for '" + eventLabel + "': "
                + MathKit.fmt(rate * 100, 1) + " % over n=" + n + " cases, 90% CI "
                + MathKit.fmt(ci[0], 2) + "-" + MathKit.fmt(ci[1], 2)
                + ", grade " + grade + " - " + gradeAdvice(grade);
        return Optional.of(new SignalReading(ID, TITLE, rate, formatted, DEFINITION, interpretation));
    }

    /** Confidence grade from case count and interval width (also reused regime-conditionally). */
    static String grade(int n, double[] ci) {
        if (n >= ROBUST_MIN_N && (ci[1] - ci[0]) <= ROBUST_MAX_WIDTH) {
            return ROBUST;
        }
        if (n >= INDICATIVE_MIN_N) {
            return INDICATIVE;
        }
        return ANECDOTAL;
    }

    /** Handling advice per grade (also reused regime-conditionally). */
    static String gradeAdvice(String grade) {
        return switch (grade) {
            case ROBUST -> "the rate is citable as a prior.";
            case INDICATIVE -> "usable as a tendency, not as proof.";
            default -> "practically worthless, do NOT cite as evidence, treat"
                    + " as hypothesis at most - Caution: extremely thin data.";
        };
    }
}
