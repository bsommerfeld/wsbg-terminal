package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.Model;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production {@link EmbeddingService} backed by the resident {@code embeddinggemma}
 * model on the isolated Ollama. Embeddings are cached per text — the same headline
 * or subject name is embedded once per process — so repeated similarity checks are
 * cheap. One runner is shared with {@code ClusterEngine} (same model name).
 */
@Singleton
public final class OllamaEmbeddingService implements EmbeddingService {

    private final EmbeddingModel model;
    private final Map<String, float[]> cache = new ConcurrentHashMap<>();

    @Inject
    public OllamaEmbeddingService() {
        this.model = OllamaEmbeddingModel.builder()
                .baseUrl(AgentBrain.OLLAMA_BASE_URL)
                .modelName(Model.EMBEDDING.getModelName())
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) return new float[0];
        return cache.computeIfAbsent(text, t -> model.embed(t).content().vector());
    }

    @Override
    public double similarity(String a, String b) {
        return cosine(embed(a), embed(b));
    }

    /** Cosine of two vectors, clamped to {@code [0,1]} (negatives → 0). */
    static double cosine(float[] x, float[] y) {
        if (x.length == 0 || y.length == 0 || x.length != y.length) return 0.0;
        double dot = 0, nx = 0, ny = 0;
        for (int i = 0; i < x.length; i++) {
            dot += (double) x[i] * y[i];
            nx += (double) x[i] * x[i];
            ny += (double) y[i] * y[i];
        }
        if (nx == 0 || ny == 0) return 0.0;
        double cos = dot / (Math.sqrt(nx) * Math.sqrt(ny));
        return Math.max(0.0, Math.min(1.0, cos));
    }
}
