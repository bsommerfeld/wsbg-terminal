package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.UserLanguage;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central AI brain managing Ollama model interactions. Coordinates the reasoning,
 * compose pipelines. All responses are blocking — gemma4 handles both
 * reasoning and language-appropriate output natively via system prompt injection.
 *
 * <p>The model construction lives in {@link OllamaModelFactory}, the per-URL
 * image-text cache in {@link VisionCache}, and the image fetch IO in
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
    /** Mechanical image read (Tesseract) — fills the per-URL image-text cache. */
    private final OcrEngine ocrEngine = new OcrEngine();
    private final OllamaModelFactory modelFactory = new OllamaModelFactory(OLLAMA_BASE_URL);

    private ChatModel agentModel;
    /** Same gemma4 model as {@link #agentModel}, but a TIGHT numPredict — for headline composition. */
    private ChatModel composeModel;
    /** Same gemma4 model, FREE-FORM output (no JSON mode) — for Wetterbericht prose. */
    private ChatModel proseModel;
    /** Same gemma4 model, JSON mode with a ROOMY numPredict — for the watchlist dossier. */
    private ChatModel dossierModel;
    /** Same gemma4 model, free-form with the roomiest numPredict — for KI-DD report passes. */
    private ChatModel deepDiveModel;
    /** Same gemma4 model, free-form, THINKING ON — for the few-call verdict judges. */
    private ChatModel deliberateModel;
    private String activeAgentModel;

    private final GlobalConfig config;
    private final OllamaServerManager serverManager;
    /** The ONE shared gemma4 concurrency gate for all model calls. */
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
     * are the resident gemma4:e4b (agent, compose, prose, dossier, deep-dive).
     */
    public void initialize(AgentConfig config) {
        OllamaModelFactory.Models models = modelFactory.build(config);
        this.agentModel = models.agentModel();
        this.composeModel = models.composeModel();
        this.proseModel = models.proseModel();
        this.dossierModel = models.dossierModel();
        this.deepDiveModel = models.deepDiveModel();
        this.deliberateModel = models.deliberateModel();
        this.activeAgentModel = models.activeAgentModel();
        this.userLanguage = this.config.getUser().getUserLanguage();

        LOG.info("Initializing AgentBrain -- Agent: {}, Language: {}",
                models.activeAgentModel(), userLanguage.displayName());
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

    /**
     * The DELIBERATE judge model: same gemma4, free-form, thinking ON.
     * Reserved for the few-call VERDICT judges (formcheck, final instance,
     * reclaim) where thinking measurably flips the verdict quality
     * (2026-07-17 A/B) — never for the mass lanes, whose think=false speedup
     * is the wire's documented throughput fix.
     */
    public ChatModel getDeliberateModel() {
        return deliberateModel != null ? deliberateModel : deepDiveModel;
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
