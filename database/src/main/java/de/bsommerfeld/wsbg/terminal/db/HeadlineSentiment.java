package de.bsommerfeld.wsbg.terminal.db;

/**
 * Crowd-sentiment classifier the editorial agent attaches to every
 * published headline. Each value renders as a colour-coded chip in the
 * UI so traders can scan the wire and instantly read the room — bullish
 * green, bearish red, capitulation deep red, FOMO orange, and so on.
 *
 * <p>
 * Hardcoded vocabulary (NOT free-form) by design: the previous freeform
 * tags field produced an unbounded grab bag — "Catalyst", "Reversal",
 * "Pump", "After Hours", random ad-hoc strings — that nobody could
 * filter on and that all rendered identically. A small fixed set with
 * distinct colours is more useful at-a-glance than 50 indistinguishable
 * grey chips.
 *
 * <p>
 * Use {@link #fromString} for safe parsing from tool input
 * (case-insensitive, falls back to {@link #NEUTRAL}).
 */
public enum HeadlineSentiment {

    /** Crowd is buying / defending longs / "noch lange nicht am Top". */
    BULLISH,

    /** Crowd is selling / opening shorts / "ich bin raus". */
    BEARISH,

    /** Conflicting takes, balanced upvotes both sides, room is split. */
    MIXED,

    /** Frantic chasing — "all in", "ich muss noch rein", late entries. */
    FOMO,

    /** Resignation — "alles verloren", giving up, dumping at the bottom. */
    CAPITULATION,

    /** Short-squeeze chatter, gamma squeeze, forced-buy mechanics. */
    SQUEEZE,

    /** Mean-reversion / counter-trend trade being called. */
    REVERSAL,

    /** Breakout call — pierce above resistance, ATH, fresh high. */
    BREAKOUT,

    /** Default — observation without a directional position. */
    NEUTRAL;

    public static HeadlineSentiment fromString(String s) {
        if (s == null) {
            return NEUTRAL;
        }
        try {
            return HeadlineSentiment.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NEUTRAL;
        }
    }
}
