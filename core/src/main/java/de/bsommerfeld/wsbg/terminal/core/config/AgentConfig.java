package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;

/**
 * AI Agent configuration. Only runtime-toggleable flags belong here —
 * model names live in {@link Model}.
 */
public class AgentConfig {

    @Key("agent.editorial-model")
    @Comment("Editorial agent reasoning model. REASONING_POWER (gemma4:e4b) — the one "
            + "model serving the whole editorial pipeline, so only one model stays resident. "
            + "The model choice is managed centrally; this stays fixed.")
    private String editorialModel = "REASONING_POWER";

    @Key("agent.model-tag")
    @Comment("Ollama model tag override for the one resident model (the catalog ladder: "
            + "granite4.1:3b/8b, gemma4:e2b..26b, qwen3.6:35b, nemotron-3.5-lightning:30b; "
            + "-mlx twins on Apple Silicon where the registry has one). "
            + "Empty = the managed default tier: gemma4:e4b, "
            + "as gemma4:e4b-mlx on Apple Silicon. The launcher reads this key too and "
            + "installs the matching model on the next start, so runtime and installed model "
            + "stay in sync. Only tags of a deployed family are honored; anything else degrades to "
            + "the default. Written by the model picker in the terminal's settings - the only "
            + "place the model is chosen.")
    private String modelTag = "";

    // ---- External AI endpoint ------------------------------------------
    // Together these six keys decide WHERE the model calls go; AiEndpoint#resolve
    // is the only place that reads them. Left at their defaults they describe the
    // managed local instance, i.e. exactly the behaviour that existed before they
    // did - nothing here changes anything until endpoint-mode says "remote".

    @Key("agent.endpoint-mode")
    @Comment("Where the AI model runs. \"managed\" (default) = our own isolated Ollama, "
            + "installed and started by the app on its private port. \"remote\" = an Ollama "
            + "the user runs elsewhere (own machine on the LAN); the app then installs no "
            + "model, starts no server and never shuts one down, and endpoint-url + "
            + "endpoint-model must be set. An incomplete remote setup falls back to managed.")
    private String endpointMode = "managed";

    @Key("agent.endpoint-url")
    @Comment("Base URL of the remote Ollama, e.g. http://192.168.1.20:11434 . A missing "
            + "scheme is read as http://, a trailing slash is ignored. Only used when "
            + "endpoint-mode = remote.")
    private String endpointUrl = "";

    @Key("agent.endpoint-api")
    @Comment("Which API the remote server speaks. \"ollama\" (default) = the native "
            + "Ollama API. \"openai\" = the OpenAI chat-completions API, which is what "
            + "everything else speaks (llama.cpp server, vLLM, LM Studio, gateways, hosted "
            + "providers). Normally detected by the connection test rather than set by hand. "
            + "On \"openai\" three things do not carry over: the thinking switch, num_ctx "
            + "as a request parameter, and enforced JSON schemas - see AiEndpoint.Api.")
    private String endpointApi = "ollama";

    @Key("agent.endpoint-model")
    @Comment("Model tag to request from the remote server, e.g. qwen3:32b. Unlike "
            + "agent.model-tag this is NOT limited to the families the app deploys - the "
            + "remote store is the user's. Below roughly 4B parameters the editorial "
            + "pipeline's output quality is not something we can vouch for.")
    private String endpointModel = "";

    @Key("agent.endpoint-auth")
    @Comment("Value of the auth header sent to the remote endpoint, verbatim - e.g. "
            + "\"Bearer abc123\". Empty = no auth (plain Ollama has none; a value is only "
            + "needed behind a reverse proxy, LiteLLM or similar). Stored in clear text.")
    private String endpointAuth = "";

    @Key("agent.endpoint-auth-header")
    @Comment("Header name carrying endpoint-auth. Default Authorization; set to e.g. "
            + "x-api-key for gateways that expect their own header.")
    private String endpointAuthHeader = "Authorization";

    @Key("agent.endpoint-context-tokens")
    @Comment("Context window (num_ctx) to run the remote model with. 0 = 8192, the same "
            + "conservative floor an unprobeable machine gets. The automatic scaling used "
            + "for the local instance cannot apply here: it is computed from THIS machine's "
            + "memory minus the model's weights, and we know neither for a foreign box. The "
            + "number is not cosmetic - the pipeline budgets its prompts against it.")
    private int endpointContextTokens = 0;

