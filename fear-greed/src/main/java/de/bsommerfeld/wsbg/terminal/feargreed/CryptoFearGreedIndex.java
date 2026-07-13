package de.bsommerfeld.wsbg.terminal.feargreed;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * One reading of the crypto Fear &amp; Greed Index (alternative.me) — the
 * crypto-market counterpart of CNN's composite: 0 = extreme fear, 100 =
 * extreme greed, one fix per day. Complements the US-market gauge in the
 * same widget; neither replaces the other.
 *
 * @param score         0–100 composite
 * @param rating        alternative.me's own label ("Extreme Fear" … "Extreme Greed")
 * @param previousClose yesterday's score ({@code null} when the feed carried only one day)
 * @param fetchedAt     when this reading was pulled
 * @param history       the daily score series (chronological, possibly empty — never null)
 */
public record CryptoFearGreedIndex(double score, String rating, Double previousClose,
                                   Instant fetchedAt, List<FearGreedIndex.Point> history) {

    public CryptoFearGreedIndex {
        history = history == null ? List.of() : List.copyOf(history);
    }

    /**
     * Band for the shared color scale. alternative.me's own band cuts differ
     * slightly from CNN's, so the label string decides first (label and color
     * must never contradict each other); an unknown label falls back to CNN's
     * numeric thresholds.
     */
    public FearGreedIndex.Band band() {
        return switch (rating == null ? "" : rating.toLowerCase(Locale.ROOT)) {
            case "extreme fear" -> FearGreedIndex.Band.EXTREME_FEAR;
            case "fear" -> FearGreedIndex.Band.FEAR;
            case "neutral" -> FearGreedIndex.Band.NEUTRAL;
            case "greed" -> FearGreedIndex.Band.GREED;
            case "extreme greed" -> FearGreedIndex.Band.EXTREME_GREED;
            default -> FearGreedIndex.Band.of(score);
        };
    }
}
