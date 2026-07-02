package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.Model;
import de.bsommerfeld.wsbg.terminal.core.config.UserLanguage;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.ollama.OllamaChatModel;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central AI brain managing Ollama model interactions.
 * Coordinates reasoning and vision pipelines. All responses are
 * blocking — gemma4 handles both reasoning and language-appropriate
 * output natively via system prompt injection.
 */
@Singleton
public class AgentBrain {

    private static final Logger LOG = LoggerFactory.getLogger(AgentBrain.class);

    // Our isolated instance's endpoint (private port, own model store) — never
    // the user's default-port Ollama. See OllamaServerManager.
    public static final String OLLAMA_BASE_URL = OllamaServerManager.BASE_URL;

    /**
     * A random, realistic browser User-Agent chosen once per process for image
     * fetches — a browser-shaped agent is what an image CDN expects and is the
     * most reliably accepted, while keeping installs off one shared fingerprint.
     * See {@link BrowserUserAgent}.
     */
    private final String userAgent = BrowserUserAgent.random();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            // Bound the connect phase: image fetches run on the single vision-prefetch
            // worker, so a hung CDN connection would otherwise stall ALL vision warming.
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    private ChatModel visionModel;
    private ChatModel agentModel;
    /** Same gemma4 model as {@link #agentModel}, but a TIGHT numPredict — for headline composition. */
    private ChatModel composeModel;
    private String activeAgentModel;

    /**
     * URL → vision description. Shared between the headline pipeline (where
     * cluster embeddings, thread report blocks, and comment image dumps all
     * pull from the same source of truth) so a given image is rendered at
     * most once per session, regardless of how many times it appears.
     */
    private final Map<String, String> imageDescriptionCache = new ConcurrentHashMap<>();

    private final GlobalConfig config;
    private final OllamaServerManager serverManager;
    private UserLanguage userLanguage;

    @Inject
    public AgentBrain(GlobalConfig config, ApplicationEventBus eventBus,
            OllamaServerManager serverManager) {
        this.config = config;
        this.serverManager = serverManager;
        eventBus.register(this);

        serverManager.ensureRunning(OLLAMA_BASE_URL);
        initialize(config.getAgent());
    }

