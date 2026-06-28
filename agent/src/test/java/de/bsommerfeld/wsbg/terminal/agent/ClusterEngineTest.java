package de.bsommerfeld.wsbg.terminal.agent;
import de.bsommerfeld.wsbg.terminal.embedding.EmbeddingService;

import de.bsommerfeld.wsbg.terminal.agent.ClusterEngine.AssignOutcome;
import de.bsommerfeld.wsbg.terminal.agent.ClusterEngine.Kind;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cluster assignment after the "one cluster == one thread" cutover: there is no
 * cross-thread merging anymore (the feed-wide SubjectRegistry is the cross-thread
 * layer). A new thread creates its own cluster; re-seeing a thread updates that
 * same cluster. Driven through the {@link EmbeddingService} seam with a fake — NO
 * Ollama (the embedding is now only kept for snapshot shape, not routing).
 */
class ClusterEngineTest {

    private static ClusterEngine engine(ClusterRegistry reg) {
        return new ClusterEngine(reg);
    }

    private static RedditThread thread(String id, String title, String body, int score, int comments) {
        long now = System.currentTimeMillis() / 1000;
        return new RedditThread(id, "wallstreetbetsGER", title, "[user]", body, now,
                "/r/wallstreetbetsGER/comments/" + id, score, 0.5, comments);
    }

    @Test
    void newThreadCreatesItsOwnClusterKeyedByThreadId() {
        ClusterRegistry reg = new ClusterRegistry();
        AssignOutcome out = engine(reg).assign(thread("t3_a", "NVIDIA Rakete", "all in", 0, 0), 0, 0, "");
        assertEquals(Kind.NEW, out.kind());
        assertEquals("t3_a", out.clusterId(), "cluster id == thread id");
        assertEquals(1, reg.size());
    }

    @Test
    void everyThreadGetsItsOwnClusterEvenWhenContentIsNearIdentical() {
        ClusterRegistry reg = new ClusterRegistry();
        ClusterEngine e = engine(reg);
        e.assign(thread("t3_a", "NVIDIA Rakete Mond Hebel all in", "NVIDIA Hebel Rakete Mond", 0, 0), 0, 0, "");
        AssignOutcome out = e.assign(
                thread("t3_b", "NVIDIA Rakete Hebel Mond", "NVIDIA all in Rakete heute", 0, 0), 0, 0, "");
        // No more cosine merge: two threads = two clusters, period.
        assertEquals(Kind.NEW, out.kind(), "a second thread is its own cluster, never merged");
        assertEquals(2, reg.size(), "two threads → two clusters");
    }

    @Test
    void sharedTickerNoLongerMergesThreads() {
        ClusterRegistry reg = new ClusterRegistry();
        ClusterEngine e = engine(reg);
        e.assign(thread("t3_a", "$NVDA Rakete Mond", "all in", 0, 0), 0, 0, "");
        AssignOutcome out = e.assign(thread("t3_d", "$NVDA komplett anderer Text", "x", 0, 0), 0, 0, "");
        assertEquals(Kind.NEW, out.kind(), "a shared $ticker is the SubjectRegistry's job, not the cluster's");
        assertEquals(2, reg.size());
    }

    @Test
    void reseeingAThreadUpdatesItsOwnCluster() {
        ClusterRegistry reg = new ClusterRegistry();
        ClusterEngine e = engine(reg);
        e.assign(thread("t3_a", "NVIDIA Rakete", "all in", 10, 2), 0, 0, "");
        AssignOutcome out = e.assign(
                thread("t3_a", "NVIDIA Rakete", "all in", 15, 5), 5, 3, "");
        assertEquals(Kind.UPDATE, out.kind(), "the same thread updates its existing cluster");
        assertEquals("t3_a", out.clusterId());
        assertEquals(1, reg.size(), "no new cluster on update");
    }
}
