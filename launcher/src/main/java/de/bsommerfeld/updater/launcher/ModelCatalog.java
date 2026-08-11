package de.bsommerfeld.updater.launcher;

/**
 * The selectable model tiers, ordered small → large. This is the launcher-side
 * source of truth for the hardware-based model recommendation: every tier knows
 * its display name, its Ollama tags (base + Apple-Silicon MLX twin), its
 * approximate download size, and the RAM bar it needs — {@link #recommend(long)}
 * walks the ladder, {@link #fitFor(long)} grades each tier for the model-choice
 * screen.
 *
 * <p>
 * The ladder spans more than one model family, so every tier carries a
 * {@link #displayName()} — the label the choice screen shows. It is the only
 * thing on a row that names the model at all, and it is deliberately NOT the
 * raw Ollama tag: a tag is a package coordinate, a name is what a user
 * recognises when they come back to the screen a year later.
 *
 * <p>
 * RAM bars are deliberately conservative: the model must share the machine with
 * the OS, the terminal itself (JCEF is a full Chromium), and the KV cache
 * ({@code num_ctx} 8192, q8_0). "min" = runs, but the machine will feel it;
 * "recommended" = comfortable daily use. Total RAM is the v1 signal (see
 * {@link HardwareProbe}); the audience is non-technical, so the choice UI
 * renders these as plain verdicts, never as these raw numbers.
 */
enum ModelCatalog {

    // MoE on-device tiers (128K ctx) — small active params, friendly to weak GPUs.
    E2B("Gemma 4 · e2b", "gemma4:e2b", 7.2, 6.5, 8, 12, 3, 10),
    E4B("Gemma 4 · e4b", "gemma4:e4b", 9.6, 8.8, 12, 16, 5, 8),
    // 26B is MoE with 4B active — the strongest gemma4 rung. A dense 12B tier
    // stood between e4b and this one and was struck 2026-08-11: it was the
    // slowest rung on BOTH halves (measured ~480 tok/s prefill on Apple
    // Silicon where the MoE tiers manage 1150–1600, because a dense forward
    // pass has no inactive experts to skip), and a ladder rung that costs more
    // machine AND more waiting than the rungs around it has nothing to offer.
    // A 35B tier (Qwen3.6) briefly stood above it and was struck the same day:
    // measured on a 48 GB Apple-Silicon machine at the app's own num_ctx,
    // sustained generation collapsed from ~78 to ~18 tok/s across five
    // consecutive calls as the 21 GB resident set pushed the machine into
    // swap, while this 26B held 83–87 tok/s flat.
    B26("Gemma 4 · 26b", "gemma4:26b", 18.0, 18.0, 32, 40, 9, 7),
    // NVIDIA's 30B MoE with only 3B active — built for the execution layer of
    // always-on agents, which is exactly what this terminal runs it as. It
    // carries the largest resident set on the ladder, but fewer active
    // parameters than the 26B, so it is the rare rung that is both bigger and
    // faster than the one below it — which is exactly why it may stand above
    // the 26B at all: the top of this ladder has to win on quality WITHOUT
    // giving the speed back (the rule that struck the 35B and the dense 12B).
    NEMOTRON_LIGHTNING("Nemotron 3.5 Lightning", "nemotron-3.5-lightning:30b",
            25.0, 23.0, 32, 48, 10, 9);

    /**
     * The default TIER whenever the user has not chosen one — platform-suffixed
     * via {@link #tagFor(boolean)}, so Apple Silicon standardly runs the MLX
     * build. The recommendation is advisory until a real choice is made (an
     * install must never silently swap its TIER because a RAM probe said so).
     */
    static final ModelCatalog DEFAULT = E4B;

    /** Suitability verdict for one tier on one machine. */
    enum Fit { COMFORTABLE, TIGHT, TOO_LARGE }

    private final String displayName;
    private final String baseTag;
    private final double baseDiskGb;
    private final double mlxDiskGb;
    private final int minRamGb;
    private final int recommendedRamGb;
    private final int quality;
    private final int speed;

    ModelCatalog(String displayName, String baseTag, double baseDiskGb, double mlxDiskGb,
            int minRamGb, int recommendedRamGb, int quality, int speed) {
        this.displayName = displayName;
        this.baseTag = baseTag;
        this.baseDiskGb = baseDiskGb;
        this.mlxDiskGb = mlxDiskGb;
        this.minRamGb = minRamGb;
        this.recommendedRamGb = recommendedRamGb;
        this.quality = quality;
        this.speed = speed;
    }

    /**
     * The human-readable name of this tier — a product name, never translated
     * and never derived from the tag. It is what the choice screen puts on the
     * row so a ladder spanning several model families stays recognisable.
     */
    String displayName() {
        return displayName;
    }

    /** The concrete Ollama tag for the platform: MLX twin on Apple Silicon. */
    String tagFor(boolean appleSilicon) {
        return appleSilicon ? baseTag + "-mlx" : baseTag;
    }

    /**
     * Whether {@code tag} belongs to a family this catalog deploys — the gate
     * on a hand-written {@code agent.model-tag}. Family-level, not tag-level:
     * a sibling size within a deployed family stays installable, a foreign
     * family must never reach {@code ollama pull} verbatim.
     */
    static boolean isDeployedFamily(String tag) {
        for (ModelCatalog tier : values()) {
            int colon = tier.baseTag.indexOf(':');
            if (colon > 0 && tag.startsWith(tier.baseTag.substring(0, colon + 1))) return true;
        }
        return false;
    }

    double diskGbFor(boolean appleSilicon) {
        return appleSilicon ? mlxDiskGb : baseDiskGb;
    }

    int minRamGb() {
        return minRamGb;
    }

    int recommendedRamGb() {
        return recommendedRamGb;
    }

    /**
     * Output quality on a 0–10 scale — the non-technical translation of the
     * parameter count, anchored on published benchmark spreads (MMLU Pro /
     * AIME / coding) between the tiers. Relative honesty is the contract: a
     * "9" must genuinely sit near the "10" and far above a "5".
     */
    int quality() {
        return quality;
    }

    /**
     * Generation speed on a 0–10 scale, anchored on measured tokens/sec on
     * the same machine class. Deliberately NOT monotonic with size: speed
     * follows ACTIVE parameters, so the 30B MoE (3B active) at the top of the
     * ladder outruns the 26B below it (4B active) — exactly the non-obvious
     * fact this scale exists to convey.
     */
    int speed() {
        return speed;
    }

    Fit fitFor(long totalRamGb) {
        if (totalRamGb >= recommendedRamGb) return Fit.COMFORTABLE;
        if (totalRamGb >= minRamGb) return Fit.TIGHT;
        return Fit.TOO_LARGE;
    }

    /**
     * The largest tier the machine runs comfortably. An unprobeable machine
     * ({@code totalRamGb == 0}) gets the safe default rather than the floor —
     * a probe failure says nothing about the hardware.
     */
    static ModelCatalog recommend(long totalRamGb) {
        if (totalRamGb <= 0) return DEFAULT;
        ModelCatalog best = E2B;
        for (ModelCatalog tier : values()) {
            if (tier.fitFor(totalRamGb) == Fit.COMFORTABLE) best = tier;
        }
        return best;
    }
}
