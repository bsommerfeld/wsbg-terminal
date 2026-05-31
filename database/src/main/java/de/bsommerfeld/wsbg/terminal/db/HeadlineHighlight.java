package de.bsommerfeld.wsbg.terminal.db;

/**
 * Editorial weight on a published headline.
 *
 * <p>
 * Two-tier: routine vs catalyst. Trader-facing emphasis only — the
 * decision whether a cluster gets a headline at all is not encoded
 * here. A cluster with no new uncovered content simply yields no
 * headline this tick; a cluster with new content yields a headline at
 * one of these weights.
 *
 * <p>
 * Use {@link #fromString} for safe parsing from tool-input strings
 * (case-insensitive, falls back to {@link #NORMAL}).
 */
public enum HeadlineHighlight {

    /** Standard headline — no special rendering. */
    NORMAL,

    /** Worth attention — real move, breaking event, or unusual signal. */
    IMPORTANT;

    public static HeadlineHighlight fromString(String s) {
        if (s == null)
            return NORMAL;
        try {
            return HeadlineHighlight.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NORMAL;
        }
    }
}
