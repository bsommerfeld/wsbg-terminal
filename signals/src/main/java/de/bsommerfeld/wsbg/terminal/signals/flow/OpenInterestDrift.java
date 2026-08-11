package de.bsommerfeld.wsbg.terminal.signals.flow;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * The sharpest reading in the whole flow family, and the only one that has to
 * wait for it: is open interest RISING or FALLING while the name trades?
 *
 * <p>Share volume cannot separate a crowd arriving from a crowd leaving -
 * every trade has two sides and counts once either way. Open interest can,
 * because it counts contracts OUTSTANDING: it rises only when a position is
 * opened and falls only when one is closed. So:
 *
 * <ul>
 *   <li>busy tape, open interest RISING - money entering, the move has
 *       conviction behind it;</li>
 *   <li>busy tape, open interest FALLING - positions being unwound, and a
 *       price that still holds is being carried by fewer hands. This is the
 *       classic exhaustion tell, and it usually precedes the price.</li>
 * </ul>
 *
 * <p><b>Cold start is structural, not a defect.</b> Price, volume and the
 * short share are all retrievable years back; open interest is published only
 * as "now" and nobody archives it for free, so this axis measures nothing
 * until the house has watched a name for {@value #MIN_SESSIONS} sessions. It
 * says so instead of guessing - a cold axis must never enter a tally as a
 * neutral vote, or the warm-up quietly dilutes every verdict.
 */
public final class OpenInterestDrift {

    /** Below this many archived sessions there is nothing to compare. */
    private static final int MIN_SESSIONS = 5;
    /** Sessions averaged into each end of the comparison. */
    private static final int EDGE = 2;
    /** From this relative change the book is growing or shrinking meaningfully. */
    private static final double MOVED = 0.05;

    private OpenInterestDrift() {
    }

    /**
     * Measures the change in the standing book.
     *
     * @param openInterest total contracts outstanding per archived session,
     *                     oldest first
     * @return the reading, or empty while the archive is still cold
     */
    public static Optional<SignalReading> measure(long[] openInterest) {
        if (openInterest == null || openInterest.length < MIN_SESSIONS) return Optional.empty();
        int n = openInterest.length;
        double[] first = new double[EDGE];
        double[] last = new double[EDGE];
        for (int i = 0; i < EDGE; i++) {
            if (openInterest[i] <= 0 || openInterest[n - 1 - i] <= 0) return Optional.empty();
            first[i] = openInterest[i];
            last[i] = openInterest[n - 1 - i];
        }
        double then = MathKit.mean(first);
        double now = MathKit.mean(last);
        if (then <= 0) return Optional.empty();
        double change = now / then - 1;

        String interpretation;
        if (change >= MOVED) {
            interpretation = "The standing book is GROWING: contracts are being opened faster "
                    + "than closed over these sessions - money is entering the name, and the "
                    + "move has positions behind it rather than churn.";
        } else if (change <= -MOVED) {
            interpretation = "The standing book is SHRINKING: contracts are being closed faster "
                    + "than opened - positions are being unwound. A price that still holds up "
                    + "against a shrinking book is carried by fewer and fewer hands, and this "
                    + "usually turns before the price does.";
        } else {
            interpretation = "The standing book is flat over these sessions - whatever trades, "
                    + "it is opening and closing in roughly equal measure.";
        }
        return Optional.of(new SignalReading(
                "open-interest-drift",
                "Open interest against the house's own record",
                change,
                (change >= 0 ? "+" : "") + MathKit.fmt(change * 100, 1)
                        + " % over " + n + " archived sessions",
                "Change in total outstanding option contracts between the oldest and the "
                        + "newest sessions the house has archived for this name.",
                interpretation + " Open interest is the ONLY figure here that distinguishes "
                        + "positions being opened from positions being closed."));
    }

    /**
     * Why the axis is silent, for a tally that must not count a cold axis as a
     * neutral vote.
     *
     * @param archivedSessions sessions the house has archived for the name
     * @return true while the archive cannot yet carry a reading
     */
    public static boolean cold(int archivedSessions) {
        return archivedSessions < MIN_SESSIONS;
    }

    /** Sessions the archive needs before this axis speaks. */
    public static int requiredSessions() {
        return MIN_SESSIONS;
    }
}
