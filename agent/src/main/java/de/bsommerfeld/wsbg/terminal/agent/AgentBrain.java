package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.UserLanguage;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central AI brain managing Ollama model interactions. Coordinates the reasoning,
 * compose and vision pipelines. All responses are blocking — gemma4 handles both
 * reasoning and language-appropriate output natively via system prompt injection.
 *
 * <p>The model construction lives in {@link OllamaModelFactory}, the per-URL vision
 * cache in {@link VisionCache}, and the image fetch/standardise IO in
 * {@link ImageFetcher}; this class is the runtime facade holding the three built
 * models and the shared LLM concurrency gate.
 */
@Singleton
public class AgentBrain {

    private static final Logger LOG = LoggerFactory.getLogger(AgentBrain.class);

    // Our isolated instance's endpoint (private port, own model store) — never
    // the user's default-port Ollama. See OllamaServerManager.
    public static final String OLLAMA_BASE_URL = OllamaServerManager.BASE_URL;

    private final ImageFetcher imageFetcher = new ImageFetcher();
    private final VisionCache visionCache = new VisionCache();
    /** Mechanical image read (Tesseract) — fills the same per-URL cache the vision model used to. */
    private final OcrEngine ocrEngine = new OcrEngine();
    private final OllamaModelFactory modelFactory = new OllamaModelFactory(OLLAMA_BASE_URL);

    private ChatModel visionModel;
    private ChatModel agentModel;
    /** Same gemma4 model as {@link #agentModel}, but a TIGHT numPredict — for headline composition. */
    private ChatModel composeModel;
    /** Same gemma4 model, FREE-FORM output (no JSON mode) — for Wetterbericht prose. */
    private ChatModel proseModel;
    /** Same gemma4 model, JSON mode with a ROOMY numPredict — for the watchlist dossier. */
    private ChatModel dossierModel;
    /** Same gemma4 model, free-form with the roomiest numPredict — for KI-DD report passes. */
    private ChatModel deepDiveModel;
    private String activeAgentModel;

    private final GlobalConfig config;
    private final OllamaServerManager serverManager;
    /** The ONE shared gemma4 concurrency gate — vision acquires the same permit the composes do. */
    private final LlmGate llmGate;
    private UserLanguage userLanguage;

    @Inject
    public AgentBrain(GlobalConfig config, ApplicationEventBus eventBus,
            OllamaServerManager serverManager, LlmGate llmGate) {
        this.config = config;
        this.serverManager = serverManager;
        this.llmGate = llmGate;
        eventBus.register(this);

        serverManager.ensureRunning(OLLAMA_BASE_URL);
        initialize(config.getAgent());
    }