    @Key("agent.endpoint-parallelism")
    @Comment("How many model calls the app sends to the remote server at once. 0 = 2, our "
            + "own server's slot count. Match it to the remote server's OLLAMA_NUM_PARALLEL: "
            + "too high queues on its side, too low leaves it idle.")
    private int endpointParallelism = 0;

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

    public String getEndpointMode() {
        return endpointMode;
    }

    public void setEndpointMode(String endpointMode) {
        this.endpointMode = endpointMode;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public String getEndpointApi() {
        return endpointApi;
    }

    public void setEndpointApi(String endpointApi) {
        this.endpointApi = endpointApi;
    }

    public String getEndpointModel() {
        return endpointModel;
    }

    public void setEndpointModel(String endpointModel) {
        this.endpointModel = endpointModel;
    }

    public String getEndpointAuth() {
        return endpointAuth;
    }

    public void setEndpointAuth(String endpointAuth) {
        this.endpointAuth = endpointAuth;
    }

    public String getEndpointAuthHeader() {
        return endpointAuthHeader;
    }

    public void setEndpointAuthHeader(String endpointAuthHeader) {
        this.endpointAuthHeader = endpointAuthHeader;
    }

    public int getEndpointContextTokens() {
        return endpointContextTokens;
    }

    public void setEndpointContextTokens(int endpointContextTokens) {
        this.endpointContextTokens = endpointContextTokens;
    }

    public int getEndpointParallelism() {
        return endpointParallelism;
    }

    public void setEndpointParallelism(int endpointParallelism) {
        this.endpointParallelism = endpointParallelism;
    }

    /**
     * Resolves the concrete Ollama tag the one resident model runs as: the
     * user's {@code agent.model-tag} choice when it names a tag of a deployed
     * family,
     * else the managed default — {@link Model#REASONING_POWER}, with the MLX
     * build as the standard on Apple Silicon (mirrors the launcher's
     * {@code ModelSelection}, so runtime and installed model agree without any
     * handshake). Family-gated so a typo or foreign model name can never reach
     * the model factory — the factory's own family fallback then still guards
     * against a chosen tag that is not actually installed yet.
     */
    public String resolveModelTag() {
        String tag = modelTag == null ? "" : modelTag.strip().toLowerCase();
        if (Model.isDeployedFamily(tag)) {
            return tag;
        }
        String base = Model.REASONING_POWER.getModelName();
        return isAppleSilicon() ? base + "-mlx" : base;
    }

    /**
     * Public because the settings' model list has to build the SAME tags the
     * launcher installs ({@code ModelCatalog.tagFor(mlx)}); a second copy of
     * this check is how a UI ends up offering a tag the installer never pulls.
     */
    public static boolean isAppleSilicon() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64"));
    }

    /**
     * The effective context window (num_ctx) for the one resident model —
     * fully automatic, scaled to what this machine has left AFTER the model's
     * own weights (user mandate 2026-07-16: no maintained knob; the machine
     * decides). The 8k floor was sized for 16 GB end-user machines with
     * OLLAMA_KV_CACHE_TYPE=q8_0 + flash attention; a bigger window buys
     * headroom per pass, not a different pipeline. Every model variant shares
     * the value so they share one Ollama runner (num_ctx is a load-time
     * parameter).
     *
     * <p>
     * The tiers used to read TOTAL RAM alone, which was only ever right while
     * the resident model was a fixed ~9 GB gemma4:e4b. Measured 2026-08-10 on
     * a 48 GB machine: the same tier table handed a 21 GB model the same
     * 16384-token window it hands a 9 GB one, the resident set plus KV cache
     * pushed the machine 10.6 GB into swap, and sustained generation collapsed
     * from ~78 to ~18 tok/s across five calls. Weight size is therefore the
     * second axis, not a footnote.
     *
     * <p>
     * Honesty note (verified live 2026-08-13): the KV arithmetic below describes
     * the GGUF runner. The MLX runner — the Apple-Silicon default — ignores
     * num_ctx entirely (no reservation: resident set identical at 8192 and
     * 24576) and grows memory with the ACTUAL prompt instead. There the window
     * is a budget the app enforces via its own prompt budgeting, not a limit
     * the runner enforces.
     */
    public int resolveContextTokens() {
        return contextTokensFor(totalPhysicalMemoryBytes(), weightsGbFor(resolveModelTag()));
    }
    // NOTE: this is the MANAGED path only. It reads THIS machine's memory and
    // the weight size of a tag from OUR catalog, so it says nothing about a
    // remote server - see agent.endpoint-context-tokens, which replaces it there.

