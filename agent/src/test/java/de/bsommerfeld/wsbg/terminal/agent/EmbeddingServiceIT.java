package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real {@code embeddinggemma} behaviour behind {@link EmbeddingService} — the
 * layer the fake unit tests deliberately cannot check: does the model actually
 * score same-topic text higher than off-topic, and does {@link
 * EmbeddingService#bestMatch} pick the right candidate? This is what validates the
 * thresholds the collator / tier-2 / name-matcher rely on.
 *
 * <p>Auto-runs locally when Ollama is available; skips in CI.
 */
@Tag("integration")
@EnabledIf("de.bsommerfeld.wsbg.terminal.agent.OllamaAvailability#available")
class EmbeddingServiceIT {

    private static EmbeddingService emb;

    @BeforeAll
    static void up() {
        OllamaAvailability.ensureOllama();
        emb = new OllamaEmbeddingService();
    }

    @Test
    void sameStoryScoresHigherThanOffTopic() {
        double same = emb.similarity(
                "NVIDIA fällt um 5% — die Apes sehen rot",
                "NVIDIA verliert 5%, der Käfig ist tiefrot");
        double off = emb.similarity(
                "NVIDIA fällt um 5% — die Apes sehen rot",
                "Der Goldpreis steigt nach schwachen US-Arbeitsmarktdaten");
        System.out.printf("same=%.3f off=%.3f%n", same, off);
        assertTrue(same > off, "paraphrase must outrank off-topic (same=" + same + ", off=" + off + ")");
        assertTrue(same >= 0.6, "near-paraphrase should be reasonably high: " + same);
    }

    @Test
    void bestMatchPicksTheClosestCandidate() {
        int i = emb.bestMatch("Rheinmetall",
                List.of("Apple Inc.", "Rheinmetall AG", "Bitcoin"), 0.3);
        assertEquals(1, i, "should pick 'Rheinmetall AG'");
    }

    @Test
    void bestMatchReturnsMinusOneBelowThreshold() {
        int i = emb.bestMatch("Rheinmetall",
                List.of("Bananenbrot Rezept", "Wetter morgen", "Fußball Ergebnisse"), 0.6);
        assertEquals(-1, i, "no candidate is about Rheinmetall");
    }
}
