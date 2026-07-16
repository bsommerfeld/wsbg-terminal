package de.bsommerfeld.wsbg.terminal.signals.swarm;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The house's own Fear &amp; Greed - for the CROWD, not the market: a 0-100
 * composite of behavioral components (direction of the wire lines, heat of
 * the hot-sentiment share, attention focus), built CNN-style as an average of
 * per-component scores. Unlike CNN's fixed mappings every component is
 * normalized as a PERCENTILE against its own history (the base-rate
 * philosophy applied to the index itself): 80 does not mean "greedy by some
 * curated constant" but "greedier than 80 % of this cage's own record".
 *
 * <p>Inputs come from the permanent headline archive (daily direction/heat
 * tallies, daily attention entropy) - the adapter supplies each component's
 * current value together with its history; the kernel never sees a source.
 */
public final class CageMoodIndex {

    /** A component needs at least this much own history to be percentile-scored. */
    public static final int MIN_HISTORY = 10;
    /** The composite needs at least this many scored components. */
    public static final int MIN_COMPONENTS = 2;

    /**
     * One behavioral component: today's raw value plus the raw-value history
     * it is ranked against (same unit, chronological or not - only the
     * distribution matters).
     */
    public record Component(String label, double value, double[] history) {
    }

    private CageMoodIndex() {
    }

    /**
     * The pure scoring rule, shared with series builders so a chart point and
     * the live reading can never disagree: percentile (0-100) of the value
     * within its history (mid-rank), averaged over all scorable components.
     * Empty when fewer than {@value #MIN_COMPONENTS} components carry at least
     * {@value #MIN_HISTORY} history points.
     */
    public static Optional<Double> composite(List<Component> components) {
        if (components == null) return Optional.empty();
        double sum = 0;
        int n = 0;
        for (Component c : components) {
            if (c == null || c.history() == null || c.history().length < MIN_HISTORY
                    || !Double.isFinite(c.value())) {
                continue;
            }
            sum += MathKit.empiricalPercentile(c.value(), c.history()) * 100.0;
            n++;
        }
        return n >= MIN_COMPONENTS ? Optional.of(sum / n) : Optional.empty();
    }

    /** The full reading: composite value, zone band and the per-component breakdown. */
    public static Optional<SignalReading> measure(List<Component> components) {
        Optional<Double> composite = composite(components);
        if (composite.isEmpty()) return Optional.empty();
        double value = composite.get();

        StringBuilder breakdown = new StringBuilder();
        int scored = 0;
        int minHistory = Integer.MAX_VALUE;
        for (Component c : components) {
            if (c == null || c.history() == null || c.history().length < MIN_HISTORY
                    || !Double.isFinite(c.value())) {
                continue;
            }
            if (breakdown.length() > 0) breakdown.append(", ");
            breakdown.append(c.label()).append(' ')
                    .append(MathKit.fmt(MathKit.empiricalPercentile(c.value(), c.history()) * 100, 0));
            scored++;
            minHistory = Math.min(minHistory, c.history().length);
        }

        String zone;
        if (value >= 75) {
            zone = "EXTREME GREED by the cage's own record - the crowd is hotter than almost "
                    + "any day in its history; crowd extremes are contrarian territory, treat "
                    + "fresh euphoria as risk, not as confirmation.";
        } else if (value >= 55) {
            zone = "GREED side: the cage runs hotter than its usual self - lean skeptical on "
                    + "crowd-confirmed stories, they are already crowded.";
        } else if (value > 45) {
            zone = "NEUTRAL: the cage sits inside its normal mood band - no crowd edge either way.";
        } else if (value > 25) {
            zone = "FEAR side: the cage is colder than its usual self - crowd pessimism, "
                    + "single contrarian picks deserve a closer look.";
        } else {
            zone = "EXTREME FEAR by the cage's own record - capitulation territory; historically "
                    + "the crowd's darkest days cluster near turning points, weigh against the news.";
        }
        String caution = minHistory < 20
                ? " Caution: only " + minHistory + " days of component history - the "
                + "percentile scale is still coarse."
                : "";

        return Optional.of(new SignalReading(
                "cage-mood-index",
                "Cage mood index (house Fear & Greed)",
                value,
                MathKit.fmt(value, 0) + " (0-100, percentile-scored vs own record; "
                        + scored + " components)",
                "The cage's own Fear & Greed: behavioral components (direction, heat, focus) "
                        + "each ranked against their own history, averaged CNN-style.",
                zone + " Components: " + breakdown + "." + caution));
    }

    /**
     * The three standard components from one day's wire tallies — kept here so
     * every caller (live reading, series builder, tests) derives them with the
     * SAME formulas.
     *
     * @param bull        directional bull lines of the day (incl. hot sentiments)
     * @param bear        directional bear lines of the day (incl. capitulation)
     * @param hot         FOMO/squeeze-type lines of the day
     * @param capitulation capitulation lines of the day
     * @param normalizedEntropyOrNaN the day's attention entropy 0-1, NaN when unmeasurable
     * @return raw component values {direction, heat, focus} with NaN where unavailable
     */
    public static double[] rawComponents(int bull, int bear, int hot, int capitulation,
            double normalizedEntropyOrNaN) {
        int directional = bull + bear;
        double direction = directional > 0 ? 100.0 * bull / directional : Double.NaN;
        double heat = directional > 0
                ? 50.0 + 50.0 * (hot - capitulation) / directional : Double.NaN;
        double focus = Double.isNaN(normalizedEntropyOrNaN)
                ? Double.NaN : (1.0 - normalizedEntropyOrNaN) * 100.0;
        return new double[]{direction, heat, focus};
    }

    /** Labels matching {@link #rawComponents} by index. */
    public static List<String> componentLabels() {
        List<String> labels = new ArrayList<>(3);
        labels.add("direction");
        labels.add("heat");
        labels.add("focus");
        return labels;
    }
}