    /**
     * Initializes all Ollama model instances and wires the AI service proxies.
     *
     * <p>
     * Two model instances are stood up, both gemma4:e4b:
     * <ul>
     * <li><b>agentModel</b> — gemma4:e4b, the tool-use editorial loop.</li>
     * <li><b>visionModel</b> — gemma4:e4b (multimodal), used by
     * {@link #describeImage(String)} for image analysis.</li>
     * </ul>
     */
    public void initialize(AgentConfig config) {
        // One multimodal model serves both roles: the editorial agent
        // (REASONING_POWER = gemma4:e4b) and vision (also gemma4:e4b).
        // We do NOT use the gemma4:e4b-mlx build — its published Ollama tag
        // is text-only (the vision encoder is stripped), so it would return
        // "Please provide the image" placeholders that poison the report
        // context. Sharing the same model name AND the same num_ctx (below)
        // means Ollama keeps a single runner resident instead of two.
        Model agentModelEnum = config.resolveEditorialModel();
        String agentName = resolveModel(agentModelEnum);
        this.activeAgentModel = agentName;

        Model visionModelEnum = Model.REASONING_POWER;
        String visionName = resolveModel(visionModelEnum);

        this.userLanguage = this.config.getUser().getUserLanguage();

        LOG.info("Initializing AgentBrain -- Agent: {}, Vision: {}, Language: {}",
                agentName, visionName, userLanguage.displayName());

        // All non-streaming — the full response is returned as String.
        // Generous timeouts: the agent + tool-use can take a minute per round
        // on a busy machine, and vision calls on gemma4 routinely push past
        // the LangChain4j default of 60s. Set to 5 min across the board.
        java.time.Duration timeout = java.time.Duration.ofMinutes(5);

        // Context window (num_ctx): Ollama silently caps at 4096 unless told
        // otherwise. The editorial agent's tool loop accumulates a system
        // prompt + a getCluster report + per-cluster tool results across a
        // tick, and 4096 is where gemma4:e4b started truncating/stalling in
        // the 2026-05-28 smoke tests. Vision uses the SAME num_ctx on purpose:
        // num_ctx is a load-time parameter, so matching it (with the same
        // model name) lets agent + vision share one Ollama runner instead of
        // spawning a second weight copy. The per-request sampling params
        // (temperature, numPredict) can still differ freely — those don't
        // fork the runner.
        int ctxTokens = config.getContextTokens();
        int visionCtxTokens = ctxTokens;

        // gemma4 is a HYBRID THINKING model and Ollama defaults thinking ON — without an
        // explicit think=false every call silently generated ~2k chars of hidden English
        // "Thinking Process" (~500 tokens, 6–10 s GPU time) that nothing ever read. That,
        // not prefill and not parallelism, was the throughput ceiling (~5 calls/min): with
        // thinking off the same compose measures ~1 s instead of ~10 s at IDENTICAL headline
        // quality (A/B'd 2026-07-01 on real briefs). It also explains the historical
        // "JSON-mode whitespace-loop" / empty-reply lore: with a tight numPredict the
        // thinking consumed the whole token budget and the visible content came back
        // truncated or empty — the loop was the model reasoning, invisibly.
        final boolean think = false;

        // Editorial agent — every call in the deterministic pipeline expects a
        // JSON reply (subjects array, theme-headline object), so Ollama's JSON mode
        // (constrained decoding) is on: the model CANNOT emit syntactically
        // broken JSON anymore, which is what the EditorialAgent salvage cascade
        // existed for (it stays as belt-and-braces). Plain JSON (no schema) because
        // this one model instance serves TWO output shapes (extraction + theme).
        // Low temperature + tightened nucleus keep the headlines faithful (no
        // creative drift away from the cluster evidence).
        this.agentModel = OllamaChatModel.builder()
                .baseUrl(OLLAMA_BASE_URL).modelName(agentName)
                .temperature(agentModelEnum.getTemperature()).topP(0.9).topK(40)
                // 768 backstop for the batched subjects array (~300 tokens). (No `\n\n`
                // stop — it truncated extraction to empty when the model led with a
                // blank line; see composeModel.)
                .numCtx(ctxTokens).numPredict(768)
                .responseFormat(ResponseFormat.JSON)
                .think(think)
                .timeout(timeout)
                .build();

        // Compose model — the SAME gemma4 (no extra load), with the compose output
        // SCHEMA-constrained (not just JSON mode): the grammar forces exactly
        // {headline, mode, trigger, highlight, sentiment} with the enums closed,
        // so a malformed or truncated compose object is mechanically impossible —
        // the salvage cascade and the empty-first-line retries become true
        // belt-and-braces. The deliberate redundant-empty stays legal (headline
        // may be ""). numPredict 1024 is a harmless backstop (the slim JSON is
        // ~60 tokens). sentiment came BACK into the output 2026-07-01: the slim
        // 3-key contract had left every published line NEUTRAL, starving the
        // mood badge and the unit's sentiment arc — with thinking off and the
        // schema closed, the ~5 extra tokens are free and loop-safe.
        // trigger (2026-07-01) is the IMPORTANT anchor: the model must NAME the
        // concrete red trigger BEFORE it sets highlight (property order matters —
        // the grammar emits trigger first, so highlight becomes a near-mechanical
        // consequence instead of a vibe), and HeadlineWriter.reconcileHighlight
        // demotes an IMPORTANT whose trigger doesn't hold up. A 4B model is far
        // more consistent at "which of these 7 discrete cases" than at judging
        // "unambiguous?" in the abstract.
        ResponseFormat composeFormat = ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder()
                        .name("headline")
                        .rootElement(JsonObjectSchema.builder()
                                .addStringProperty("headline")
                                .addEnumProperty("trigger", java.util.List.of(
                                        "NONE", "RUNNER", "SQUEEZE", "BREAKOUT",
                                        "HARD_CATALYST", "POOLED_CALL", "EXTREME_DIRECTION"))
                                .addEnumProperty("highlight", java.util.List.of("NORMAL", "IMPORTANT"))
                                .addEnumProperty("sentiment", java.util.List.of(
                                        "BULLISH", "BEARISH", "MIXED", "FOMO", "CAPITULATION",
                                        "SQUEEZE", "REVERSAL", "BREAKOUT", "NEUTRAL"))
                                .required("headline", "trigger", "highlight", "sentiment")
                                .build())
                        .build())
                .build();
        this.composeModel = OllamaChatModel.builder()
                .baseUrl(OLLAMA_BASE_URL).modelName(agentName)
                .temperature(agentModelEnum.getTemperature()).topP(0.9).topK(40)
                .numCtx(ctxTokens).numPredict(1024)
                .responseFormat(composeFormat)
                .think(think)
                .timeout(timeout)
                .build();

