package de.bsommerfeld.wsbg.terminal.core.config;

/**
 * Registry of external (Ollama) AI models used by the terminal.
 *
 * <p>
 * The deployment is single-model: {@link #REASONING_POWER} serves Chat and
 * the Editorial Agent in one resident model. Only the concrete TAG varies —
 * the hardware-based model choice ({@code agent.model-tag}, gemma4:e2b..26b
 * plus the Nemotron rung above them, with -mlx twins on Apple Silicon)
 * overrides the default tag via {@code AgentConfig.resolveModelTag()}. Exactly
 * one model is ever resident; the ladder only decides WHICH.
 */
public enum Model {
    /** gemma4:e4b — the default model and the family anchor. Drives Chat and
     *  the editorial agent in a single resident runner. Other
     *  model tiers (gemma4:e2b..26b, -mlx builds on Apple Silicon) are selectable via
     *  agent.model-tag; the launcher installs whatever tag is chosen. */
    REASONING_POWER("gemma4:e4b", "gemma4", 0.2);

    /**
     * Whether {@code tag} names a family the app deploys - the gate on
     * {@code agent.model-tag}. Family-level, not tag-level: a sibling size
     * within a deployed family stays selectable, a foreign model name never
     * reaches the model factory. Delegates to the ONE shared catalog
     * ({@code updater-core}) - this used to be a hand-maintained mirror of the
     * launcher's list, which is exactly the kind of register that drifts.
     */
    public static boolean isDeployedFamily(String tag) {
        return de.bsommerfeld.updater.catalog.ModelCatalog.isDeployedFamily(tag);
    }

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
