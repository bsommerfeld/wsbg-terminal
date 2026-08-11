package de.bsommerfeld.wsbg.terminal.signals.flow;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * The only axis that can turn BEFORE the tape does: public attention.
 *
 * <p><b>Method:</b> attention is measured exactly like volume - against the
 * name's own quiet stretch, never a global constant, because a household name
 * is read a thousand times a day while a small cap is read twice. What the
 * axis adds over the volume axis is the DIVERGENCE: attention and volume
 * normally rise and fall together, and the interesting states are the two
 * where they do not.
 *
 * <ul>
 *   <li><b>Attention up, volume still flat</b> - people are reading before they
 *       are trading. This is the earliest warning the house can give, and the
 *       whole reason the axis exists.</li>
 *   <li><b>Attention down, volume still up</b> - the crowd has stopped caring
 *       while the tape is still busy. A wave whose audience left is being
 *       traded out of, not into, and this usually shows before the price
 *       admits it.</li>
 * </ul>
 *
 * <p><b>What it is not:</b> attention is not sentiment and not direction.
 * People read about a company for good and bad reasons in equal measure; the
 * axis measures HOW MANY are looking, never why, and the reading lines say so.
 *
 * <p><b>Terminal inputs:</b> daily article pageviews aligned to the daily
 * volume series - the caller aligns by date, this kernel only compares.
 */
public final class AttentionLead {

    /** Below this many aligned sessions there is no measurement. */
    private static final int MIN_DAYS = 30;
    /** Sessions averaged into the current reading. */
    private static final int RECENT = 3;
    /** Sessions kept clear of the baseline. */
    private static final int RECENT_EXCLUDE = 7;
    /** From this multiple of its own normal attention counts as raised. */
    private static final double RAISED = 1.5;
    /** Below this multiple attention counts as gone quiet. */
    private static final double FADED = 0.8;

    private AttentionLead() {
    }

    /**
     * Compares the attention curve with the volume curve.
     *
     * @param views   daily pageviews, oldest first
     * @param volume  daily volume for the SAME days, oldest first
     * @return the reading, or empty when the series are too short or unaligned
     */
    public static Optional<SignalReading> measure(long[] views, double[] volume) {
        if (views == null || volume == null) return Optional.empty();
        if (views.length != volume.length || views.length < MIN_DAYS) return Optional.empty();
        for (int i = 0; i < views.length; i++) {
            if (views[i] < 0 || !Double.isFinite(volume[i]) || volume[i] < 0) {
                return Optional.empty();
            }
        }
        double attention = ratio(toDoubles(views));
        double traded = ratio(volume);
        if (!Double.isFinite(attention) || !Double.isFinite(traded)) return Optional.empty();

        String interpretation;
        if (attention >= RAISED && traded < RAISED) {
            interpretation = "ATTENTION RUNS AHEAD: people are reading about this name well "
                    + "above its own normal while the tape has not followed. This is the "
                    + "earliest signal in the house - and the one most often followed by "
                    + "nothing at all, so it licenses watching, never a claim.";
        } else if (attention <= FADED && traded >= RAISED) {
            interpretation = "THE AUDIENCE HAS LEFT: the tape is still busy but attention has "
                    + "fallen back below this name's own normal. A wave whose readers are gone "
                    + "is being traded OUT of; this usually shows before the price admits it.";
        } else if (attention >= RAISED) {
            interpretation = "Attention and trading are raised together - the ordinary shape of "
                    + "a live story, and it says nothing beyond what the volume already says.";
        } else {
            interpretation = "Attention sits inside this name's own normal range - whatever is "
                    + "happening here, the public is not watching it yet.";
        }
        return Optional.of(new SignalReading(
                "attention-lead",
                "Public attention against trading",
                attention,
                MathKit.fmt(attention, 1) + "x normal attention against "
                        + MathKit.fmt(traded, 1) + "x normal volume",
                "Daily encyclopedia pageviews for this name against their own quiet stretch, "
                        + "compared with the same measure on trading volume, over "
                        + views.length + " days.",
                interpretation + " Attention is NOT sentiment: it counts how many are looking, "
                        + "never why, and carries no direction."));
    }

    /** Recent mean over the trailing median, the shape the volume axis uses. */
    private static double ratio(double[] xs) {
        int n = xs.length;
        int baselineTo = n - RECENT_EXCLUDE;
        if (baselineTo < MIN_DAYS - RECENT_EXCLUDE) return Double.NaN;
        double[] baseline = new double[baselineTo];
        System.arraycopy(xs, 0, baseline, 0, baselineTo);
        double base = MathKit.median(baseline);
        if (!Double.isFinite(base) || base <= 0) return Double.NaN;
        double[] recent = new double[RECENT];
        System.arraycopy(xs, n - RECENT, recent, 0, RECENT);
        return MathKit.mean(recent) / base;
    }

    private static double[] toDoubles(long[] xs) {
        double[] out = new double[xs.length];
        for (int i = 0; i < xs.length; i++) out[i] = xs[i];
        return out;
    }
}
