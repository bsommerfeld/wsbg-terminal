package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;

import java.util.EnumSet;
import java.util.Set;

/**
 * Directional-label sanity: makes the headline's sentiment agree with the sign of
 * the number the line carries, and caps an obvious position-P&amp;L misread. Never a
 * publish gate — it only corrects the label / clamps the move. Extracted verbatim
 * from {@link HeadlineWriter}.
 */
final class SentimentReconciler {

    private SentimentReconciler() {}

    private static final Set<HeadlineSentiment> BULLISH_CAMP = EnumSet.of(
            HeadlineSentiment.BULLISH, HeadlineSentiment.FOMO,
            HeadlineSentiment.BREAKOUT, HeadlineSentiment.SQUEEZE);
    private static final Set<HeadlineSentiment> BEARISH_CAMP = EnumSet.of(
            HeadlineSentiment.BEARISH, HeadlineSentiment.CAPITULATION);

    /** Only a move at least this large (in %) overrides the model's directional label. */
    private static final double SENTIMENT_FLIP_MIN_MOVE = 1.5;

    static Double sanePriceMove(Double priceMove, String headline) {
        if (priceMove == null) return null;
        if (Math.abs(priceMove) > 500.0
                && headline.matches(".*\\d[\\d.,]*\\s*(€|\\$|EUR|USD).*")) {
            return null; // money amount + huge % ⇒ almost always a P&L misread
        }
        return priceMove;
    }

    /**
     * Makes the directional read agree with the sign of the number the headline
     * itself carries — a line with a −% can't read BULLISH, and vice versa (a
     * reader feels lied to otherwise). The number is the line's own
     * {@code priceMovePercent}, which is the figure the line is ABOUT — Yahoo- OR
     * user-sourced, it doesn't matter (sentiment is sentiment: a posted −13% is
     * bearish regardless of whether Yahoo confirms it).
     *
     * <p>Deliberately does NOT fall back to the instrument's market day-move: a
     * loss-porn post is BEARISH even when the stock is green today, so the model's
     * own classification must stand when the line carries no move of its own. Only
     * flips a directional label that <em>contradicts</em> a <b>prominent</b> move
     * ({@link #SENTIMENT_FLIP_MIN_MOVE}); a tiny ±0.x% day-move must NOT drag a
     * bullish narrative ("+20% seit dem Tief") to BEARISH. Non-directional reads
     * (NEUTRAL/MIXED/REVERSAL) and number-less lines stay as the model set them.
     * <b>Never a publish gate</b> — it only corrects the label.
     */
    static HeadlineSentiment reconcileSentiment(HeadlineSentiment sentiment, Double priceMove) {
        if (priceMove == null || !Double.isFinite(priceMove)
                || Math.abs(priceMove) < SENTIMENT_FLIP_MIN_MOVE) {
            return sentiment;
        }
        if (priceMove < 0 && BULLISH_CAMP.contains(sentiment)) return HeadlineSentiment.BEARISH;
        if (priceMove > 0 && BEARISH_CAMP.contains(sentiment)) return HeadlineSentiment.BULLISH;
        return sentiment;
    }
}
