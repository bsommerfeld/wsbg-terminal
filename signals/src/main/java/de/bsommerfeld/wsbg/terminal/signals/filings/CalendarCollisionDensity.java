package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Calendar collision density: measures how overloaded today's event calendar
 * is compared to its own history.
 *
 * <p><b>Method:</b> today's event weight is ranked as an empirical percentile
 * against the distribution of historical daily weights (mid-rank on ties).
 * Literature anchor: limited attention / distraction by competing news - on
 * days with many simultaneous events prices demonstrably react with a lag
 * (Hirshleifer/Lim/Teoh 2009, "Driven to Distraction").
 *
 * <p><b>Terminal inputs:</b> weighted daily event load from the calendar
 * briefing (earnings dates, ECB/Fed decisions, macro data, annual general
 * meetings) plus filing volume from the ad-hoc feeds (fn-adhoc, EQS).
 */
public final class CalendarCollisionDensity {

    private static final int MIN_HISTORY = 30;
    private static final int THIN_HISTORY = 45;

    private CalendarCollisionDensity() {
    }

    /**
     * @param historicalDailyEventWeights historical daily weights
     *                                    (at least {@value #MIN_HISTORY} days)
     * @param todayWeight                 today's event weight
     * @return reading, or empty on too thin a history
     */
    public static Optional<SignalReading> measure(double[] historicalDailyEventWeights, double todayWeight) {
        if (historicalDailyEventWeights == null || historicalDailyEventWeights.length < MIN_HISTORY) {
            return Optional.empty();
        }
        int n = historicalDailyEventWeights.length;
        double percentile = MathKit.empiricalPercentile(todayWeight, historicalDailyEventWeights);

        String formatted = "Percentile " + MathKit.fmt(percentile, 2)
                + " (today's weight " + MathKit.fmt(todayWeight, 1) + ", n=" + n + " days)";

        String interpretation;
        if (percentile >= 0.9) {
            interpretation = "COLLISION DAY: small filings price in with a lag today"
                    + " - today's unnoticed small-cap filing is the best candidate for not-yet-digested"
                    + " information, follow up deliberately.";
        } else if (percentile >= 0.5) {
            interpretation = "Busy day: a mild distraction effect is possible - individual filings"
                    + " may be processed with some lag.";
        } else {
            interpretation = "Quiet calendar: filings are processed immediately - whatever does not"
                    + " react today probably genuinely failed to convince the market.";
        }
        if (n < THIN_HISTORY) {
            interpretation += " Caution: only n=" + n + " historical days"
                    + " - thin data, read the percentile with restraint.";
        }

        return Optional.of(new SignalReading(
                "calendar-collision-density",
                "Calendar collision density",
                percentile,
                formatted,
                "Measures how overloaded today's event calendar is against its own history - on"
                        + " collision days the market demonstrably processes information worse"
                        + " (limited attention).",
                interpretation));
    }
}
