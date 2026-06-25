package de.bsommerfeld.wsbg.terminal.feargreed;

import java.time.Instant;

/**
 * One reading of CNN's Fear &amp; Greed Index — the broad US-market sentiment
 * gauge (0 = extreme fear, 100 = extreme greed). Complements the room-level
 * BULLISH/BEARISH mood badge: that one mirrors the cage, this one the whole market.
 *
 * @param score        0–100 composite
 * @param rating       CNN's own label string ("extreme fear" … "extreme greed")
 * @param previousClose yesterday's score, for an up/down arrow
 * @param fetchedAt    when this reading was pulled
 */
public record FearGreedIndex(double score, String rating, double previousClose, Instant fetchedAt) {

    /** The five canonical bands, derived from the score (CNN's own thresholds). */
    public enum Band { EXTREME_FEAR, FEAR, NEUTRAL, GREED, EXTREME_GREED }

    /** Band from the numeric score — robust even if CNN's rating string changes. */
    public Band band() {
        if (score < 25) return Band.EXTREME_FEAR;
        if (score < 45) return Band.FEAR;
        if (score <= 55) return Band.NEUTRAL;
        if (score <= 75) return Band.GREED;
        return Band.EXTREME_GREED;
    }
}
