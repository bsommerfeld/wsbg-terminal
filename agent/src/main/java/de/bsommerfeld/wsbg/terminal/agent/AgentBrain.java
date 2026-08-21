package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.AiEndpoint;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.UserLanguage;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central AI brain managing Ollama model interactions. Coordinates the reasoning,
 * compose pipelines. All responses are blocking — one resident model handles both
 * reasoning and language-appropriate output natively via system prompt injection.
 *
 * <p>The model construction lives in {@link ChatModelFactory}, the per-URL
 * image-text cache in {@link VisionCache}, and the image fetch IO in
 * {@link ImageFetcher}; this class is the runtime facade holding the three built
 * models and the shared LLM concurrency gate.
 */
@Singleton
public class AgentBrain {

    private static final Logger LOG = LoggerFactory.getLogger(AgentBrain.class);

    private final ImageFetcher imageFetcher = new ImageFetcher();
    private final VisionCache visionCache = new VisionCache();
    /** Mechanical image read (Tesseract) — fills the per-URL image-text cache. */
    private final OcrEngine ocrEngine = new OcrEngine();
    private final ChatModelFactory modelFactory = new ChatModelFactory();

    private ChatModel agentModel;
    /** Same model as {@link #agentModel}, but a TIGHT numPredict — for headline composition. */
    private ChatModel composeModel;
    /** The same model again, but WITHOUT JSON mode — for prose replies. */
    private ChatModel proseModel;
    private String activeAgentModel;

    private final GlobalConfig config;
    private final OllamaServerManager serverManager;
    /** Guards {@link #start()} — the server is brought up exactly once. */
    private final java.util.concurrent.atomic.AtomicBoolean serverStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** The ONE shared concurrency gate for all model calls. */
    private final LlmGate llmGate;
    /** Endpoint health + the circuit breaker; fed by {@link ChatGateway}. */
    private final AiHealth health;
    private UserLanguage userLanguage;

    @Inject
    public AgentBrain(GlobalConfig config, ApplicationEventBus eventBus,
            OllamaServerManager serverManager, LlmGate llmGate, AiHealth health) {
        this.config = config;
        this.serverManager = serverManager;
        this.llmGate = llmGate;
        this.health = health;
        eventBus.register(this);

        // The model HANDLES are cheap to build and several collaborators expect
        // them right after injection, so they stay here. Bringing the SERVER up
        // does not — that spawns a subprocess and blocks until it answers, which
        // used to happen inside Guice.createInjector, i.e. before the window
        // existed. It moved to start(), behind the boot gate.
        //
        // WITHOUT ASKING OLLAMA. Resolving the model tag is an HTTP call, and
        // leaving it here made the whole app depend on a server that this very
        // class is responsible for starting later: with nothing listening on
        // the port, Guice died during injection and said "Ollama connection
        // failed: null". It only ever worked because an orphan from an earlier
        // run was usually still up.
        initialize(config.getAgent(), false);
    }

    /**
     * Brings the isolated Ollama server up. Idempotent, and deliberately NOT in
     * the constructor: it is the single most expensive step of the boot and it
     * used to run while the startup intro was animating.
     *
     * <p>Called once from the deferred startup. Model calls before that point
     * would hit an unreachable endpoint — every caller (the editorial pipeline,
     * the Reddit monitor) is held behind the same gate, so none can get ahead
     * of it.
     */
    public void start() {
        if (!serverStarted.compareAndSet(false, true)) return;

        AiEndpoint endpoint = AiEndpoint.resolve(config.getAgent());
        if (AiSwitch.off()) {
            // No spawn, no probe, no adoption - the switch's whole point is that
            // no model runs on this machine. The endpoint is marked down right
            // here rather than at the first failing call, so the UI says so from
            // the start and ChatGateway's breaker is tripped before any lane
            // queues behind it.
            LOG.warn("WSBG_NO_AI=true -- no model server is started and every AI lane "
                    + "stays down for this run.");
            health.noteUnreachable(endpoint.baseUrl(), endpoint.managed(), "WSBG_NO_AI=true");
            return;
        }
        boolean reachable;
        if (endpoint.managed()) {
            serverManager.ensureRunning();
            reachable = true;   // ensureRunning() throws rather than return unready
        } else {
            // A server we do not own: address it, nothing more. No start, no
            // adopt, no shutdown — see OllamaServerManager#ensureRunning for what
            // that used to risk. Unreachable is NOT fatal here: the box may be
            // asleep or the address mistyped, and an app that refuses to boot
            // over it cannot even show the user where to fix it.
            reachable = probeRemote(endpoint);
        }

        // NOW the tag can be verified against a server that is actually up: on
        // the managed instance a configured tag that is missing falls back to an
        // installed sibling instead of failing at the first call. Skipped when
        // nothing answered — asking an unreachable endpoint proves nothing.
        initialize(config.getAgent(), reachable);
    }

