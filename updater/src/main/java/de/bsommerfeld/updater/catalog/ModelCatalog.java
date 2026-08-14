package de.bsommerfeld.updater.catalog;

/**
 * THE model catalog — the one definition of the selectable model tiers,
 * ordered small → large ("large" meaning the machine a tier needs, the axis
 * {@link #recommend(long)} actually walks; download size mostly follows it but
 * is allowed to zig — the dense Granite 8B downloads less than the MoE e2b yet
 * needs the bigger machine).
 *
 * <p>
 * It lives in {@code updater-core} because that is the one dependency-free
 * module BOTH processes share: the launcher (model choice screen, setup-script
 * tag resolution) and the terminal runtime (via {@code core}). It used to
 * live in the launcher alone, mirrored by hand into the runtime's family gate
 * and weight table — registers that drift silently. The catalog is
 * compiled-in data; only the hardware PROBE is a measurement. Consumers:
 * {@code ModelSelection}/{@code ModelChoicePanel} (launcher),
 * {@code Model}/{@code AgentConfig} (core — deployed-family gate and resident
 * weights). Exactly one definition, nothing left to drift.
 *
 * <p>
 * RAM bars follow ONE rule (user decision 2026-08-13): {@link #minRamGb()} is
 * the honest floor a tier runs on at all, and {@link #recommendedRamGb()} is
 * ALWAYS {@code min + 8} — "comfortable" begins 8 GB above the floor, for
 * every tier, with no hand-set exceptions. "min" = runs, but the machine will
 * feel it; the model must share the machine with the OS, the terminal itself
 * (JCEF is a full Chromium), and the KV cache. The audience is non-technical,
 * so choice UIs render these as plain verdicts, never as raw numbers.
 */
public enum ModelCatalog {

