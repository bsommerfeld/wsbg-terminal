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

    @Key("agent.model-tag")
    @Comment("Ollama model tag override for the one resident gemma4 model (gemma4:e2b..31b, "
            + "-mlx twins on Apple Silicon). Empty = the managed default tier: gemma4:e4b, "
            + "as gemma4:e4b-mlx on Apple Silicon. The launcher reads this key too and "
            + "installs the matching model on the next start, so runtime and installed model "
            + "stay in sync. Only gemma4-family tags are honored; anything else degrades to "
            + "the default. Written by the future model-choice UI — the hardware "
            + "recommendation lives in the launcher's hardware-recommendation.json.")
    private String modelTag = "";

    @Key("agent.identity-desk")
    @Comment("The AI identity desk (border control): subject identity is decided by ONE "
            + "gemma4 judgment over the combined Yahoo + Lang & Schwarz search facts and the "
            + "verdict is stamped (ISIN + venue instrument) onto the price lookup. Off = the "
            + "legacy deterministic guard tower decides alone (the desk's outage fallback). "
            + "Live-toggleable; keep on.")
    private boolean identityDesk = true;

    public String getEditorialModel() {
        return editorialModel;
    }

    public boolean isIdentityDesk() {
        return identityDesk;
    }

    public void setIdentityDesk(boolean identityDesk) {
        this.identityDesk = identityDesk;
    }

    public void setEditorialModel(String editorialModel) {
        this.editorialModel = editorialModel;
    }


    /**
     * Resolves the configured editorial-model string to a {@link Model}.
     * Falls back to {@link Model#REASONING_POWER} (the multimodal gemma4:e4b
     * default) on any unknown or stale value — e.g. a "REASONING_AGENT_POWER"
     * or "REASONING_POWER_MLX" left in an older config now degrades gracefully
     * to the single-model default.
     */
    public Model resolveEditorialModel() {
        return Model.REASONING_POWER;
    }

    public String getModelTag() {
        return modelTag;
    }

    public void setModelTag(String modelTag) {
        this.modelTag = modelTag;
    }

    /**
     * Resolves the concrete Ollama tag the one resident model runs as: the
     * user's {@code agent.model-tag} choice when it names a gemma4-family tag,
     * else the managed default — {@link Model#REASONING_POWER}, with the MLX
     * build as the standard on Apple Silicon (mirrors the launcher's
     * {@code ModelSelection}, so runtime and installed model agree without any
     * handshake). Family-gated so a typo or foreign model name can never reach
     * the model factory — the factory's own family fallback then still guards
     * against a chosen tag that is not actually installed yet.
     */
    public String resolveModelTag() {
        String tag = modelTag == null ? "" : modelTag.strip().toLowerCase();
        if (tag.startsWith(Model.REASONING_POWER.getFamilyPrefix() + ":")) {
            return tag;
        }
        String base = Model.REASONING_POWER.getModelName();
        return isAppleSilicon() ? base + "-mlx" : base;
    }

    private static boolean isAppleSilicon() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64"));
    }

    /**
     * The effective context window (num_ctx) for the one resident model —
     * fully automatic, scaled to this machine's physical memory (user mandate
     * 2026-07-16: no maintained knob; the machine decides). The 8k floor was
     * sized for 16 GB end-user machines with OLLAMA_KV_CACHE_TYPE=q8_0 +
     * flash attention; a bigger window buys headroom per pass, not a
     * different pipeline. Agent and vision share the value so they share one
     * Ollama runner (num_ctx is a load-time parameter).
     */
    public int resolveContextTokens() {
        return contextTokensFor(totalPhysicalMemoryBytes());
    }

    /** The memory tiers behind auto mode - package-visible for tests. */
    static int contextTokensFor(long totalMemoryBytes) {
        long gb = totalMemoryBytes / (1L << 30);
        if (gb >= 64) return 24576;
        if (gb >= 32) return 16384;
        return 8192;
    }

    /** Total physical memory, or {@code 0} when the platform bean is unavailable. */
    private static long totalPhysicalMemoryBytes() {
        try {
            var bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize();
            }
        } catch (Throwable ignored) {
            // fall through - auto mode then keeps the conservative floor
        }
        return 0;
    }
}
