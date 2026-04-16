package de.bsommerfeld.wsbg.terminal.agent;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.Model;
import de.bsommerfeld.wsbg.terminal.core.config.UserLanguage;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.PowerModeChangedEvent;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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

    public static final String OLLAMA_BASE_URL = "http://localhost:11434";

    private static final String USER_AGENT = "java:de.bsommerfeld.wsbg.terminal:v1.0 (by /u/WsbgTerminal)";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Assistant assistant;
    private ChatModel visionModel;
    private String activeReasoningModel;

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

    /** Reinitializes models when power mode is toggled at runtime. */
    @Subscribe
    public void onPowerModeChanged(PowerModeChangedEvent event) {
        LOG.info("Power Mode changed — reinitializing models...");
        initialize(config.getAgent());
    }

    /**
     * Initializes all Ollama model instances and wires the AI service proxies.
     */
    public void initialize(AgentConfig config) {
        Model reasoningModelEnum = config.isPowerMode() ? Model.REASONING_POWER : Model.REASONING;
        String reasoningName = resolveModel(reasoningModelEnum);

        this.activeReasoningModel = reasoningName;
        this.userLanguage = this.config.getUser().getUserLanguage();

        LOG.info("Initializing AgentBrain -- Reasoning: {}, Language: {}", reasoningName, userLanguage.displayName());

        // All models are non-streaming — the full response is returned as String
        ChatModel reasoningModel = OllamaChatModel.builder()
                .baseUrl(OLLAMA_BASE_URL).modelName(reasoningName)
                .temperature(reasoningModelEnum.getTemperature())
                .build();

        // Gemma 4 is natively multimodal — the reasoning
        // model handles vision directly.
        this.visionModel = OllamaChatModel.builder()
                .baseUrl(OLLAMA_BASE_URL).modelName(reasoningName)
                .temperature(0.1)
                .maxRetries(1).build();

        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(reasoningModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .build();
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

    /** Sends a chat message using the default memory scope. */
    public String ask(String message) {
        return ask("default", message);
    }

    /** Sends a chat message with an isolated memory scope. */
    public String ask(String memoryId, String message) {
        if (assistant == null)
            return null;
        try {
            LOG.info("Brain thinking [ID={}, model={}]: {}", memoryId, activeReasoningModel, message);
            return assistant.chat(memoryId, message, userLanguage.displayName());
        } catch (Exception e) {
            LOG.error("Brain failure", e);
            return null;
        }
    }

    /**
     * Extracts financial instrument mentions from text.
     * Uses a free-form JSON prompt parsed manually — more reliable than
     * AiServices structured output which silently fails on most Ollama models
     * that don't expose a JSON schema path.
     */
    public TickerExtractionResult extractTickers(String context) {
        if (assistant == null)
            return TickerExtractionResult.EMPTY;
        try {
            String prompt = PromptLoader.load("ticker-extraction", Map.of("CONTEXT", context));
            String raw = assistant.chat("ticker-extraction", prompt, "English");
            if (raw == null || raw.isBlank())
                return TickerExtractionResult.EMPTY;
            TickerExtractionResult result = parseTickerJson(raw);
            LOG.info("Extracted {} ticker mentions", result.mentions().size());
            return result;
        } catch (Exception e) {
            LOG.warn("Ticker extraction failed: {}", e.getMessage());
            return TickerExtractionResult.EMPTY;
        }
    }

    /**
     * Parses ticker mentions from an AI response that contains a JSON array.
     * Tolerates markdown fences and leading narrative text — only the JSON
     * array block is extracted.
     */
    private TickerExtractionResult parseTickerJson(String raw) {
        // Find first '[' ... last ']' to isolate the JSON array
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start == -1 || end == -1 || end <= start)
            return TickerExtractionResult.EMPTY;

        String json = raw.substring(start, end + 1);
        List<TickerExtractionResult.TickerMention> mentions = new ArrayList<>();

        // Match individual {"symbol":...,"type":...,"name":...} objects
        Pattern obj = Pattern.compile("\\{[^}]+\\}");
        Pattern sym = Pattern.compile("\"symbol\"\\s*:\\s*\"([^\"]+)\"");
        Pattern typ = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"");
        Pattern nam = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

        Matcher objM = obj.matcher(json);
        while (objM.find()) {
            String block = objM.group();
            Matcher sm = sym.matcher(block);
            Matcher tm = typ.matcher(block);
            Matcher nm = nam.matcher(block);
            String symbol = sm.find() ? sm.group(1) : null;
            if (symbol == null || symbol.isBlank())
                continue;
            String type = tm.find() ? tm.group(1) : "UNKNOWN";
            String name = nm.find() ? nm.group(1) : symbol;
            mentions.add(new TickerExtractionResult.TickerMention(symbol.toUpperCase(), type, name));
        }
        return new TickerExtractionResult(mentions);
    }

    /** Performs blocking vision analysis on the given image URL. */
    public String see(String imageUrl) {
        if (visionModel == null)
            return "Vision Brain not ready.";
        try {
            LOG.debug("Vision analyzing [model={}]: {}", activeReasoningModel, imageUrl);
            ImagePayload payload = fetchAndOptimize(imageUrl);

            UserMessage msg = UserMessage.from(
                    TextContent.from(PromptLoader.load("vision")),
                    ImageContent.from(payload.base64, payload.mimeType));

            String result = visionModel.chat(msg).aiMessage().text();
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

    // -- Image Processing --

    private record ImagePayload(String base64, String mimeType) {
    }

    private ImagePayload fetchAndOptimize(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT).GET().build();

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
