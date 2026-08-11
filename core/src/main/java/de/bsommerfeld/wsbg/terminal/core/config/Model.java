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
     * The families the app deploys tags from: the anchor above plus the
     * Nemotron rung at the top of the ladder. Mirrors the launcher's model
     * catalog - the two must agree, or the launcher installs a tag the runtime
     * then refuses and silently falls back to the anchor, leaving the machine
     * with a model it never downloaded. Adding a family stays a one-line
     * change here; it also needs its resident size in
     * {@code AgentConfig.weightsGbFor}, or the context window is sized against
     * the wrong weights.
     */
    private static final String[] DEPLOYED_FAMILIES = {"gemma4:", "nemotron-3.5-lightning:"};

    /**
     * Whether {@code tag} names a family the app deploys - the gate on
     * {@code agent.model-tag}. Family-level, not tag-level: a sibling size
     * within a deployed family stays selectable, a foreign model name never
     * reaches the model factory.
     */
    public static boolean isDeployedFamily(String tag) {
        for (String family : DEPLOYED_FAMILIES) {
            if (tag.startsWith(family)) return true;
        }
        return false;
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
