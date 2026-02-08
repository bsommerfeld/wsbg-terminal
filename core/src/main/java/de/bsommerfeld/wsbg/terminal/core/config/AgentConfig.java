package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;

public class AgentConfig {

    @Key("ollama.model")
    @Comment("Ollama Model Name for Analysis/Reasoning (default: gemma3:12b)")
    private String ollamaModel = "gemma3:12b";

    @Key("ollama.translator-model")
    @Comment("Ollama Model Name for Translation (default: translategemma:12b)")
    private String translatorModel = "translategemma:12b";

    @Key("ollama.vision-model")
    @Comment("Ollama Model Name for Vision/OCR (default: glm-ocr:latest)")
    private String visionModel = "glm-ocr:latest";

    @Key("ollama.embedding-model")
    @Comment("Ollama Model Name for Embeddings (default: nomic-embed-text-v2-moe:latest)")
    private String embeddingModel = "nomic-embed-text-v2-moe:latest";

    @Key("ui.allow-graph-view")
    @Comment("Enable the 3D Graph View feature (default: true)")
    private boolean allowGraphView = true;

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

    public boolean isAllowGraphView() {
        return allowGraphView;
    }

    public void setAllowGraphView(boolean allowGraphView) {
        this.allowGraphView = allowGraphView;
    }

}
