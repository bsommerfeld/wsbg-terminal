package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClusterRebalancerTest {

    private ClusterRegistry registry;
    private ClusterRebalancer rebalancer;

    @BeforeEach
    void setUp() {
        registry = new ClusterRegistry();
        // The constructor schedules the periodic job; the run() method below
        // is what the tests actually exercise.
        rebalancer = new ClusterRebalancer(registry, new GlobalConfig());
    }

    @AfterEach
    void tearDown() {
        rebalancer.shutdown();
    }

    private InvestigationCluster cluster(String id, float[] vec, int comments) {
        long now = System.currentTimeMillis() / 1000;
        RedditThread t = new RedditThread(id, "wsb", "T " + id, "u", "b",
                now, "/p", 1, 0.9, comments, now, null);
        return new InvestigationCluster(t, Embedding.from(vec));
    }

    @Test
    void run_mergesClustersAboveMergeThreshold() {
        // Both centroids are almost identical → cosine ≈ 1.0, well above the
        // default 0.55 + 0.10 = 0.65 merge threshold.
        registry.add(cluster("a", new float[] { 1.0f, 0.0f, 0.0f }, 10));
        registry.add(cluster("b", new float[] { 0.99f, 0.01f, 0.0f }, 10));
        assertEquals(2, registry.size());

        var stats = rebalancer.run();

        assertEquals(1, registry.size(), "almost-identical centroids should merge");
        assertTrue(stats.merges() >= 1);
    }

    @Test
    void run_leavesDistantClustersAlone() {
        // Orthogonal centroids → cosine 0.0 → below threshold → no merge.
        registry.add(cluster("a", new float[] { 1.0f, 0.0f }, 10));
        registry.add(cluster("b", new float[] { 0.0f, 1.0f }, 10));

        var stats = rebalancer.run();

        assertEquals(2, registry.size());
        assertEquals(0, stats.merges());
    }

    @Test
    void run_returnsStatsRecord() {
        registry.add(cluster("a", new float[] { 1f, 0f }, 1));
        var stats = rebalancer.run();
        assertNotNull(stats);
        assertTrue(stats.passes() >= 1);
        assertTrue(stats.merges() >= 0);
        assertTrue(stats.outliers() >= 0);
    }

    @Test
    void run_doesNotPruneFreshSingletons() {
        // Young single-thread cluster with 0 comments must survive — the
        // outlier rule only triggers after OUTLIER_AGE.
        registry.add(cluster("a", new float[] { 1f, 0f }, 0));
        var stats = rebalancer.run();
        assertEquals(1, registry.size());
        assertEquals(0, stats.outliers());
    }
}