    /**
     * Initializes all Ollama model instances via {@link ChatModelFactory}. All
     * are the resident gemma4:e4b (agent, compose, deliberate, verdict).
     */
    public void initialize(AgentConfig config) {
        initialize(config, true);
    }

    public void initialize(AgentConfig config, boolean askOllama) {
        ChatModelFactory.Models models = modelFactory.build(config, askOllama);
        this.agentModel = models.agentModel();
        this.composeModel = models.composeModel();
        this.proseModel = models.proseModel();
        this.activeAgentModel = models.activeAgentModel();
        this.userLanguage = this.config.getUser().getUserLanguage();

        AiEndpoint endpoint = AiEndpoint.resolve(config);
        LOG.info("Initializing AgentBrain -- Agent: {}, Language: {}, Endpoint: {} ({})",
                models.activeAgentModel(), userLanguage.displayName(),
                endpoint.baseUrl(), endpoint.mode());
    }

    // -- Public API --

    /**
     * Cache-backed image read. Returns the text for {@code url}, computing it
     * via {@link #readImageText} (mechanical OCR — the vision model is retired)
     * on first hit and reusing the result thereafter. Failed reads are cached
     * too — a broken image is not re-tried within the session.
     *
     * @return raw OCR text, or empty string when the URL is null/blank
     */
    public String describeImage(String url) {
        return visionCache.describe(url, this::readImageText);
    }

    /**
     * Whether the mechanical image read is usable on this system (a native
     * Tesseract install was found). Gates all image cache-warming.
     */
    public boolean imageReadingAvailable() {
        return ocrEngine.available();
    }

    /**
     * Mechanical OCR read of an image URL: fetch at FULL resolution (the 1024px
     * model-payload downscale would destroy small UI glyphs), Tesseract, raw
     * text. Any failure (fetch, undecodable format, OCR) reads as "" so the
     * cache remembers the miss and downstream readers simply skip the image —
     * never an error string that could leak into a report.
     */
    private String readImageText(String imageUrl) {
        if (!ocrEngine.available()) return "";
        try {
            java.awt.image.BufferedImage image = imageFetcher.fetchFullResolution(imageUrl);
            String text = ocrEngine.read(image);
            if (text == null || text.isBlank()) {
                LOG.info("OCR read nothing from {} (image fetched OK — likely a photo/meme without text)", imageUrl);
                return "";
            }
            LOG.info("OCR read {} chars from {}", text.length(), imageUrl);
            return text.trim();
        } catch (Exception e) {
            LOG.warn("OCR failure (fetch/read) for {}: {}", imageUrl, e.getMessage());
            return "";
        }
    }

    /**
     * Lookup-only cache read for the report-builder path. Returns the
     * text if the image has already been read for {@code url},
     * otherwise empty string — does <b>not</b> trigger a fresh read.
     *
     * <p>Used for comment images: the fetch+OCR work happens
     * asynchronously in {@code PassiveMonitorService} so the editorial
     * agent never blocks on cold images.
     */
    public String describeImageIfCached(String url) {
        return visionCache.ifCached(url);
    }

    /**
     * Returns {@code true} if {@code url} has already been described,
     * regardless of whether the description is empty (failed analyses
     * are cached too). Useful for deciding whether a prefetch submission
     * would be redundant work for the prefetch pool.
     */
    public boolean isImageCached(String url) {
        return visionCache.isCached(url);
    }

    /**
     * Snapshot of the per-URL image-text cache for persistence. Restoring this on
     * a quick restart means already-done reads (and remembered failures) don't
     * have to be re-fetched and re-OCRed.
     */
    public java.util.Map<String, String> exportVisionCache() {
        return visionCache.export();
    }

    /** Restores a persisted image-text cache without clobbering live entries. */
    public void importVisionCache(java.util.Map<String, String> cache) {
        visionCache.importAll(cache);
    }

    /**
     * Returns the user language, read <b>live</b> from the config each call so a
     * runtime change (Settings → Anzeigesprache, persisted via {@code config.save()})
     * takes effect on the next composed headline without a restart. The cached
     * {@link #userLanguage} field is kept only for the one-shot init log line.
     */
    public UserLanguage getUserLanguage() {
        return config.getUser().getUserLanguage();
    }

    /**
     * Returns the agent-class {@link ChatModel} (gemma4:e4b) used by
     * the editorial extraction/judge calls.
     */
    public ChatModel getAgentModel() {
        return agentModel;
    }

    /** The tight-numPredict compose model (headline composition); see {@link #composeModel}. */
    public ChatModel getComposeModel() {
        return composeModel;
    }

