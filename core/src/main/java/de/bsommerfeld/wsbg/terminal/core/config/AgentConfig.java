package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;

/**
 * AI Agent configuration. Only runtime-toggleable flags belong here —
 * model names live in {@link Model}.
 */
public class AgentConfig {

    @Key("agent.editorial-model")
    @Comment("Editorial agent reasoning model. REASONING_POWER (gemma4:e4b) — a single "
            + "multimodal model that also serves vision, so only one model stays resident. "
            + "The model choice is managed centrally; this stays fixed.")
    private String editorialModel = "REASONING_POWER";

    @Key("agent.context-tokens")
    @Comment("Context window (num_ctx) in tokens for the editorial-agent + vision model. "
            + "Ollama silently defaults to 4096, which is tight for the agent's multi-cluster "
            + "tool loop (system prompt + a getCluster report + accumulated tool results). 8192 "
            + "doubles the headroom and stays comfortable on 16 GB unified memory together with "
            + "OLLAMA_KV_CACHE_TYPE=q8_0 + flash attention. Raise to 16384 on 32 GB+ machines. "
            + "Agent and vision share this value so they share one Ollama runner (one model in "
            + "memory).")
    private int contextTokens = 8192;

    public String getEditorialModel() {
        return editorialModel;
    }

    public void setEditorialModel(String editorialModel) {
        this.editorialModel = editorialModel;
    }

    public int getContextTokens() {
        return contextTokens;
    }

    public void setContextTokens(int contextTokens) {
        this.contextTokens = contextTokens;
    }

    /**
     * Resolves the configured editorial-model string to a {@link Model}.
     * Falls back to {@link Model#REASONING_POWER} (the multimodal gemma4:e4b
     * default) on any unknown or stale value — e.g. a "REASONING_AGENT_POWER"
     * or "REASONING_POWER_MLX" left in an older config now degrades gracefully
     * to the single-model default.
     */
    public Model resolveEditorialModel() {
        try {
            if (Model.valueOf(editorialModel) == Model.REASONING_POWER) {
                return Model.REASONING_POWER;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return Model.REASONING_POWER;
    }
}
