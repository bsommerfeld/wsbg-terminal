package de.bsommerfeld.wsbg.terminal.signals.swarm;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Map;
import java.util.Optional;

/**
 * Aufmerksamkeits-Entropie: normierte Shannon-Entropie (Shannon 1948) über die
 * Verteilung der Ticker-Nennungen eines Ticks. 1 bedeutet maximal zersplitterte
 * Aufmerksamkeit, 0 bedeutet alle starren auf ein Papier.
 *
 * <p>Numerik: {@link MathKit#normalizedEntropy(double[])} über alle Ticker mit
 * mindestens einer Nennung (H / ln k, k = Anzahl genannter Ticker). Liegt ein
 * Vor-Tick vor, der dieselben Mindestanforderungen erfüllt, wird zusätzlich das
 * Delta (aktuell - vorher) berechnet - ein schlagartiger Entropie-Abfall ist
 * das eigentliche Frühwarnsignal (Kollaps der Aufmerksamkeitsverteilung als
 * Vorbote von Herdenverhalten, vgl. die Entropie-Kollaps-Diagnostik in der
 * Ökonophysik).
 *
 * <p>Input im Terminal: die Nennungs-Zähler pro aufgelöstem Ticker aus den
 * Subjekt-Clustern eines Ticks (Subject Registry), als Vor-Tick die Zähler des
 * vorherigen Laufs aus dem Session-Snapshot.
 */
public final class AttentionEntropy {

    private static final String ID = "attention-entropy";
    private static final String TITLE = "Aufmerksamkeits-Entropie";
    private static final String DEFINITION =
            "Misst, wie verteilt die Aufmerksamkeit des Subreddits über die Papiere ist"
                    + " (1 = zersplittert, 0 = alle starren auf ein Papier).";

    private static final int MIN_TICKERS = 2;
    private static final int MIN_TOTAL_MENTIONS = 10;
    private static final int THIN_TOTAL_MENTIONS = 30;
    private static final double COLLAPSE_DELTA = -0.25;

    private AttentionEntropy() {
    }

    /**
     * Berechnet die normierte Entropie der Nennungsverteilung; mit Vor-Tick
     * zusätzlich das Delta. Mindestens {@value #MIN_TICKERS} Ticker mit &gt;0
     * Nennungen und Summe &gt;= {@value #MIN_TOTAL_MENTIONS}, sonst
     * {@link Optional#empty()}.
     *
     * @param mentionsByTicker       Nennungen pro Ticker im aktuellen Tick
     * @param previousMentionsOrNull Nennungen des vorherigen Ticks oder null
     */
    public static Optional<SignalReading> measure(
            Map<String, Integer> mentionsByTicker,
            Map<String, Integer> previousMentionsOrNull) {
        double[] current = entropyOrNaN(mentionsByTicker);
        if (Double.isNaN(current[0])) {
            return Optional.empty();
        }
        double value = current[0];
        int totalMentions = (int) current[1];

        Double delta = null;
        if (previousMentionsOrNull != null) {
            double[] previous = entropyOrNaN(previousMentionsOrNull);
            if (!Double.isNaN(previous[0])) {
                delta = value - previous[0];
            }
        }

        String interpretation = interpret(value, delta, totalMentions);
        String formatted = MathKit.fmt(value, 2) + " (Skala 0-1, 1 = maximal zersplittert)";
        if (delta != null) {
            formatted += ", Delta zum Vor-Tick " + fmtSigned(delta);
        }
        return Optional.of(new SignalReading(ID, TITLE, value, formatted, DEFINITION, interpretation));
    }

    /** Rückgabe {normierte Entropie, Summe der Nennungen} oder {NaN, 0} bei zu dünner Lage. */
    private static double[] entropyOrNaN(Map<String, Integer> mentions) {
        if (mentions == null) {
            return new double[]{Double.NaN, 0};
        }
        int positive = 0;
        int total = 0;
        for (Integer count : mentions.values()) {
            if (count != null && count > 0) {
                positive++;
                total += count;
            }
        }
        if (positive < MIN_TICKERS || total < MIN_TOTAL_MENTIONS) {
            return new double[]{Double.NaN, 0};
        }
        double[] weights = new double[positive];
        int i = 0;
        for (Integer count : mentions.values()) {
            if (count != null && count > 0) {
                weights[i++] = count;
            }
        }
        return new double[]{MathKit.normalizedEntropy(weights), total};
    }

    private static String interpret(double value, Double delta, int totalMentions) {
        String band;
        if (delta != null && delta <= COLLAPSE_DELTA) {
            band = "ENTROPIE-KOLLAPS: die Aufmerksamkeit fokussiert sich gerade"
                    + " schlagartig (Delta " + fmtSigned(delta) + " zum Vor-Tick) -"
                    + " Frühwarnsignal für Schwarmbildung, wichtiger als jede Vote-Zahl.";
        } else if (value < 0.4) {
            band = "Konsens/Fixierung auf wenige Papiere, Schwarmpotenzial vorhanden.";
        } else if (value <= 0.8) {
            band = "Die Aufmerksamkeit ist normal verteilt.";
        } else {
            band = "Zersplitterte Aufmerksamkeit über viele Papiere, kein Schwarmpotenzial.";
        }
        if (delta != null && delta > COLLAPSE_DELTA) {
            band += " Delta zum Vor-Tick: " + fmtSigned(delta) + ".";
        }
        if (totalMentions < THIN_TOTAL_MENTIONS) {
            band += " Vorsicht: nur " + totalMentions + " Nennungen insgesamt,"
                    + " die Verteilung ist entsprechend wackelig.";
        }
        return band;
    }

    private static String fmtSigned(double v) {
        return (v >= 0 ? "+" : "") + MathKit.fmt(v, 2);
    }
}
