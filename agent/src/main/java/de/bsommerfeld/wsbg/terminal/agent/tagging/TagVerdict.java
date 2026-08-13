package de.bsommerfeld.wsbg.terminal.agent.tagging;

/**
 * One article's judged relation to one instrument: the role, how sure the
 * engine is, and WHICH stage decided — the explanation that the old
 * query-time word guess could never give.
 */
public record TagVerdict(Role role, double confidence, String decidedBy) {

    /** What the article is to the instrument. */
    public enum Role {
        /** The article is ABOUT the instrument — what an instrument query returns. */
        SUBJECT,
        /** The instrument appears, but as attribution/side note ("DB Research belässt Tui…"). */
        MENTIONED,
        /** Considered and rejected. */
        NONE,
        /** Ambiguous and not yet arbitrated — excluded until a verdict lands. */
        PENDING
    }

    public static TagVerdict none(String decidedBy) {
        return new TagVerdict(Role.NONE, 0.0, decidedBy);
    }
}
