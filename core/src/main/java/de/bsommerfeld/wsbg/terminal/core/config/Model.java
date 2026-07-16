package de.bsommerfeld.wsbg.terminal.core.config;

/**
 * Registry of external (Ollama) AI models used by the terminal.
 *
 * <p>
 * The deployment is single-model: {@link #REASONING_POWER} serves Chat and
 * the Editorial Agent in one resident model. The FAMILY is fixed (gemma4);
 * only the concrete TAG within it varies — the hardware-based model choice
 * ({@code agent.model-tag}, gemma4:e2b..31b with -mlx twins on Apple Silicon)
 * overrides the default tag via {@code AgentConfig.resolveModelTag()}. There is
 * deliberately no second model family.
 */
public enum Model {
    /** gemma4:e4b — the default model and the family anchor. Drives Chat and
     *  the editorial agent in a single resident runner. Other
     *  gemma4 tiers (e2b..31b, -mlx builds on Apple Silicon) are selectable via
     *  agent.model-tag; the launcher installs whatever tag is chosen. */
    REASONING_POWER("gemma4:e4b", "gemma4", 0.2);

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
