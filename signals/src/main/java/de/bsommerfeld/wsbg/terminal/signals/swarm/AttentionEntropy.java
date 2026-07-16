package de.bsommerfeld.wsbg.terminal.signals.swarm;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Map;
import java.util.Optional;

/**
 * Attention entropy: normalized Shannon entropy (Shannon 1948) over the
 * distribution of ticker mentions in a tick. 1 means maximally fragmented
 * attention, 0 means everyone is staring at one instrument.
 *
 * <p>Numerics: {@link MathKit#normalizedEntropy(double[])} over all tickers
 * with at least one mention (H / ln k, k = number of mentioned tickers). If a
 * previous tick meets the same minimum requirements, the delta
 * (current - previous) is computed as well - a sudden entropy drop is the
 * actual early-warning signal (collapse of the attention distribution as a
 * precursor of herding, cf. the entropy-collapse diagnostics in econophysics).
 *
 * <p>Terminal input: the mention counts per resolved ticker from the subject
 * clusters of a tick (Subject Registry), with the previous run's counts from
 * the session snapshot as the previous tick.
 */
public final class AttentionEntropy {

    private static final String ID = "attention-entropy";
    private static final String TITLE = "Attention entropy";
    private static final String DEFINITION =
            "Measures how spread out the cage's attention is across instruments"
                    + " (1 = fragmented, 0 = everyone staring at one instrument).";

    private static final int MIN_TICKERS = 2;
    private static final int MIN_TOTAL_MENTIONS = 10;
    private static final int THIN_TOTAL_MENTIONS = 30;
    private static final double COLLAPSE_DELTA = -0.25;

    private AttentionEntropy() {
    }

    /**
     * Computes the normalized entropy of the mention distribution; with a
     * previous tick also the delta. At least {@value #MIN_TICKERS} tickers with
     * &gt;0 mentions and a sum &gt;= {@value #MIN_TOTAL_MENTIONS}, otherwise
     * {@link Optional#empty()}.
     *
     * @param mentionsByTicker       mentions per ticker in the current tick
     * @param previousMentionsOrNull mentions of the previous tick, or null
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
        String formatted = MathKit.fmt(value, 2) + " (scale 0-1, 1 = maximally fragmented)";
        if (delta != null) {
            formatted += ", delta vs previous tick " + fmtSigned(delta);
        }
        return Optional.of(new SignalReading(ID, TITLE, value, formatted, DEFINITION, interpretation));
    }

    /** Returns {normalized entropy, sum of mentions} or {NaN, 0} when the data is too thin. */
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
            band = "ENTROPY COLLAPSE: attention is snapping into focus right now"
                    + " (delta " + fmtSigned(delta) + " vs previous tick) -"
                    + " early warning of swarm formation, more important than any vote count.";
        } else if (value < 0.4) {
            band = "CONSENSUS/FIXATION on few instruments - swarm potential present.";
        } else if (value <= 0.8) {
            band = "Attention is normally distributed.";
        } else {
            band = "FRAGMENTED attention across many instruments - no swarm potential.";
        }
        if (delta != null && delta > COLLAPSE_DELTA) {
            band += " Delta vs previous tick: " + fmtSigned(delta) + ".";
        }
        if (totalMentions < THIN_TOTAL_MENTIONS) {
            band += " Caution: only n=" + totalMentions + " mentions in total -"
                    + " the distribution is correspondingly shaky.";
        }
        return band;
    }

    private static String fmtSigned(double v) {
        return (v >= 0 ? "+" : "") + MathKit.fmt(v, 2);
    }
}