    /**
     * Initializes all Ollama model instances via {@link OllamaModelFactory}. All
     * three are the resident gemma4:e4b (agent, compose, vision).
     */
    public void initialize(AgentConfig config) {
        OllamaModelFactory.Models models = modelFactory.build(config);
        this.agentModel = models.agentModel();
        this.composeModel = models.composeModel();
        this.visionModel = models.visionModel();
        this.proseModel = models.proseModel();
        this.dossierModel = models.dossierModel();
        this.deepDiveModel = models.deepDiveModel();
        this.activeAgentModel = models.activeAgentModel();
        this.userLanguage = this.config.getUser().getUserLanguage();

        LOG.info("Initializing AgentBrain -- Agent: {}, Vision: {}, Language: {}",
                models.activeAgentModel(), models.visionModelName(), userLanguage.displayName());
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
     * description if vision has already been computed for {@code url},
     * otherwise empty string — does <b>not</b> trigger vision analysis.
     *
     * <p>Used for comment images: the heavy vision lift happens
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
     * would be redundant work for the vision pool.
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

    /** Restores a persisted vision cache without clobbering live entries. */
    public void importVisionCache(java.util.Map<String, String> cache) {
        visionCache.importAll(cache);
    }

    /**
     * RETIRED: blocking vision-model analysis of an image URL. No longer wired —
     * {@link #describeImage} reads mechanically via {@link #readImageText} instead.
     * Kept (with the vision prompt + model slot) only for a possible future return
     * of true scene understanding on stronger hardware.
     */
    public String see(String imageUrl) {
        if (visionModel == null)
            return "Vision Brain not ready.";
        try {
            LOG.debug("Vision analyzing: {}", imageUrl);
            ImageFetcher.ImagePayload payload = imageFetcher.fetchAndOptimize(imageUrl);

            UserMessage msg = UserMessage.from(
                    TextContent.from(PromptLoader.loadLocalized("vision", userLanguage.code())),
                    ImageContent.from(payload.base64(), payload.mimeType()));

            // Vision shares the one gemma4 model with the editorial composes, so it acquires
            // the SAME LLM gate — vision can no longer over-subscribe Ollama and starve the
            // wire (it now waits its turn behind a compose instead of blocking the slot).
            llmGate.acquire();
            String result;
            try {
                result = visionModel.chat(msg).aiMessage().text();
            } finally {
                llmGate.release();
            }
            // Some model builds (notably the MLX gemma4 variant) silently
            // drop the multimodal payload and respond with a templated
            // "I need the image" / "Please provide the image" / "No image
            // provided" string. Detect those and return empty so the
            // editorial agent's report context isn't poisoned.
            if (result == null) result = "";
            String norm = result.toLowerCase(java.util.Locale.ROOT).trim();
            boolean noImage = norm.contains("need the image")
                    || norm.contains("need an image")
                    || norm.contains("please provide the image")
                    || norm.contains("no image provided")
                    || norm.contains("no image was provided")
                    || norm.contains("cannot describe the image as it was not provided")
                    || norm.startsWith("[please provide");
            if (noImage) {
                LOG.warn("Vision model returned no-image placeholder for {}: {}", imageUrl, result);
                return "";
            }
            if (norm.isBlank()) {
                // Image fetched fine (no exception, not the no-image placeholder) but
                // the model produced NOTHING. Distinguish this from a fetch failure so
                // an empty Vision result isn't a mystery: log that the bytes arrived and
                // how many — a tiny payload hints a bad/placeholder image, a large one
                // points at the model simply returning empty for that picture.
                LOG.warn("Vision model returned an EMPTY description for {} — image fetched OK "
                        + "(~{} KB {}); NOT a fetch failure. Likely an unreadable/placeholder image "
                        + "or the model declined this one.", imageUrl,
                        payload.base64().length() * 3 / 4 / 1024, payload.mimeType());
                return "";
            }
            LOG.info("Vision result: {}", result);
            return result;
        } catch (Exception e) {
            LOG.warn("Vision failure (fetch/analyze) for {}: {}", imageUrl, e.getMessage());
            return "[SYSTEM ERROR: Image retrieval failed (" + e.getMessage()
                    + "). THE IMAGE IS INVISIBLE. DO NOT HALLUCINATE OR GUESS ITS CONTENT.]";
        }
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

    /** The free-form prose model (no JSON mode) — Wetterbericht digests + report text. */
    public ChatModel getProseModel() {
        return proseModel;
    }

    /**
     * The dossier model (plain JSON mode, roomy numPredict) — watchlist dossier
     * revisions, whose sectioned report would truncate at the agent model's 768
     * backstop. Same resident gemma4.
     */
    public ChatModel getDossierModel() {
        return dossierModel;
    }

    /**
     * The deep-dive model (free-form, roomiest numPredict) — the KI-DD report
     * passes: each pass emits a full markdown research report, far past the
     * prose model's budget. On-demand only, never in the steady-state loop.
     */
    public ChatModel getDeepDiveModel() {
        return deepDiveModel;
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
        return config.getAgent().resolveContextTokens();
    }
}
