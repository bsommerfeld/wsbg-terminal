package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Basisrate mit Belastbarkeits-Einstufung: Anteilsschätzung mit
 * Jeffreys-90%-Intervall und expliziter Aussagekraft-Klasse.
 *
 * <p>Numerik: Rate = Erfolge / n, dazu das Jeffreys-Intervall
 * (Beta(s+0.5, n-s+0.5)-Quantile; Brown/Cai/DasGupta 2001 empfehlen es als
 * Standard-Intervall für Binomial-Anteile). Einstufung: BELASTBAR bei
 * n &gt;= 50 und Intervallbreite &lt;= 0.15, INDIKATIV bei n &gt;= 15,
 * sonst ANEKDOTISCH. Die Einstufung verhindert selbstbewusste Statistik auf
 * dünnem Eis - eine Basisrate ohne Belastbarkeits-Angabe ist im Prompt
 * wertlos bis gefährlich.
 *
 * <p>Input im Terminal: Event-Basisraten aus dem Markt-Gedächtnis
 * (Ereignis-Label, Trefferzahl, Fallzahl).
 */
public final class BaseRateConfidence {

    private static final String ID = "base-rate-confidence";
    private static final String TITLE = "Basisrate mit Belastbarkeits-Einstufung";
    private static final String DEFINITION =
            "Misst die historische Basisrate eines Ereignisses aus dem"
                    + " Markt-Gedächtnis und stuft ein, wie statistisch belastbar"
                    + " diese Rate ist (Jeffreys-90%-Intervall).";

    static final double CI_LEVEL = 0.90;
    static final int ROBUST_MIN_N = 50;
    static final double ROBUST_MAX_WIDTH = 0.15;
    static final int INDICATIVE_MIN_N = 15;

    static final String ROBUST = "BELASTBAR";
    static final String INDICATIVE = "INDIKATIV";
    static final String ANECDOTAL = "ANEKDOTISCH";

    private BaseRateConfidence() {
    }

    /**
     * Berechnet Basisrate, Jeffreys-90%-Intervall und Einstufung.
     * n &gt;= 1 und Erfolge in [0, n], sonst {@link Optional#empty()}.
     *
     * @param eventLabel Bezeichnung des Ereignisses (nur zur Anzeige)
     * @param successes  Zahl der Treffer
     * @param n          Zahl der Fälle
     */
    public static Optional<SignalReading> measure(String eventLabel, int successes, int n) {
        if (eventLabel == null || n < 1 || successes < 0 || successes > n) {
            return Optional.empty();
        }
        double rate = (double) successes / n;
        double[] ci = MathKit.jeffreysInterval(successes, n, CI_LEVEL);
        String grade = grade(n, ci);

        String formatted = MathKit.fmt(rate * 100, 1) + " % (n=" + n + ", 90%-CI "
                + MathKit.fmt(ci[0], 2) + "-" + MathKit.fmt(ci[1], 2) + ", " + grade + ")";
        String interpretation = "Basisrate für '" + eventLabel + "': "
                + MathKit.fmt(rate * 100, 1) + " % bei n=" + n + " Fällen, 90%-CI "
                + MathKit.fmt(ci[0], 2) + "-" + MathKit.fmt(ci[1], 2)
                + ", Einstufung " + grade + " - " + gradeAdvice(grade);
        return Optional.of(new SignalReading(ID, TITLE, rate, formatted, DEFINITION, interpretation));
    }

    /** Belastbarkeits-Einstufung aus Fallzahl und Intervallbreite (wird auch regime-konditional wiederverwendet). */
    static String grade(int n, double[] ci) {
        if (n >= ROBUST_MIN_N && (ci[1] - ci[0]) <= ROBUST_MAX_WIDTH) {
            return ROBUST;
        }
        if (n >= INDICATIVE_MIN_N) {
            return INDICATIVE;
        }
        return ANECDOTAL;
    }

    /** Handlungsanweisung je Einstufung (wird auch regime-konditional wiederverwendet). */
    static String gradeAdvice(String grade) {
        return switch (grade) {
            case ROBUST -> "die Rate ist als Prior zitierfähig.";
            case INDICATIVE -> "als Tendenz nutzbar, nicht als Beweis.";
            default -> "praktisch wertlos, NICHT als Beleg zitieren, höchstens"
                    + " als Hypothese behandeln - Vorsicht, extrem dünne Datenlage.";
        };
    }
}
