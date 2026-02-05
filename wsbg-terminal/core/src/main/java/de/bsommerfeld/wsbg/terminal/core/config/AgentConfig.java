package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;

public class AgentConfig {

    @Key("ollama.model")
    @Comment("Ollama Model Name for Analysis/Reasoning (default: llama3.1:8b)")
    private String ollamaModel = "llama3.1:8b";

    @Key("ollama.translator-model")
    @Comment("Ollama Model Name for Translation (default: translategemma:latest)")
    private String translatorModel = "translategemma:latest";

    @Key("ollama.vision-model")
    @Comment("Ollama Model Name for Vision/OCR (default: glm-ocr:latest)")
    private String visionModel = "glm-ocr:latest";

    @Key("ollama.embedding-model")
    @Comment("Ollama Model Name for Embeddings (default: nomic-embed-text-v2-moe:latest)")
    private String embeddingModel = "nomic-embed-text-v2-moe:latest";

    @Key("subreddits")
    @Comment("List of subreddits to scan")
    private java.util.List<String> subreddits = java.util.List.of("wallstreetbetsGER");

    public String getOllamaModel() {
        return ollamaModel;
    }

    public String getTranslatorModel() {
        return translatorModel;
    }

    public String getVisionModel() {
        return visionModel;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public java.util.List<String> getSubreddits() {
        return subreddits;
    }

    public void setOllamaModel(String ollamaModel) {
        this.ollamaModel = ollamaModel;
    }

    public void setTranslatorModel(String translatorModel) {
        this.translatorModel = translatorModel;
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public void setSubreddits(java.util.List<String> subreddits) {
        this.subreddits = subreddits;
    }
}
