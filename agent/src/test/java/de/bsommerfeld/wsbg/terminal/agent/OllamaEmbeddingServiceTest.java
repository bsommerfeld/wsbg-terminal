package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The cosine math behind {@link OllamaEmbeddingService} (and thus every embedding
 * consumer's similarity). Pure, model-free — the model's behaviour is checked in
 * EmbeddingServiceIT.
 */
class OllamaEmbeddingServiceTest {

    @Test
    void identicalVectorsScoreOne() {
        float[] v = {1f, 2f, 3f, 4f};
        assertEquals(1.0, OllamaEmbeddingService.cosine(v, v), 1e-9);
    }

    @Test
    void orthogonalVectorsScoreZero() {
        assertEquals(0.0, OllamaEmbeddingService.cosine(new float[]{1f, 0f}, new float[]{0f, 1f}), 1e-9);
    }

    @Test
    void oppositeVectorsClampToZero() {
        // Raw cosine is -1; clamped to [0,1] so a "negative" similarity never confuses callers.
        assertEquals(0.0, OllamaEmbeddingService.cosine(new float[]{1f, 0f}, new float[]{-1f, 0f}), 1e-9);
    }

    @Test
    void emptyOrMismatchedDimensionsAreZero() {
        assertEquals(0.0, OllamaEmbeddingService.cosine(new float[0], new float[]{1f}), 1e-9);
        assertEquals(0.0, OllamaEmbeddingService.cosine(new float[]{1f, 2f}, new float[]{1f}), 1e-9);
    }
}