    // The Granite rungs are the FRUGAL half of the ladder: IBM's dense models
    // for the machines where even e4b gets tight, with pronounced tool-use /
    // structured-JSON fidelity for their size. Dense means no expert-skipping
    // in the prefill — at 3B/8B active that barely registers, so the speed
    // scores stay high. No MLX twin exists on the registry (checked against
    // ollama.com/library/granite4.1 on 2026-08-13: only quantization variants),
    // hence hasMlxTwin=false — Apple Silicon runs the base tag.
    GRANITE_3B("Granite 4.1 · 3b", "granite4.1:3b", false, 2.1, 2.1, 2.1, 6, 4, 10),
    E2B("Gemma 4 · e2b", "gemma4:e2b", true, 7.2, 6.5, 6.5, 8, 3, 10),
    // The stronger frugal rung. Evidence for the placement: MMLU 73.84;
    // IFEval 87.1 (practically level with Qwen3.5-9B at 87.2); BFCL V3
    // tool-calling 68.27; IBM measures the 8B on instruction-following and
    // tool-calling on par with the former 32B-MoE Granite 4.0-H-Small.
    // Strengths: RAG, tool use, structured JSON, 12 languages incl. German,
    // Apache 2.0.
    GRANITE_8B("Granite 4.1 · 8b", "granite4.1:8b", false, 5.3, 5.3, 5.3, 10, 6, 8),
    E4B("Gemma 4 · e4b", "gemma4:e4b", true, 9.6, 8.8, 8.8, 12, 5, 8),
    // 26B is MoE with 4B active — the strongest gemma4 rung. A dense 12B tier
    // stood between e4b and this one and was struck 2026-08-11: it was the
    // slowest rung on BOTH halves (measured ~480 tok/s prefill on Apple
    // Silicon where the MoE tiers manage 1150–1600, because a dense forward
    // pass has no inactive experts to skip), and a ladder rung that costs more
    // machine AND more waiting than the rungs around it has nothing to offer.
    // Measured 2026-08-13 on a 48 GB Apple-Silicon machine: 42 of 48 GB in
    // use with this tier active, running unremarkably at 83–87 tok/s.
    B26("Gemma 4 · 26b", "gemma4:26b", true, 18.0, 18.0, 18.0, 32, 9, 7),
    // Qwen's 35B MoE with 3B active (35B-A3B), 256K ctx. ⚠ RE-ADMITTED WITH A
    // WARNING — this tier was struck 2026-08-11 with a measurement that still
    // stands: on a 48 GB Apple-Silicon machine at the app's own num_ctx,
    // sustained generation collapsed from ~78 to ~18 tok/s across five
    // consecutive calls as the 21 GB resident set pushed the machine into
    // swap, while the 26B held 83–87 tok/s flat. The rung is back by user
    // decision (2026-08-13), hung deliberately hard BECAUSE of that
    // measurement: speed 5 is the honest SUSTAINED value, not the first-run
    // figure — do not raise it — and min 48 keeps a 48 GB machine at TIGHT
    // (recommended = 56 under the min+8 rule), so recommend() can never hand
    // it to the machine it collapsed on. residentGb 27: the 21 GB were the
    // set MID-collapse, and MLX's high-water allocator only grows from there
    // (the 26B measured 17 → 25 GB under load); pricing it at 27 puts the
    // 48 GB machine's context window on the 8k floor — which is what the
    // collapse measured — and errs large, never small.
    QWEN_35B("Qwen 3.6 · 35b", "qwen3.6:35b", true, 24.0, 22.0, 27.0, 48, 10, 5),
    // NVIDIA's 30B MoE with only 3B active — built for the execution layer of
    // always-on agents, which is exactly what this terminal runs it as.
    // ⚠ MEASURED DOWN (2026-08-13, 48 GB Apple Silicon): with this tier
    // active the machine sat at 46 of 48 GB and was reported "very slow",
    // while the 26B below it held 42 of 48 GB and ran unremarkably. The old
    // speed 9 was a derivation from "only 3B active", and the old bars
    // (32/48) made recommend() hand this tier to exactly that 48 GB machine
    // as its best choice — the same paper-data failure mode that struck the
    // 35B on 2026-08-11, this time at the top of the ladder. So: speed 6 is
    // the honest on-machine value (above the collapsing 35B, below the flat
    // 26B — active params say nothing once the resident set eats the
    // machine), min 48 puts a 48 GB machine at TIGHT (recommended = 56), and
    // residentGb 28 is the measured effective set (46 total − 12 host
    // reserve − ~6 KV under MLX's high-water allocator), which drops the
    // 48 GB machine's context window to the 8k floor instead of feeding the
    // pressure. Whether the tier earns its keep on a real 64 GB machine is
    // UNMEASURED — do not raise these numbers without that measurement. The
    // tier stays in the catalog by explicit user decision (2026-08-13).
    NEMOTRON_LIGHTNING("Nemotron 3.5 Lightning", "nemotron-3.5-lightning:30b",
            true, 25.0, 23.0, 28.0, 48, 10, 6);

    /**
     * The default TIER whenever the user has not chosen one — platform-suffixed
     * via {@link #tagFor(boolean)}, so Apple Silicon standardly runs the MLX
     * build. The recommendation is advisory until a real choice is made (an
     * install must never silently swap its TIER because a RAM probe said so).
     */
    public static final ModelCatalog DEFAULT = E4B;

    /** "Comfortable" begins this far above the floor — the min+8 rule. */
    private static final int RECOMMENDED_HEADROOM_GB = 8;

    /** Suitability verdict for one tier on one machine. */
    public enum Fit { COMFORTABLE, TIGHT, TOO_LARGE }

    private final String displayName;
    private final String baseTag;
    /**
     * Whether an {@code -mlx} build of this tag exists on the registry. NOT a
     * platform question — a per-tier registry fact: appending {@code -mlx} to
     * a tag that has no such build hands {@code ollama pull} a tag it cannot
     * find, and the install fails. Verify against the tag list on
     * ollama.com/library/&lt;family&gt; before flipping this to true.
     */
    private final boolean hasMlxTwin;
    private final double baseDiskGb;
    private final double mlxDiskGb;
    /**
     * Approximate RESIDENT footprint in GB once the model runs — what
     * {@code AgentConfig} sizes the context window against. NOT the download
     * size: MLX's high-water allocator grows past the fresh load and never
     * shrinks back, so the big rungs carry measured values above their disk
     * sizes. Erring large is the safe direction; guessing small is the one
     * error that reintroduces the swap collapse.
     */
    private final double residentGb;
    private final int minRamGb;
    private final int quality;
    private final int speed;

