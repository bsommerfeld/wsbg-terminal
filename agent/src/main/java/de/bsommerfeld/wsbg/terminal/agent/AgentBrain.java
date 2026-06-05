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
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private ChatModel visionModel;
    private ChatModel agentModel;
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

        // Editorial agent — tool-use loop. Low temperature + tightened
        // nucleus keep the tool-call JSON valid and the headlines faithful
        // (no creative drift away from the cluster evidence). numPredict is
        // bounded: a turn emits one tool call or a short headline, never an
        // essay, and a tight cap discourages rambling pre-amble before the
        // call.
        this.agentModel = OllamaChatModel.builder()
                .baseUrl(OLLAMA_BASE_URL).modelName(agentName)
                .temperature(agentModelEnum.getTemperature()).topP(0.9).topK(40)
                .numCtx(ctxTokens).numPredict(2048)
                .timeout(timeout)
                .build();

        // Vision — image description. Low temperature is critical: the agent
        // trusts these numbers (percent moves, € amounts, price levels), so a
        // hallucinated figure poisons the headline. Faithful read-out, not
        // creative caption. numPredict is 2048 (not 1024): the prompt now asks
        // for a full per-row transcription of dense screenshots (a 10–12 line
        // depot), and a real one measured ~1300 output tokens — 1024 truncated
        // it mid-table, dropping positions the desk needs.
        this.visionModel = OllamaChatModel.builder()
                .baseUrl(OLLAMA_BASE_URL).modelName(visionName)
                .temperature(0.2).topP(0.9).topK(40)
                .numCtx(visionCtxTokens).numPredict(2048)
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

    /** Performs blocking vision analysis on the given image URL. */
    public String see(String imageUrl) {
        if (visionModel == null)
            return "Vision Brain not ready.";
        try {
            LOG.debug("Vision analyzing: {}", imageUrl);
            ImagePayload payload = fetchAndOptimize(imageUrl);

            UserMessage msg = UserMessage.from(
                    TextContent.from(PromptLoader.load("vision")),
                    ImageContent.from(payload.base64, payload.mimeType));

            String result = visionModel.chat(msg).aiMessage().text();
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
            LOG.info("Vision result: {}", result);
            return result;
        } catch (Exception e) {
            LOG.warn("Vision failure: {}", e.getMessage());
            return "[SYSTEM ERROR: Image retrieval failed (" + e.getMessage()
                    + "). THE IMAGE IS INVISIBLE. DO NOT HALLUCINATE OR GUESS ITS CONTENT.]";
        }
    }

    /** Returns the resolved user language. */
    public UserLanguage getUserLanguage() {
        return userLanguage;
    }

    /**
     * Returns the agent-class {@link ChatModel} (gemma4:e4b) used by
     * the editorial tool-use loop.
     */
    public ChatModel getAgentModel() {
        return agentModel;
    }

    /** Returns the resolved Ollama model name used by {@link #getAgentModel()}. */
    public String getAgentModelName() {
        return activeAgentModel;
    }

    // -- Image Processing --

    private record ImagePayload(String base64, String mimeType) {
    }

    private ImagePayload fetchAndOptimize(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
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
