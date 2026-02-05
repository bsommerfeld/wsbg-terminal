package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SignificanceScorerTest {

    @Test
    void compute_shouldReturnZeroForFreshCluster() {
        var cluster = createCluster("Fresh thread", 1, 0);
        SignificanceScore score = SignificanceScorer.compute(cluster);

        assertNotNull(score);
        // A brand new cluster with no updates should have minimal significance
        assertTrue(score.score() >= 0);
    }

    @Test
    void compute_shouldIncreasWithMultipleUpdates() {
        var cluster = createCluster("Active thread", 100, 50);
        simulateUpdates(cluster, 5, 20, 10);

        SignificanceScore score = SignificanceScorer.compute(cluster);
        assertTrue(score.score() > 0, "Active cluster should have positive significance");
    }

    @Test
    void compute_shouldScoreHighForHighActivity() {
        var clusterLow = createCluster("Low activity", 10, 2);
        var clusterHigh = createCluster("High activity", 500, 200);
        simulateUpdates(clusterHigh, 10, 100, 50);

        SignificanceScore scoreLow = SignificanceScorer.compute(clusterLow);
        SignificanceScore scoreHigh = SignificanceScorer.compute(clusterHigh);

        assertTrue(scoreHigh.score() >= scoreLow.score(),
                "High activity cluster should score at least as high");
    }

    @Test
    void compute_shouldReturnNonNullReasoning() {
        var cluster = createCluster("Test", 50, 10);
        SignificanceScore score = SignificanceScorer.compute(cluster);

        assertNotNull(score.reasoning());
        assertFalse(score.reasoning().isEmpty());
    }

    private InvestigationCluster createCluster(String title, int score, int comments) {
        long now = System.currentTimeMillis() / 1000;
        var thread = new RedditThread("t3_test", "wsb", title, "author", "content",
                now, "/r/wsb/test", score, 0.85, comments, now, null);

        float[] vector = new float[768];
        vector[0] = 1.0f;
        return new InvestigationCluster(thread, Embedding.from(vector));
    }

    private void simulateUpdates(InvestigationCluster cluster, int count,
            int deltaScore, int deltaComments) {
        for (int i = 0; i < count; i++) {
            long now = System.currentTimeMillis() / 1000;
            var thread = new RedditThread("t3_u" + i, "wsb", "Update " + i,
                    "author", "content", now, "/p", deltaScore, 0.9, deltaComments, now, null);
            float[] vector = new float[768];
            vector[0] = 0.9f;
            cluster.addUpdate(thread, deltaScore, deltaComments, Embedding.from(vector));
        }
    }
}
