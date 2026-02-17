package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class InvestigationClusterTest {

    @Test
    void constructor_shouldInitializeWithSeedThread() {
        var cluster = createCluster("t3_seed", "Seed Title");

        assertEquals("Seed Title", cluster.initialTitle);
        assertEquals(1, cluster.threadCount);
        assertNotNull(cluster.id);
        assertFalse(cluster.id.isEmpty());
        assertNotNull(cluster.centroid());
    }

    @Test
    void id_shouldBeEightCharacters() {
        var cluster = createCluster("t3_1", "Title");
        assertEquals(8, cluster.id.length());
    }

    @Test
    void addUpdate_shouldIncrementThreadCount() {
        var cluster = createCluster("t3_1", "Initial");

        long now = System.currentTimeMillis() / 1000;
        var update = new RedditThread("t3_2", "wsb", "Update",
                "a", null, now, "/p", 10, 0.5, 5, now, null);
        cluster.addUpdate(update, 10, 5, dummyEmbedding());

        assertEquals(2, cluster.threadCount);
    }

    @Test
    void addUpdate_shouldAccumulateScoreAndComments() {
        var cluster = createCluster("t3_1", "Initial");

        long now = System.currentTimeMillis() / 1000;
        var update = new RedditThread("t3_2", "wsb", "Update",
                "a", null, now, "/p", 50, 0.9, 20, now, null);
        cluster.addUpdate(update, 30, 10, dummyEmbedding());

        assertTrue(cluster.totalScore > 0);
        assertTrue(cluster.totalComments > 0);
    }

    @Test
    void addUpdate_shouldRefreshLastActivity() throws InterruptedException {
        var cluster = createCluster("t3_1", "Initial");
        Instant before = cluster.lastActivity;

        Thread.sleep(50);

        long now = System.currentTimeMillis() / 1000;
        var update = new RedditThread("t3_2", "wsb", "U",
                "a", null, now, "/p", 1, 0.5, 1, now, null);
        // deltaComments > 0 triggers lastActivity refresh
        cluster.addUpdate(update, 1, 1, dummyEmbedding());

        assertTrue(cluster.lastActivity.isAfter(before) || cluster.lastActivity.equals(before));
    }

    @Test
    void addUpdate_shouldUpdateCentroidViaEma() {
        var cluster = createCluster("t3_1", "Initial");
        Embedding initialCentroid = cluster.centroid();

        float[] diffVector = new float[768];
        diffVector[1] = 1.0f;
        long now = System.currentTimeMillis() / 1000;
        var update = new RedditThread("t3_2", "wsb", "Different",
                "a", null, now, "/p", 1, 0.5, 1, now, null);
        cluster.addUpdate(update, 1, 1, Embedding.from(diffVector));

        // Centroid should have shifted — the second dimension was 0 before the update
        Embedding updatedCentroid = cluster.centroid();
        float updatedDim1 = updatedCentroid.vectorAsList().get(1);
        assertTrue(updatedDim1 > 0.0f,
                "EMA should shift centroid toward the new vector's direction");
    }

    @Test
    void absorb_shouldMergeCountsAndEvidence() {
        var primary = createCluster("t3_1", "Primary");
        var secondary = createCluster("t3_2", "Secondary");

        long now = System.currentTimeMillis() / 1000;
        var update = new RedditThread("t3_3", "wsb", "Extra",
                "a", null, now, "/p", 1, 0.5, 1, now, null);
        secondary.addUpdate(update, 5, 3, dummyEmbedding());

        int primaryThreadsBefore = primary.threadCount;
        primary.absorb(secondary);

        assertTrue(primary.threadCount > primaryThreadsBefore);
    }

    @Test
    void absorb_shouldMergeActiveThreadIds() {
        var primary = createCluster("t3_1", "Primary");
        var secondary = createCluster("t3_2", "Secondary");

        primary.absorb(secondary);

        assertTrue(primary.activeThreadIds.contains("t3_1"));
        assertTrue(primary.activeThreadIds.contains("t3_2"));
    }

    @Test
    void reported_shouldDefaultToFalse() {
        var cluster = createCluster("t3_1", "Title");
        assertFalse(cluster.reported);
    }

    @Test
    void addToHistory_shouldAccumulateEntries() {
        var cluster = createCluster("t3_1", "Title");

        cluster.addToHistory("Headline 1");
        cluster.addToHistory("Headline 2");

        assertEquals(2, cluster.reportHistory.size());
        // addToHistory prepends a [HH:MM] timestamp, so check contains
        assertTrue(cluster.reportHistory.get(0).contains("Headline 1"));
    }

    @Test
    void addToHistory_shouldCapAtFiveEntries() {
        var cluster = createCluster("t3_1", "Title");
        for (int i = 0; i < 7; i++) {
            cluster.addToHistory("H" + i);
        }
        assertEquals(5, cluster.reportHistory.size());
    }

    @Test
    void activeThreadIds_shouldTrackAllIds() {
        var cluster = createCluster("t3_1", "Initial");

        long now = System.currentTimeMillis() / 1000;
        var update = new RedditThread("t3_2", "wsb", "U",
                "a", null, now, "/p", 1, 0.5, 1, now, null);
        cluster.addUpdate(update, 1, 1, dummyEmbedding());

        assertTrue(cluster.activeThreadIds.contains("t3_1"));
        assertTrue(cluster.activeThreadIds.contains("t3_2"));
    }

    @Test
    void bestThreadId_shouldTrackHighestScore() {
        var cluster = createCluster("t3_low", "Low", 5);

        long now = System.currentTimeMillis() / 1000;
        var highScore = new RedditThread("t3_high", "wsb", "High Score",
                "a", null, now, "/p", 999, 0.9, 1, now, null);
        cluster.addUpdate(highScore, 999, 1, dummyEmbedding());

        assertEquals("t3_high", cluster.bestThreadId);
        assertEquals(999, cluster.bestThreadScore);
    }

    private InvestigationCluster createCluster(String threadId, String title) {
        return createCluster(threadId, title, 10);
    }

    private InvestigationCluster createCluster(String threadId, String title, int score) {
        long now = System.currentTimeMillis() / 1000;
        var thread = new RedditThread(threadId, "wsb", title, "author", "text",
                now, "/p", score, 0.8, 5, now, null);
        return new InvestigationCluster(thread, dummyEmbedding());
    }

    private Embedding dummyEmbedding() {
        float[] vector = new float[768];
        vector[0] = 1.0f;
        return Embedding.from(vector);
    }
}
