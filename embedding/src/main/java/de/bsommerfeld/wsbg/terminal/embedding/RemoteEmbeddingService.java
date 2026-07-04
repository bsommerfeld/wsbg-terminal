package de.bsommerfeld.wsbg.terminal.embedding;

import de.bsommerfeld.wsbg.terminal.core.config.LlmConfig;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link EmbeddingService} backed by an OpenAI-compatible embeddings endpoint —
 * the "bring your own API key" counterpart to {@link OllamaEmbeddingService}.
 *
 * <p>Because the base URL is configurable, this one impl serves OpenAI itself,
 * the Vercel AI Gateway, OpenRouter and any other OpenAI-compatible embeddings
 * API. It is selected in {@code AppModule} when the LLM backend is remote (and,
 * for the Anthropic backend, when a remote embeddings endpoint is configured —
 * Anthropic has no embeddings API of its own).
 *
 * <p>Embeddings are cached per text, exactly like the Ollama impl, so repeated
 * similarity checks on the same headline/subject name don't re-hit the API.
 */
public final class RemoteEmbeddingService implements EmbeddingService {

    private final EmbeddingModel model;
    private final Map<String, float[]> cache = new ConcurrentHashMap<>();

    public RemoteEmbeddingService(LlmConfig llm) {
        this.model = OpenAiEmbeddingModel.builder()
                .baseUrl(llm.effectiveEmbedBaseUrl())
                .apiKey(llm.effectiveEmbedApiKey())
                .modelName(llm.getEmbedModel())
                .timeout(Duration.ofSeconds(60))
                .maxRetries(2)
                .build();
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) return new float[0];
        return cache.computeIfAbsent(text, t -> model.embed(t).content().vector());
    }

    @Override
    public double similarity(String a, String b) {
        // Reuse the shared, clamped cosine so both embedding backends score identically.
        return OllamaEmbeddingService.cosine(embed(a), embed(b));
    }
}
