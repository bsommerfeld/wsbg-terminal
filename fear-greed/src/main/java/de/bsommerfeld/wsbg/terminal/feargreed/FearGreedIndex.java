package de.bsommerfeld.wsbg.terminal.feargreed;

import java.time.Instant;
import java.util.List;

/**
 * One reading of CNN's Fear &amp; Greed Index — the broad US-market sentiment
 * gauge (0 = extreme fear, 100 = extreme greed). Complements the room-level
 * BULLISH/BEARISH mood badge: that one mirrors the cage, this one the whole market.
 *
 * @param score        0–100 composite
 * @param rating       CNN's own label string ("extreme fear" … "extreme greed")
 * @param previousClose yesterday's score, for an up/down arrow
 * @param previousWeek  the composite one week ago ({@code null} when CNN didn't ship it)
 * @param previousMonth the composite one month ago ({@code null} when CNN didn't ship it)
 * @param previousYear  the composite one year ago ({@code null} when CNN didn't ship it)
 * @param fetchedAt    when this reading was pulled
 * @param history      the ~1y daily score series CNN ships alongside the reading
 *                     (chronological, possibly empty — never null)
 * @param components   the seven sub-indicators CNN folds into the composite
 *                     (momentum, price strength, breadth, put/call, VIX, junk
 *                     bonds, safe haven — possibly empty, never null)
 */
public record FearGreedIndex(double score, String rating, double previousClose,
                             Double previousWeek, Double previousMonth, Double previousYear,
                             Instant fetchedAt, List<Point> history, List<Component> components) {

    /** One historical sample: epoch millis + the composite score at that time. */
    public record Point(long epochMs, double score) {}

    /**
     * One of the seven sub-indicators behind the composite. {@code key} is CNN's
     * own JSON block name (e.g. {@code market_momentum_sp500}) — the UI maps it
     * to a localized label; {@code rating} is CNN's per-component label string.
     */
    public record Component(String key, double score, String rating) {

        /** Same thresholds as the composite — one color scale everywhere. */
        public Band band() {
            return Band.of(score);
        }
    }

    public FearGreedIndex {
        history = history == null ? List.of() : List.copyOf(history);
        components = components == null ? List.of() : List.copyOf(components);
    }

    /** Pre-components shape (tests / callers that only need composite + history). */
    public FearGreedIndex(double score, String rating, double previousClose, Instant fetchedAt,
                          List<Point> history) {
        this(score, rating, previousClose, null, null, null, fetchedAt, history, List.of());
    }

    /** History-less reading (tests / callers that only need the live value). */
    public FearGreedIndex(double score, String rating, double previousClose, Instant fetchedAt) {
        this(score, rating, previousClose, fetchedAt, List.of());
    }

    /** The five canonical bands, derived from the score (CNN's own thresholds). */
    public enum Band {
        EXTREME_FEAR, FEAR, NEUTRAL, GREED, EXTREME_GREED;

        /** Band from a numeric score — robust even if CNN's rating string changes. */
        public static Band of(double score) {
            if (score < 25) return EXTREME_FEAR;
            if (score < 45) return FEAR;
            if (score <= 55) return NEUTRAL;
            if (score <= 75) return GREED;
            return EXTREME_GREED;
        }
    }

    /** Band from the numeric score — robust even if CNN's rating string changes. */
    public Band band() {
        return Band.of(score);
    }
}
