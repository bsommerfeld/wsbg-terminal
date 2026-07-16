package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Estimate revision momentum: mean percentage revision of analyst estimates
 * between two snapshots, normalized to 30 days.
 *
 * <p>Numerics: per common estimate year the relative revision
 * (new - old) / |old| * 100 is computed, then averaged, then linearly scaled
 * to a 30-day window (value * 30 / days between snapshots). Deliberately
 * measured is NOT the level of the estimate (that is priced in) but its rate
 * of change - estimate revision momentum is one of the most robust documented
 * capital-market factors of the PEAD family (post-earnings-announcement
 * drift, Ball/Brown 1968; Chan, Jegadeesh, Lakonishok 1996 on earnings
 * revision strategies).
 *
 * <p>Terminal input: two time-shifted Consorsbank KeyFigures snapshots of the
 * same stock (estimate year to consensus estimate, e.g. EPS per year up to
 * 2029) plus the day gap between the snapshots.
 */
public final class EstimateRevisionMomentum {

    private static final String ID = "estimate-revision-momentum";
    private static final String TITLE = "Estimate revision momentum";
    private static final String DEFINITION =
            "Measures how strongly analysts have recently written their estimates"
                    + " up or down (mean revision in % per 30 days) - not the level,"
                    + " but the rate of change of the story.";

    private static final double MIN_DAYS = 7;
    private static final double UP_THRESHOLD = 1.0;
    private static final double DOWN_THRESHOLD = -1.0;
    private static final int THIN_YEARS = 2;

    private EstimateRevisionMomentum() {
    }

    /**
     * Computes the mean estimate revision scaled to 30 days. Requires at
     * least 1 common estimate year with old value != 0 and a snapshot gap of
     * at least 7 days, otherwise {@link Optional#empty()}.
     *
     * @param oldEstimatesByYear   estimates per year from the older snapshot
     * @param newEstimatesByYear   estimates per year from the newer snapshot
     * @param daysBetweenSnapshots days between the two snapshots
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

        String formatted = fmtSigned(value) + " %/30 days (over " + years
                + " common estimate year(s), snapshot gap "
                + MathKit.fmt(daysBetweenSnapshots, 0) + " days)";
        return Optional.of(new SignalReading(
                ID, TITLE, value, formatted, DEFINITION, interpret(value, years)));
    }

    private static String interpret(double value, int years) {
        String band;
        if (value >= UP_THRESHOLD) {
            band = "UPWARD MOMENTUM: analysts are currently writing the story up ("
                    + fmtSigned(value) + " % per 30 days over " + years
                    + " estimate year(s)) - historically such a revision wave"
                    + " keeps feeding into the price for weeks.";
        } else if (value <= DOWN_THRESHOLD) {
            band = "DOWNWARD MOMENTUM: the story is currently being written down ("
                    + fmtSigned(value) + " % per 30 days over " + years
                    + " estimate year(s)) - historically the price drifts after"
                    + " such downward revisions for weeks.";
        } else {
            band = "No notable revision (" + fmtSigned(value)
                    + " % per 30 days over " + years + " estimate year(s)) - the"
                    + " analyst story stands still, the signal is neutral.";
        }
        if (years < THIN_YEARS) {
            band += " Caution: only " + years + " common estimate year in both"
                    + " snapshots, the mean stands on thin ice.";
        }
        return band;
    }

    private static String fmtSigned(double v) {
        return (v >= 0 ? "+" : "") + MathKit.fmt(v, 2);
    }
}
