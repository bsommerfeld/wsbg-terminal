package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.V;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central AI brain managing Ollama model interactions.
 * Coordinates reasoning, translation, and vision pipelines.
 */
@Singleton
public class AgentBrain {

    private static final Logger LOG = LoggerFactory.getLogger(AgentBrain.class);

    public static final String OLLAMA_BASE_URL = "http://localhost:11434";

    // Model names — deployment constants, not user-configurable.
    // Maintained alongside Ollama setup scripts.
    public static final String VISION_MODEL = "glm-ocr:latest";
    public static final String EMBEDDING_MODEL = "nomic-embed-text-v2-moe:latest";

    private static final String USER_AGENT = "java:de.bsommerfeld.wsbg.terminal:v1.0 (by /u/WsbgTerminal)";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Assistant assistant;
    private TranslatorBot translatorBot;
    private ChatLanguageModel visionModel;

    // FQN on @UserMessage required — name collision with
    // dev.langchain4j.data.message.UserMessage
    interface TranslatorBot {
        @SystemMessage("{{sysPrompt}}")
        TokenStream translate(@V("sysPrompt") String system,
                @dev.langchain4j.service.UserMessage String text);
    }

    @Inject
    public AgentBrain(GlobalConfig config) {
        initialize(config.getAgent());
    }

    /**
     * Initializes all Ollama model instances and wires the AI service proxies.
     */
    public void initialize(AgentConfig config) {
        String reasoningName = resolveModel(config.isPowerMode() ? "gemma3:12b" : "gemma3:4b", "gemma3");
        String translatorName = resolveModel(config.isPowerMode() ? "translategemma:12b" : "translategemma:4b",
                "translategemma");

        LOG.info("Initializing AgentBrain — Reasoning: {}, Translator: {}", reasoningName, translatorName);

        StreamingChatLanguageModel reasoningModel = OllamaStreamingChatModel.builder()
                .baseUrl(OLLAMA_BASE_URL).modelName(reasoningName).temperature(0.2).build();

        StreamingChatLanguageModel translatorModel = OllamaStreamingChatModel.builder()
                .baseUrl(OLLAMA_BASE_URL).modelName(translatorName).temperature(0.1).build();

        this.visionModel = OllamaChatModel.builder()
                .baseUrl(OLLAMA_BASE_URL).modelName(VISION_MODEL).temperature(0.1).build();

        this.assistant = AiServices.builder(Assistant.class)
                .streamingChatLanguageModel(reasoningModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .build();

        this.translatorBot = AiServices.builder(TranslatorBot.class)
                .streamingChatLanguageModel(translatorModel)
                .build();
    }

    /**
     * Verifies the target model exists in Ollama, falling back to any installed
     * model from the same family to prevent crashes.
     */
    private String resolveModel(String target, String familyPrefix) {
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
    public TokenStream ask(String message) {
        return ask("default", message);
    }

    /** Sends a chat message with an isolated memory scope. */
    public TokenStream ask(String memoryId, String message) {
        if (assistant == null)
            return null;
        try {
            LOG.info("Brain thinking [ID={}]: {}", memoryId, message);
            return assistant.chat(memoryId, message);
        } catch (Exception e) {
            LOG.error("Brain failure", e);
            return null;
        }
    }

    /** Translates text between languages via the dedicated translator model. */
    public TokenStream translate(String text, String sourceLang, String sourceCode,
            String targetLang, String targetCode) {
        if (translatorBot == null)
            return null;
        String prompt = PromptLoader.load("translation", Map.of(
                "SOURCE_LANG", sourceLang, "SOURCE_CODE", sourceCode,
                "TARGET_LANG", targetLang, "TARGET_CODE", targetCode));
        return translatorBot.translate(prompt, text);
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

            String result = visionModel.generate(msg).content().text();
            LOG.info("Vision result: {}", result);
            return result;
        } catch (Exception e) {
            LOG.warn("Vision failure: {}", e.getMessage());
            return "[SYSTEM ERROR: Image retrieval failed (" + e.getMessage()
                    + "). THE IMAGE IS INVISIBLE. DO NOT HALLUCINATE OR GUESS ITS CONTENT.]";
        }
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