    /**
     * What the machine must keep for itself: the OS plus the terminal, whose
     * JCEF layer is a full multi-process Chromium next to a 4 GB Java heap.
     * Deliberately generous - the failure mode this guards against (swap) does
     * not degrade gracefully, it collapses.
     */
    private static final double HOST_RESERVE_GB = 12.0;

    /**
     * Approximate RESIDENT weight footprint in GB per model tag — read from
     * the ONE shared catalog ({@code ModelCatalog.residentGb()}, the same
     * definition the launcher's choice screen and the settings' model section
     * render), so the number that sizes the context window can never drift
     * from the number the UIs promise. This used to be a hand-maintained
     * mirror of the launcher's sizes. A tag matches its tier by base-tag
     * prefix ({@code -mlx} twins included); an unknown tag gets the anchor's
     * size rather than zero: guessing small is the one error that
     * reintroduces the swap collapse. (A leftover {@code gemma4:12b} from the
     * tier struck 2026-08-11 sizes as the anchor too — errs large, never
     * small.)
     */
    static double weightsGbFor(String tag) {
        String t = tag == null ? "" : tag.strip().toLowerCase();
        for (var tier : de.bsommerfeld.updater.catalog.ModelCatalog.values()) {
            if (t.startsWith(tier.baseTag())) return tier.residentGb();
        }
        return de.bsommerfeld.updater.catalog.ModelCatalog.DEFAULT.residentGb();
    }

    /**
     * The context window is paid for TWICE: the server runs with
     * {@code OLLAMA_NUM_PARALLEL} slots (see {@link OllamaEndpoint#PARALLELISM}),
     * so the runner allocates KV cache for that many slots of {@code num_ctx}
     * each. Any sizing that forgets this factor is off by 100 % on the one term
     * it exists to bound. Reads the shared constant rather than a private 2, so
     * a change to the server's slot count cannot leave this maths behind.
     */
    private static final int LLM_PARALLEL_SLOTS = OllamaEndpoint.PARALLELISM;

    /**
     * GB of KV cache per 1000 slot-tokens, with q8_0 + flash attention.
     * Measured 2026-08-10 on the 48 GB machine: a 21.9 GB model at num_ctx
     * 16384 held a 25.7 GB wired set, so ~3.8 GB of KV across 2 × 16384 =
     * 32768 slot-tokens.
     */
    private static final double KV_GB_PER_1K_SLOT_TOKENS = 0.116;

    /**
     * Slack beyond the KV cache the window must ALSO leave free: fetch
     * buffers, Java heap growth, OS file cache. Without it the ladder picks
     * the window that exactly fills the machine, and "exactly full" is the
     * state one browser tab away from swapping.
     */
    private static final double BURST_SLACK_GB = 6.0;

    /** The windows auto mode may choose from, roomiest first. */
    private static final int[] WINDOW_LADDER = {24576, 16384, 8192};

    /**
     * The memory tiers behind auto mode - package-visible for tests. Picks the
     * roomiest window whose KV cache still fits what is left after the host
     * reserve and the model's own weights; 8192 is the floor and is returned
     * even when it does not fit, because a window below it is not a smaller
     * pipeline, it is a broken one.
     */
    static int contextTokensFor(long totalMemoryBytes, double modelWeightsGb) {
        if (totalMemoryBytes <= 0) return 8192;   // unprobeable → floor
        double headroomGb =
                totalMemoryBytes / (double) (1L << 30) - modelWeightsGb - HOST_RESERVE_GB;
        for (int window : WINDOW_LADDER) {
            double kvGb = window * LLM_PARALLEL_SLOTS / 1000.0 * KV_GB_PER_1K_SLOT_TOKENS;
            if (headroomGb >= kvGb + BURST_SLACK_GB) return window;
        }
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
