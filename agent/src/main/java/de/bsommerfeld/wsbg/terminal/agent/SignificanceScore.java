package de.bsommerfeld.wsbg.terminal.agent;

/**
 * Immutable result of a significance assessment for a thread or cluster.
 * The score is a 0.0–100.0 value produced by the AI, combining
 * market relevance, urgency, and activity momentum.
 *
 * @param score     weighted significance (0.0 = irrelevant, 100.0 = critical)
 * @param reasoning one-line justification from the AI
 */
record SignificanceScore(double score, String reasoning) {

    static final SignificanceScore ZERO = new SignificanceScore(0.0, "No data");

    boolean meetsThreshold(double threshold) {
        return score >= threshold;
    }
}
