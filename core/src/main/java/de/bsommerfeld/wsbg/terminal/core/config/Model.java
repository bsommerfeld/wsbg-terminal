package de.bsommerfeld.wsbg.terminal.core.config;

/**
 * Registry of external (Ollama) AI models used by the terminal.
 */
public enum Model {
    REASONING("gemma4:e2b", "gemma4", 0.2),
    REASONING_POWER("gemma4:e4b", "gemma4", 0.2),
    EMBEDDING("nomic-embed-text-v2-moe:latest", "nomic-embed-text-v2-moe", 0.0);

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
