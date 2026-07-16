package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Expectation vacuum: combo signal from calendar proximity and Reddit
 * attention - hunts for events nobody is watching.
 *
 * <p><b>Method:</b> product of three normalized factors:
 * proximity = 1 - days/14 (event is imminent),
 * silence = 1 - min(1, mentions/baseline) (hardly any mentions relative to
 * the own baseline) and spread = normalized attention entropy (no focused
 * attention). Literature anchor: limited attention and underreaction to
 * public events (Hirshleifer/Teoh 2003; DellaVigna/Pollet 2009) - without a
 * priced-in expectation the move per unit of information is maximal.
 *
 * <p><b>Terminal inputs:</b> event proximity from the calendar briefing
 * (earnings, ECB/Fed, macro), mention rates from the Reddit feed and the
 * normalized entropy from the attention-entropy signal.
 */
public final class ExpectationVacuum {

    private static final long MAX_DAYS = 14;
    private static final double THIN_BASELINE = 1.0;

    private ExpectationVacuum() {
    }

    /**
     * @param daysToEvent                 days until the event (0 to {@value #MAX_DAYS})
     * @param mentionsPerDay              current mentions per day
     * @param baselineMentionsPerDay      historical baseline of mentions per day (> 0)
     * @param normalizedAttentionEntropy  normalized attention entropy in [0,1]
     * @return reading, or empty if the event lies outside the window or the baseline is missing
     */
    public static Optional<SignalReading> measure(long daysToEvent, double mentionsPerDay,
                                                  double baselineMentionsPerDay,
                                                  double normalizedAttentionEntropy) {
        if (daysToEvent < 0 || daysToEvent > MAX_DAYS || !(baselineMentionsPerDay > 0)) {
            return Optional.empty();
        }
        double proximity = 1.0 - daysToEvent / (double) MAX_DAYS;
        double silence = 1.0 - Math.min(1.0, mentionsPerDay / baselineMentionsPerDay);
        double spread = Math.max(0.0, Math.min(1.0, normalizedAttentionEntropy));
        double value = proximity * silence * spread;

        String formatted = MathKit.fmt(value, 2) + " (scale 0-1; proximity " + MathKit.fmt(proximity, 2)
                + " x silence " + MathKit.fmt(silence, 2) + " x spread " + MathKit.fmt(spread, 2)
                + ", event in " + daysToEvent + " day(s))";

        String interpretation;
        if (value >= 0.6) {
            interpretation = "EXPECTATION VACUUM: there is no priced-in expectation - maximum"
                    + " move per unit of information, ideal candidate for a preparatory DD."
                    + " Event in " + daysToEvent + " day(s).";
        } else if (value >= 0.3) {
            interpretation = "Thinly covered: the event runs under the radar, but not entirely"
                    + " unwatched - a look ahead can pay off."
                    + " Event in " + daysToEvent + " day(s).";
        } else {
            interpretation = "Expectation is formed: the event is priced in - only a deviation"
                    + " from consensus still surprises here."
                    + " Event in " + daysToEvent + " day(s).";
        }
        if (baselineMentionsPerDay < THIN_BASELINE) {
            interpretation += " Caution: very low mention baseline ("
                    + MathKit.fmt(baselineMentionsPerDay, 2) + "/day)"
                    + " - thin data, read the silence factor with restraint.";
        }

        return Optional.of(new SignalReading(
                "expectation-vacuum",
                "Expectation vacuum (combo signal)",
                value,
                formatted,
                "Inverts the calendar - hunts for events NOBODY is watching: no mentions,"
                        + " no focused attention, event is imminent.",
                interpretation));
    }
}
