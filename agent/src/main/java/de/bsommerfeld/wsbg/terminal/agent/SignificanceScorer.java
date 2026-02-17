package de.bsommerfeld.wsbg.terminal.agent;

import java.time.Duration;
import java.time.Instant;

/**
 * Lightweight heuristic gate for {@link InvestigationCluster} significance.
 * This is NOT the final quality decision — it only determines whether a
 * cluster warrants an AI call. The AI itself makes the real significance
 * and topic-relevance judgment inside the consolidated headline prompt.
 */
final class SignificanceScorer {

    private SignificanceScorer() {
    }

    /**
     * Instant heuristic based on activity metrics only.
     * Intentionally permissive — false negatives are worse than false positives
     * because the AI can still reject with -1.
     */
    static SignificanceScore compute(InvestigationCluster inv) {
        double score = inv.threadCount * 12.0
                + inv.totalComments * 2.5
                + inv.totalScore * 0.4;
        if (inv.lastActivity.isAfter(Instant.now().minus(Duration.ofMinutes(5))))
            score += 5.0;
        return new SignificanceScore(score, "Heuristic gate");
    }
}
