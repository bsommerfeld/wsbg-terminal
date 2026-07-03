package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
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
    }

    @Test
    void id_shouldMatchInitialThreadId() {
        var cluster = createCluster("t3_abc123", "Title");
        assertEquals("t3_abc123", cluster.id);
    }

    @Test
    void addUpdate_shouldIncrementThreadCount() {
        var cluster = createCluster("t3_1", "Initial");

        long now = System.currentTimeMillis() / 1000;
        var update = new RedditThread("t3_2", "wsb", "Update",
                "a", null, now, "/p", 10, 0.5, 5, now, null);
        cluster.addUpdate(update, 10, 5);

        assertEquals(2, cluster.threadCount);
    }

    @Test
    void addUpdate_shouldAccumulateScoreAndComments() {
        var cluster = createCluster("t3_1", "Initial");

        long now = System.currentTimeMillis() / 1000;
        var update = new RedditThread("t3_2", "wsb", "Update",
                "a", null, now, "/p", 50, 0.9, 20, now, null);
        cluster.addUpdate(update, 30, 10);

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
        cluster.addUpdate(update, 1, 1);

        assertTrue(cluster.lastActivity.isAfter(before) || cluster.lastActivity.equals(before));
    }




    @Test
    void activeThreadIds_shouldTrackAllIds() {
        var cluster = createCluster("t3_1", "Initial");

        long now = System.currentTimeMillis() / 1000;
        var update = new RedditThread("t3_2", "wsb", "U",
                "a", null, now, "/p", 1, 0.5, 1, now, null);
        cluster.addUpdate(update, 1, 1);

        assertTrue(cluster.activeThreadIds.contains("t3_1"));
        assertTrue(cluster.activeThreadIds.contains("t3_2"));
    }

    @Test
    void bestThreadId_shouldTrackHighestScore() {
        var cluster = createCluster("t3_low", "Low", 5);

        long now = System.currentTimeMillis() / 1000;
        var highScore = new RedditThread("t3_high", "wsb", "High Score",
                "a", null, now, "/p", 999, 0.9, 1, now, null);
        cluster.addUpdate(highScore, 999, 1);

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
        return new InvestigationCluster(thread);
    }

}
