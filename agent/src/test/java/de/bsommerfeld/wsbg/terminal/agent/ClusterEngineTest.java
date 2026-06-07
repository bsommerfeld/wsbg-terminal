package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.ClusterEngine.AssignOutcome;
import de.bsommerfeld.wsbg.terminal.agent.ClusterEngine.Kind;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Clustering decision logic, unit-tested through the {@link EmbeddingService} seam
 * with a deterministic fake (bag-of-words vectors) — NO Ollama. Proves the core
 * behaviour the live wire depends on: similar threads join one cluster, dissimilar
 * threads start a new one, and a shared $ticker forces a merge regardless of cosine.
 * The embedder's real-world quality is checked separately in EmbeddingServiceIT.
 */
class ClusterEngineTest {

    private static ClusterEngine engine(ClusterRegistry reg) {
        return new ClusterEngine(reg, new GlobalConfig(), new FakeEmbeddingService());
    }

    private static RedditThread thread(String id, String title, String body) {
        long now = System.currentTimeMillis() / 1000;
        return new RedditThread(id, "wallstreetbetsGER", title, "[user]", body, now,
                "/r/wallstreetbetsGER/comments/" + id, 0, 0.5, 0);
    }

    @Test
    void similarThreadsJoinOneCluster() {
        ClusterRegistry reg = new ClusterRegistry();
        ClusterEngine e = engine(reg);
        e.assign(thread("t3_a", "NVIDIA Rakete Mond Hebel all in", "NVIDIA Hebel Rakete Mond"), 0, 0, "");
        AssignOutcome out = e.assign(
                thread("t3_b", "NVIDIA Rakete Hebel Mond", "NVIDIA all in Rakete heute"), 0, 0, "");
        assertEquals(Kind.JOIN_COSINE, out.kind(), "near-identical wording must join the cluster");
        assertEquals(1, reg.size(), "only one cluster");
    }

    @Test
    void dissimilarThreadStartsNewCluster() {
        ClusterRegistry reg = new ClusterRegistry();
        ClusterEngine e = engine(reg);
        e.assign(thread("t3_a", "NVIDIA Rakete Mond Hebel", "NVIDIA Hebel Rakete"), 0, 0, "");
        AssignOutcome out = e.assign(
                thread("t3_c", "Goldpreis Inflation Zinsen EZB Anleihen", "Gold EZB Zinsen Inflation"), 0, 0, "");
        assertEquals(Kind.NEW, out.kind(), "an unrelated topic must start its own cluster");
        assertEquals(2, reg.size(), "two clusters");
    }

    @Test
    void sharedTickerForcesJoinEvenWhenTextDiffers() {
        ClusterRegistry reg = new ClusterRegistry();
        ClusterEngine e = engine(reg);
        e.assign(thread("t3_a", "$NVDA Rakete Mond Hebel", "all in"), 0, 0, "");
        // Completely different wording, but the same $ticker → ticker fast-path merges.
        AssignOutcome out = e.assign(
                thread("t3_d", "$NVDA Goldpreis Inflation Zinsen EZB komplett anderer Text", "x"), 0, 0, "");
        assertEquals(Kind.JOIN_TICKER, out.kind(), "shared $ticker must force the merge");
        assertEquals(1, reg.size(), "one cluster via ticker overlap");
    }
}