        // Vision — image description. Low temperature is critical: the agent
        // trusts these numbers (percent moves, € amounts, price levels), so a
        // hallucinated figure poisons the headline. Faithful read-out, not
        // creative caption. numPredict is 2048 (not 1024): the prompt now asks
        // for a full per-row transcription of dense screenshots (a 10–12 line
        // depot), and a real one measured ~1300 output tokens — 1024 truncated
        // it mid-table, dropping positions the desk needs.
        // numPredict 512 (was 2048): vision was the throughput killer — a full multi-chart
        // transcription ran 30–60s and, since vision shares the model with the editorial
        // composes, starved them. 512 holds a typical broker view but caps the runaway
        // multi-asset paragraphs (some hit 2048), so each image is ~5–10s. Trade: a very long
        // (10–12 row) depot may truncate; throughput wins. Paired with the shared LLM gate.
        // think=false matters doubly here: with the 512 numPredict cap, hidden
        // thinking could consume the entire budget and return an EMPTY transcript.
        this.visionModel = OllamaChatModel.builder()
                .baseUrl(OLLAMA_BASE_URL).modelName(visionName)
                .temperature(0.2).topP(0.9).topK(40)
                .numCtx(visionCtxTokens).numPredict(512)
                .think(think)
                .timeout(timeout)
                .maxRetries(1).build();
    }

    /**
     * Verifies the target model exists in Ollama, falling back to any installed
     * model from the same family to prevent crashes.
     */
    private String resolveModel(Model model) {
        String target = model.getModelName();
        String familyPrefix = model.getFamilyPrefix();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_BASE_URL + "/api/tags")).GET().build();
            String json = new String(
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()).body());

            if (json.contains("\"" + target + "\"")) {
                return target;
            }

            LOG.warn("Model '{}' not found. Resolving fallback for family '{}'...", target, familyPrefix);

            Pattern p = Pattern.compile("\"name\":\"(" + Pattern.quote(familyPrefix) + "[^\"]*)\"");
            Matcher m = p.matcher(json);
            if (m.find()) {
                String fallback = m.group(1);
                LOG.warn("Auto-resolved '{}' → '{}'", target, fallback);
                return fallback;
            }

            throw new IllegalStateException("No installed model found for family: " + familyPrefix);

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Ollama connection failed: " + e.getMessage(), e);
        }
    }

    // -- Public API --

    /**
     * Cache-backed vision call. Returns the description for {@code url},
     * computing it via {@link #see} on first hit and reusing the result
     * thereafter. Failed analyses are cached too — a broken image is
     * not re-tried within the session.
     *
     * @return description text, or empty string when the URL is null/blank
     */
    public String describeImage(String url) {
        if (url == null || url.isEmpty())
            return "";
        return imageDescriptionCache.computeIfAbsent(url, this::see);
    }

    /**
     * Lookup-only cache read for the report-builder path. Returns the
     * description if vision has already been computed for {@code url},
     * otherwise empty string — does <b>not</b> trigger vision analysis.
     *
     * <p>Used for comment images: the heavy vision lift happens
     * asynchronously in {@code PassiveMonitorService} so the editorial
     * agent's {@code getCluster} tool call never blocks on cold images.
     * A cache miss here simply means the description isn't ready yet;
     * it will be present on the next agent tick once prefetch settles.
     */
    public String describeImageIfCached(String url) {
        if (url == null || url.isEmpty())
            return "";
        return imageDescriptionCache.getOrDefault(url, "");
    }

    /**
     * Returns {@code true} if {@code url} has already been described,
     * regardless of whether the description is empty (failed analyses
     * are cached too). Useful for deciding whether a prefetch submission
     * would be redundant work for the vision pool.
     */
    public boolean isImageCached(String url) {
        return url != null && !url.isEmpty() && imageDescriptionCache.containsKey(url);
    }

    /**
     * Snapshot of the per-URL vision cache for persistence. Restoring this on a
     * quick restart means the (slow, ~15-25 s each) image analyses the model
     * already did don't have to be recomputed.
     */
    public java.util.Map<String, String> exportVisionCache() {
        return new java.util.HashMap<>(imageDescriptionCache);
    }

    /** Restores a persisted vision cache without clobbering live entries. */
    public void importVisionCache(java.util.Map<String, String> cache) {
        if (cache == null) return;
        cache.forEach(imageDescriptionCache::putIfAbsent);
    }

    /**
     * The ONE gemma4 concurrency gate, shared by the editorial composes/extraction AND the
     * vision prefetch (both hit the single model). Sized to match Ollama's {@code NUM_PARALLEL}
     * ({@link OllamaServerManager#llmParallelism()}) so the two never over-subscribe each other
     * — vision used to run un-gated and starve the compose workers. {@code EditorialAgent}
     * delegates its compose gating here.
     */
    private final java.util.concurrent.Semaphore llmGate =
            new java.util.concurrent.Semaphore(OllamaServerManager.llmParallelism());

    public int availableLlmPermits() { return llmGate.availablePermits(); }
    public void acquireLlm() { llmGate.acquireUninterruptibly(); }
    public void releaseLlm() { llmGate.release(); }

    /** Performs blocking vision analysis on the given image URL. */
    public String see(String imageUrl) {
        if (visionModel == null)
            return "Vision Brain not ready.";
        try {
            LOG.debug("Vision analyzing: {}", imageUrl);
            ImagePayload payload = fetchAndOptimize(imageUrl);

            UserMessage msg = UserMessage.from(
                    TextContent.from(PromptLoader.loadLocalized("vision", userLanguage.code())),
                    ImageContent.from(payload.base64, payload.mimeType));

            // Vision shares the one gemma4 model with the editorial composes, so it acquires
            // the SAME LLM gate — vision can no longer over-subscribe Ollama and starve the
            // wire (it now waits its turn behind a compose instead of blocking the slot).
            acquireLlm();
            String result;
            try {
                result = visionModel.chat(msg).aiMessage().text();
            } finally {
                releaseLlm();
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
                        payload.base64.length() * 3 / 4 / 1024, payload.mimeType);
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
     * the editorial tool-use loop.
     */
    public ChatModel getAgentModel() {
        return agentModel;
    }

    /** The tight-numPredict compose model (headline composition); see {@link #composeModel}. */
    public ChatModel getComposeModel() {
        return composeModel;
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
        return config.getAgent().getContextTokens();
    }

    // -- Image Processing --

    private record ImagePayload(String base64, String mimeType) {
    }

    private ImagePayload fetchAndOptimize(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                // Hard read deadline so a slow/stalled image host can't pin the
                // single vision-prefetch worker indefinitely.
                .timeout(java.time.Duration.ofSeconds(20))
                .header("User-Agent", userAgent).GET().build();

        byte[] bytes = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
        boolean converted = false;

        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(bytes));
            if (original != null) {
                int[] dims = constrainAndAlign(original.getWidth(), original.getHeight(), 1024, 32);
                BufferedImage resized = new BufferedImage(dims[0], dims[1], BufferedImage.TYPE_INT_RGB);
                Graphics2D g = resized.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, dims[0], dims[1]);
                g.drawImage(original, 0, 0, dims[0], dims[1], null);
                g.dispose();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(resized, "jpg", baos);
                bytes = baos.toByteArray();
                converted = true;
            }
        } catch (Exception e) {
            LOG.warn("Image standardization failed, using original bytes: {}", e.getMessage());
        }

        String mimeType;
        if (converted) {
            mimeType = "image/jpeg";
        } else {
            if (isTextResponse(bytes)) {
                throw new RuntimeException("Fetched content is not an image (HTML/JSON/Text detected).");
            }
            mimeType = detectMimeType(bytes);
            if (mimeType == null) {
                throw new RuntimeException("Unrecognized image format (no valid JPEG/PNG/WebP header).");
            }
        }

        return new ImagePayload(Base64.getEncoder().encodeToString(bytes), mimeType);
    }

    /** Constrains dimensions to maxDim and aligns to alignment boundary. */
    private int[] constrainAndAlign(int w, int h, int maxDim, int alignment) {
        if (w > maxDim || h > maxDim) {
            double ratio = (double) w / h;
            if (w > h) {
                w = maxDim;
                h = (int) (maxDim / ratio);
            } else {
                h = maxDim;
                w = (int) (maxDim * ratio);
            }
        }
        w = Math.max((w / alignment) * alignment, alignment);
        h = Math.max((h / alignment) * alignment, alignment);
        return new int[] { w, h };
    }

    private boolean isTextResponse(byte[] data) {
        if (data == null || data.length < 5)
            return false;
        String start = new String(data, 0, Math.min(data.length, 20), StandardCharsets.US_ASCII).trim().toLowerCase();
        return start.startsWith("<html") || start.startsWith("<!doc") || start.startsWith("{")
                || start.startsWith("<xml") || start.startsWith("<?xml") || start.contains("access denied");
    }

    private String detectMimeType(byte[] data) {
        if (data == null || data.length < 12)
            return null;
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8)
            return "image/jpeg";
        if ((data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47)
            return "image/png";
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P')
            return "image/webp";
        return null;
    }

}