    /**
     * The same resident model without JSON mode — for the lanes whose prompt asks
     * for plain sentences (the article digest). Using {@link #getAgentModel()}
     * there hands the GGUF runner a JSON grammar for a prose prompt, and the
     * digest comes back as JSON.
     */
    public ChatModel getProseModel() {
        return proseModel;
    }

    // The deliberate + verdict lanes were removed 2026-08-13 — dead handles since
    // the closing assessment left with the KI-DD, and the verdict lane's
    // determinism promise was measured broken on the resident MLX runner (see
    // ChatModelFactory for the full note).

    /**
     * Rebuilds the model handles after the endpoint settings changed, so a new
     * address or model takes effect without a restart.
     *
     * <p>Off the caller's thread: the rebuild verifies the tag against the new
     * server, and the caller is the settings socket - a sleeping box at the far
     * end would otherwise freeze the panel for the length of a connect timeout.
     * Only the handle fields are replaced, so calls already in flight finish on
     * the old ones rather than failing mid-pipeline.
     */
    @com.google.common.eventbus.Subscribe
    public void onEndpointChanged(ControlEvents.AiEndpointChangedEvent event) {
        Thread.ofVirtual().name("ai-endpoint-rebuild").start(() -> {
            try {
                // Probe FIRST, and let the result reach the health channel. The
                // rebuild asks the new address anyway; without this the answer
                // only ever reached the log, so someone who mistyped an address
                // in the settings saw nothing wrong until the next model call
                // failed minutes later. Now the indicator follows the field.
                AiEndpoint endpoint = AiEndpoint.resolve(config.getAgent());
                if (!endpoint.managed()) probeRemote(endpoint);
                initialize(config.getAgent(), true);
            } catch (RuntimeException e) {
                // A managed endpoint that cannot be reached still throws here
                // (it is ours to run). Log it; the handles keep pointing at the
                // previous endpoint, which is strictly better than none.
                LOG.error("Rebuilding the model handles for the new endpoint failed: {}",
                        e.toString());
            }
        });
    }

    /**
     * Asks a remote endpoint whether it is there, and tells the health channel
     * either way.
     *
     * <p>NOT {@code serverManager.isReachable()}: that asks the bare address
     * for a 200, which is Ollama's "Ollama is running" root page and nothing
     * else's. An OpenAI-compatible server answers 404 there and would be
     * declared dead while working perfectly. This asks each protocol's model
     * list - the same question the settings' connection test asks, so the two
     * can never disagree.
     *
     * @return whether the endpoint answered
     */
    private boolean probeRemote(AiEndpoint endpoint) {
        var auth = endpoint.headers().entrySet().stream().findFirst().orElse(null);
        var probe = de.bsommerfeld.updater.endpoint.EndpointProbe.probe(
                endpoint.baseUrl(),
                auth == null ? "" : auth.getKey(),
                auth == null ? "" : auth.getValue());
        if (probe.ok()) {
            health.noteOk(endpoint.baseUrl(), false);
            LOG.info("Using the configured external AI endpoint at {} ({} API)",
                    endpoint.baseUrl(), probe.api());
        } else {
            // Straight to the UI: the terminal runs either way, and the user is
            // the only one who can fix this. Waiting for the first model call
            // would leave minutes of silence with no reason given.
            health.noteUnreachable(endpoint.baseUrl(), false, probe.reason());
            LOG.error("External AI endpoint {} is not reachable ({}) — the terminal continues, "
                    + "but every AI lane will fail until it answers.",
                    endpoint.baseUrl(), probe.reason());
        }
        return probe.ok();
    }

    /** Endpoint health + circuit breaker - {@link ChatGateway} reports into it. */
    AiHealth health() {
        return health;
    }

    /** Where this run's model calls go. Resolved live, so a settings change lands. */
    AiEndpoint endpoint() {
        return AiEndpoint.resolve(config.getAgent());
    }

    /** Returns the resolved Ollama model name used by {@link #getAgentModel()}. */
    public String getAgentModelName() {
        return activeAgentModel;
    }

    /**
     * The agent model's context window (num_ctx) in tokens. Ollama TRUNCATES a
     * longer prompt silently — callers use this to warn/trim before that happens,
     * because a silently-cut brief reads like the model suddenly got dumb.
     */
    public int contextTokens() {
        return endpoint().contextTokens();
    }

    /**
     * The output ceiling (num_predict) every model handle runs with — ONE
     * number for the whole fleet. Callers that must budget prompt against
     * output read it HERE. They used to hand-copy the figure, which meant
     * several places had to be edited in lockstep and a missed one silently
     * over-promised the input budget until a pass came back truncated.
     */
    public int numPredict() {
        return ChatModelFactory.NUM_PREDICT;
    }
}
