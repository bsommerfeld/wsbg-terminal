package de.bsommerfeld.wsbg.terminal.core.config;

/**
 * Registry of external (Ollama) AI models used by the terminal.
 *
 * <p>
 * The deployment is single-model: {@link #REASONING_POWER} (gemma4:e4b) serves
 * Chat, Vision, and the Editorial Agent in one resident model, with
 * {@link #EMBEDDING} for cluster centroids. There are deliberately no
 * swappable alternatives — the model choice is managed centrally, not exposed
 * to end users.
 */
public enum Model {
    /** gemma4:e4b — the one multimodal (Text+Image) model. Drives Chat, the
     *  editorial agent, and vision in a single resident runner. The MLX build
     *  (gemma4:e4b-mlx) is deliberately NOT used: its published Ollama tag is
     *  text-only (the vision encoder is stripped), which would force a second
     *  model just for image analysis. */
    REASONING_POWER("gemma4:e4b", "gemma4", 0.2),

    /** embeddinggemma — Google's multilingual embedding model (308M, 768d).
     *  Replaces nomic-embed-text-v2-moe as of 2026-05-28: better German +
     *  finance-jargon recall, same dimensionality so no centroid migration. */
    EMBEDDING("embeddinggemma:latest", "embeddinggemma", 0.0);

    private final String modelName;
    private final String familyPrefix;
    private final double temperature;

    Model(String modelName, String familyPrefix, double temperature) {
        this.modelName = modelName;
        this.familyPrefix = familyPrefix;
        this.temperature = temperature;
    }

    public String getModelName() {
        return modelName;
    }

    public String getFamilyPrefix() {
        return familyPrefix;
    }

    public double getTemperature() {
        return temperature;
    }
}
