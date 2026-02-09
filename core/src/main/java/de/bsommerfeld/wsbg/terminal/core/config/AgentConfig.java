package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;

public class AgentConfig {

    @Key("agent.power-mode")
    @Comment("Enable Power Mode (uses 12b models instead of 4b) (default: false)")
    private boolean powerMode = false;

    @Key("ollama.vision-model")
    @Comment("Ollama Model Name for Vision/OCR (default: glm-ocr:latest)")
    private String visionModel = "glm-ocr:latest";

    @Key("ollama.embedding-model")
    @Comment("Ollama Model Name for Embeddings (default: nomic-embed-text-v2-moe:latest)")
    private String embeddingModel = "nomic-embed-text-v2-moe:latest";

    @Key("ui.allow-graph-view")
    @Comment("Enable the 3D Graph View feature (default: true)")
    private boolean allowGraphView = true;

    public boolean isPowerMode() {
        return powerMode;
    }

    public void setPowerMode(boolean powerMode) {
        this.powerMode = powerMode;
    }

    public String getVisionModel() {
        return visionModel;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
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