    ModelCatalog(String displayName, String baseTag, boolean hasMlxTwin,
            double baseDiskGb, double mlxDiskGb, double residentGb,
            int minRamGb, int quality, int speed) {
        this.displayName = displayName;
        this.baseTag = baseTag;
        this.hasMlxTwin = hasMlxTwin;
        this.baseDiskGb = baseDiskGb;
        this.mlxDiskGb = mlxDiskGb;
        this.residentGb = residentGb;
        this.minRamGb = minRamGb;
        this.quality = quality;
        this.speed = speed;
    }

    /**
     * The human-readable name of this tier — a product name, never translated
     * and never derived from the tag. It is what a choice screen puts on the
     * row so a ladder spanning several model families stays recognisable.
     */
    public String displayName() {
        return displayName;
    }

    /** The family-qualified base tag, e.g. {@code gemma4:e4b}. */
    public String baseTag() {
        return baseTag;
    }

    /**
     * The concrete Ollama tag for the platform: the MLX twin on Apple Silicon
     * — but only where the registry actually carries one ({@code hasMlxTwin});
     * a tier without an MLX build runs its base tag everywhere.
     */
    public String tagFor(boolean appleSilicon) {
        return appleSilicon && hasMlxTwin ? baseTag + "-mlx" : baseTag;
    }

    /**
     * Whether {@code tag} belongs to a family this catalog deploys — the gate
     * on a hand-written {@code agent.model-tag}. Family-level, not tag-level:
     * a sibling size within a deployed family stays installable, a foreign
     * family must never reach {@code ollama pull} verbatim.
     */
    public static boolean isDeployedFamily(String tag) {
        for (ModelCatalog tier : values()) {
            int colon = tier.baseTag.indexOf(':');
            if (colon > 0 && tag.startsWith(tier.baseTag.substring(0, colon + 1))) return true;
        }
        return false;
    }

    public double diskGbFor(boolean appleSilicon) {
        return appleSilicon && hasMlxTwin ? mlxDiskGb : baseDiskGb;
    }

    /** See {@link #residentGb} — the window-sizing weight, not the download. */
    public double residentGb() {
        return residentGb;
    }

    public int minRamGb() {
        return minRamGb;
    }

    /** Always {@code min + 8} — the one rule, no hand-set exceptions. */
    public int recommendedRamGb() {
        return minRamGb + RECOMMENDED_HEADROOM_GB;
    }

    /**
     * Output quality on a 0–10 scale — the non-technical translation of the
     * parameter count, anchored on published benchmark spreads (MMLU Pro /
     * AIME / coding) between the tiers. Relative honesty is the contract: a
     * "9" must genuinely sit near the "10" and far above a "5". NOT monotonic
     * with the ladder: the dense Granite rungs beat the Gemma MoE beside them
     * on quality per GB of machine — that inversion is real, so the scale
     * shows it.
     */
    public int quality() {
        return quality;
    }

    /**
     * Generation speed on a 0–10 scale, anchored on measured tokens/sec on
     * the same machine class — MEASURED, not derived from active-parameter
     * counts: the two big rungs carry sustained on-machine values (see their
     * warning comments), because "only 3B active" says nothing once the
     * resident set eats the machine.
     */
    public int speed() {
        return speed;
    }

    public Fit fitFor(long totalRamGb) {
        if (totalRamGb >= recommendedRamGb()) return Fit.COMFORTABLE;
        if (totalRamGb >= minRamGb) return Fit.TIGHT;
        return Fit.TOO_LARGE;
    }

    /**
     * The largest tier the machine runs comfortably. An unprobeable machine
     * ({@code totalRamGb == 0}) gets the safe default rather than the floor —
     * a probe failure says nothing about the hardware.
     */
    public static ModelCatalog recommend(long totalRamGb) {
        if (totalRamGb <= 0) return DEFAULT;
        ModelCatalog best = values()[0];
        for (ModelCatalog tier : values()) {
            if (tier.fitFor(totalRamGb) == Fit.COMFORTABLE) best = tier;
        }
        return best;
    }
}
