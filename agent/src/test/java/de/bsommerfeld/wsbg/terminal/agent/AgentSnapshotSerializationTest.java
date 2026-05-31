package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.agent.AgentSnapshotStore.AgentSnapshot;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the agent snapshot round-trips losslessly through Jackson and that
 * a restored {@link InvestigationCluster} matches the original verbatim — the
 * whole point of full persistence (so a restart resumes the exact prior state,
 * including which images were already shown).
 */
class AgentSnapshotSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void clusterRoundTrip_isLossless() throws Exception {
        RedditThread thread = new RedditThread(
                "t3_abc", "wallstreetbetsGER", "NOW Rakete 🚀", "u/kartoffel",
                "Body höher", 1780000000L, "/r/x/comments/abc/now/",
                42, 0.9, 7, 1780000500L, List.of("https://i.redd.it/x.jpeg"), null);
        Embedding embedding = Embedding.from(new float[] {0.11f, -0.22f, 0.33f, 0.44f});

        InvestigationCluster cluster = new InvestigationCluster(thread, embedding);
        cluster.addUpdate(new RedditThread("t3_def", "wallstreetbetsGER", "NOW läuft", "u/b",
                "mehr", 1780000100L, "/r/x/comments/def/now2/", 10, 0.8, 2, 1780000200L,
                List.of(), null), 10, 2, Embedding.from(new float[] {0.1f, -0.2f, 0.3f, 0.4f}));
        cluster.shownImageUrls.add("https://i.redd.it/x.jpeg");
        cluster.headlineCount = 2;
        cluster.currentSignificance = 17.5;

        AgentRepository repo = new AgentRepository();
        repo.saveHeadline("t3_abc", "NOW +606%", "ctx");

        AgentSnapshot original = new AgentSnapshot(
                Instant.now().getEpochSecond(),
                Map.of("https://i.redd.it/x.jpeg", "grüner Gewinn-Screenshot"),
                repo.getAllHeadlines(),
                List.of(cluster.toSnapshot()));

        String json = mapper.writeValueAsString(original);
        AgentSnapshot back = mapper.readValue(json, AgentSnapshot.class);

        assertEquals(1, back.clusters().size());
        assertEquals(1, back.headlines().size());
        assertEquals("grüner Gewinn-Screenshot", back.visionCache().get("https://i.redd.it/x.jpeg"));

        // Rebuild the cluster from the deserialized snapshot and compare verbatim.
        InvestigationCluster restored = new InvestigationCluster(back.clusters().get(0));
        assertEquals("t3_abc", restored.id);
        assertEquals("NOW Rakete 🚀", restored.initialTitle);
        assertEquals(cluster.threadCount, restored.threadCount);
        assertEquals(cluster.totalScore, restored.totalScore);
        assertEquals(cluster.totalComments, restored.totalComments);
        assertEquals(2, restored.headlineCount);
        assertEquals(17.5, restored.currentSignificance);
        assertArrayEquals(cluster.centroid().vector(), restored.centroid().vector(), 1e-6f);
        assertEquals(cluster.activeThreadIds, restored.activeThreadIds);
        assertTrue(restored.shownImageUrls.contains("https://i.redd.it/x.jpeg"));
        assertEquals(cluster.tickers, restored.tickers);
    }
}
