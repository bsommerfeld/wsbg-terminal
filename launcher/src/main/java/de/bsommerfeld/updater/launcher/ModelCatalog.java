package de.bsommerfeld.updater.launcher;

/**
 * The selectable gemma4 tiers, ordered small → large. This is the launcher-side
 * source of truth for the hardware-based model recommendation: every tier knows
 * its Ollama tags (base + Apple-Silicon MLX twin), its approximate download
 * size, and the RAM bar it needs — {@link #recommend(long)} walks the ladder,
 * {@link #fitFor(long)} grades each tier for the future model-choice UI.
 *
 * <p>
 * RAM bars are deliberately conservative: the model must share the machine with
 * the OS, the terminal itself (JCEF is a full Chromium), and the KV cache
 * ({@code num_ctx} 8192, q8_0). "min" = runs, but the machine will feel it;
 * "recommended" = comfortable daily use. Total RAM is the v1 signal (see
 * {@link HardwareProbe}); the audience is non-technical, so the future UI
 * renders these as plain verdicts, never as these raw numbers.
 */
enum ModelCatalog {

    // MoE on-device tiers (128K ctx) — small active params, friendly to weak GPUs.
    E2B("gemma4:e2b", 7.2, 6.5, 8, 12),
    E4B("gemma4:e4b", 9.6, 8.8, 12, 16),
    // Dense 12B (256K ctx) — smaller on disk than e4b (tighter quant) but a
    // dense forward pass, so it wants noticeably more machine than its size suggests.
    B12("gemma4:12b", 7.6, 7.7, 16, 24),
    // 26B is MoE with 4B active; 31B is the dense flagship.
    B26("gemma4:26b", 18.0, 18.0, 32, 40),
    B31("gemma4:31b", 20.0, 19.0, 32, 48);

    /**
     * The default TIER whenever the user has not chosen one — platform-suffixed
     * via {@link #tagFor(boolean)}, so Apple Silicon standardly runs the MLX
     * build. The recommendation is advisory until a real choice is made (an
     * install must never silently swap its TIER because a RAM probe said so).
     */
    static final ModelCatalog DEFAULT = E4B;

    /** Suitability verdict for one tier on one machine. */
    enum Fit { COMFORTABLE, TIGHT, TOO_LARGE }

    private final String baseTag;
    private final double baseDiskGb;
    private final double mlxDiskGb;
    private final int minRamGb;
    private final int recommendedRamGb;

    ModelCatalog(String baseTag, double baseDiskGb, double mlxDiskGb,
            int minRamGb, int recommendedRamGb) {
        this.baseTag = baseTag;
        this.baseDiskGb = baseDiskGb;
        this.mlxDiskGb = mlxDiskGb;
        this.minRamGb = minRamGb;
        this.recommendedRamGb = recommendedRamGb;
    }

    /** The concrete Ollama tag for the platform: MLX twin on Apple Silicon. */
    String tagFor(boolean appleSilicon) {
        return appleSilicon ? baseTag + "-mlx" : baseTag;
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
