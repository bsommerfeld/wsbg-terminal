package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;

/**
 * Ad-hoc timing prior: disclosure-timing forensics against an issuer's own
 * filing history.
 *
 * <p><b>Method:</b> historical mandatory filings are sorted into three
 * time-window buckets (FRIDAY NIGHT, AFTER HOURS, TRADING HOURS) and per
 * bucket the Laplace-smoothed conditional probability
 * P(negative | bucket) = (negative + 1) / (n + 2) is estimated - a
 * Beta(1,1) posterior mean. The value is the posterior for the bucket of the
 * current filing. Literature anchor: the "Friday night dump" and strategic
 * disclosure timing (DellaVigna/Pollet 2009, "Investor Inattention and Friday
 * Earnings Announcements"; Niessner 2015).
 *
 * <p><b>Terminal inputs:</b> timestamps and tonality of historical
 * ad-hoc/mandatory filings from the ad-hoc feeds (fn-adhoc, EQS) and BaFin
 * publications; the tonality classification (negative yes/no) comes from the
 * editorial pipeline.
 */
public final class AdHocTimingPrior {

    /** A historical mandatory filing: weekday, hour, and whether it was negative. */
    public record TimedFiling(DayOfWeek day, int hourOfDay, boolean negative) {
    }

    private static final int MIN_HISTORY = 30;
    private static final int THIN_BUCKET = 10;

    private AdHocTimingPrior() {
    }

    /**
     * Computes the timing prior for a filing on the given weekday at the given
     * hour against the issuer's own filing history.
     *
     * @param history historical filings (at least {@value #MIN_HISTORY})
     * @param day     weekday of the current filing
     * @param hour    hour of the current filing (0-23)
     * @return reading, or empty on too thin a history
     */
    public static Optional<SignalReading> measure(List<TimedFiling> history, DayOfWeek day, int hour) {
        if (history == null || history.size() < MIN_HISTORY) {
            return Optional.empty();
        }
        String bucket = bucketOf(day, hour);
        int n = 0;
        int neg = 0;
        for (TimedFiling f : history) {
            if (bucket.equals(bucketOf(f.day(), f.hourOfDay()))) {
                n++;
                if (f.negative()) neg++;
            }
        }
        double posterior = (neg + 1.0) / (n + 2.0);
        double[] ci = MathKit.jeffreysInterval(neg, n, 0.90);

        String formatted = "P(negative)=" + MathKit.fmt(posterior, 2)
                + " (90% interval " + MathKit.fmt(ci[0], 2) + "-" + MathKit.fmt(ci[1], 2)
                + ", bucket " + bucket + ", n=" + n + ")";

        String interpretation;
        if (posterior >= 0.65) {
            interpretation = "BAD TIMING PRIOR: classic hide-the-news window for bad news"
                    + " - read the content extra critically, probe for spin"
                    + " (bucket " + bucket + ", n=" + n + ").";
        } else if (posterior >= 0.45) {
            interpretation = "Neutral prior: in the issuer's own history this time window was neither"
                    + " notably good nor bad - the content alone decides"
                    + " (bucket " + bucket + ", n=" + n + ").";
        } else {
            interpretation = "Unremarkable window: filings at this time were historically rarely"
                    + " bad news - no timing suspicion"
                    + " (bucket " + bucket + ", n=" + n + ").";
        }
        if (n < THIN_BUCKET) {
            interpretation += " Caution: only n=" + n + " filings in this time window"
                    + " - thin data, read the prior with restraint.";
        }

        return Optional.of(new SignalReading(
                "adhoc-timing-prior",
                "Ad-hoc timing prior",
                posterior,
                formatted,
                "Base rate from the issuer's own history of how often filings in this time window"
                        + " carried bad news (Friday-night-dump effect, well documented in the finance"
                        + " literature).",
                interpretation));
    }

    /** Maps weekday and hour to one of the three time-window buckets. */
    static String bucketOf(DayOfWeek day, int hour) {
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
                || (day == DayOfWeek.FRIDAY && hour >= 17)) {
            return "FRIDAY NIGHT";
        }
        if (day != DayOfWeek.FRIDAY && (hour >= 17 || hour < 8)) {
            return "AFTER HOURS";
        }
        return "TRADING HOURS";
    }
}
