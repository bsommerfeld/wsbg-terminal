package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.tools.AgentToolbox;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class AgentBrain {

    private static final Logger LOG = LoggerFactory.getLogger(AgentBrain.class);
    private final AgentToolbox toolbox;
    private Assistant assistant;
    private TranslatorBot translatorBot;

    public interface TranslatorBot {
        @SystemMessage("{{sysPrompt}}")
        TokenStream translate(@dev.langchain4j.service.V("sysPrompt") String system, @UserMessage String text);
    }

    // Streaming Model for Agent (Analysis/Reasoning)
    private StreamingChatLanguageModel model;

    // Translator (Streaming)
    private StreamingChatLanguageModel translatorModel;

    // Formatter - Streaming (DISABLED)
    // private StreamingChatLanguageModel formatterModel;

    // Vision uses blocking
    private ChatLanguageModel visionModel;

    @Inject
    public AgentBrain(AgentToolbox toolbox, de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig config) {
        this.toolbox = toolbox;
        de.bsommerfeld.wsbg.terminal.core.config.AgentConfig agentConfig = config.getAgent();
        LOG.info("###############################################");
        LOG.info("AgentBrain Configured (2-Step Pipeline): Analysis -> Translate");
        LOG.info("###############################################");

        String baseUrl = "http://localhost:11434";

        initialize(agentConfig, baseUrl);
    }

    public void initialize(de.bsommerfeld.wsbg.terminal.core.config.AgentConfig config, String baseUrl) {
        // 1. Resolve Models (Auto-Heal if config default doesn't match installed
        // Low/Super versions)
        String reasoningModel = validateOrResolveModel(baseUrl, config.getOllamaModel(), "gemma3");
        String translatorModelName = validateOrResolveModel(baseUrl, config.getTranslatorModel(), "translategemma");
        String visionModelName = config.getVisionModel(); // Usually static/latest, less critical to resolve

        LOG.info("Initializing Agent Brain with Verified Models:");
        LOG.info(" -> Reasoning:  {}", reasoningModel);
        LOG.info(" -> Translator: {}", translatorModelName);

        // Main Agent - Streaming (Analysis)
        this.model = OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(reasoningModel)
                .temperature(0.2)
                .build();

        // Translator - Streaming (Plain Translation)
        this.translatorModel = OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(translatorModelName)
                .temperature(0.1)
                .build();

        // Formatter - Streaming (DISABLED)

        // Vision - Blocking
        this.visionModel = OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(visionModelName)
                .temperature(0.1)
                .build();

        // Assistant uses Streaming Model with Per-ID Memory
        this.assistant = AiServices.builder(Assistant.class)
                .streamingChatLanguageModel(model)
                .tools(toolbox)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .build();

        // Translator Bot Setup
        this.translatorBot = AiServices.builder(TranslatorBot.class)
                .streamingChatLanguageModel(translatorModel)
                .build();
    }

    /**
     * Checks if the target model exists in Ollama. If not, tries to find a
     * best-match fallback
     * from the same family to prevent crashes (e.g. Config says 12b, but only 27b
     * installed).
     */
    private String validateOrResolveModel(String baseUrl, String targetModel, String familyPrefix) {
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/api/tags"))
                    .GET()
                    .build();
            String json = new String(
                    httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray()).body());

            // Simple check: Is the exact model name in the JSON?
            // JSON format: "name":"mnodelname"
            if (json.contains("\"" + targetModel + "\"")) {
                return targetModel; // Exact match found
            }

            LOG.warn("Configured model '{}' not found in Ollama. Attempting auto-resolution for family '{}'...",
                    targetModel, familyPrefix);

            // Fallback: simple string parsing to find any installed model starting with
            // familyPrefix
            // We look for "name":"(familyPrefix[^"]+)"
            java.util.regex.Pattern p = java.util.regex.Pattern
                    .compile("\"name\":\"(" + java.util.regex.Pattern.quote(familyPrefix) + "[^\"]*)\"");
            java.util.regex.Matcher m = p.matcher(json);

            if (m.find()) {
                String fallback = m.group(1);
                LOG.warn(">>> AUTO-RESOLVED: Swapping missing '{}' for installed '{}'. System will function normally.",
                        targetModel, fallback);
                return fallback;
            }

            // CRITICAL: No model found at all
            logCriticalErrorAndDie(targetModel, familyPrefix, "No installed model found in this family.");
            return null; // unreachable

        } catch (Exception e) {
            // Check if it was our intentional death
            if (e instanceof RuntimeException && e.getMessage().contains("Critical Model Error")) {
                throw (RuntimeException) e;
            }

            logCriticalErrorAndDie(targetModel, familyPrefix, "Ollama Connection Failed (" + e.getMessage() + ")");
            return null; // unreachable
        }
    }

    private void logCriticalErrorAndDie(String targetModel, String familyPrefix, String technicalReason) {
        LOG.error("################################################################################");
        LOG.error("CRITICAL ERROR: Model Resolution Failed for '{}' (Family: '{}')", targetModel, familyPrefix);
        LOG.error("Reason: {}", technicalReason);
        LOG.error("");
        LOG.error("Die Anwendung ist aufgrund von Installationsfehlern nicht nutzbar.");
        LOG.error("Bitte starten Sie die Anwendung neu, um eine automatische Reparatur durchzufuehren.");
        LOG.error("################################################################################");
        throw new RuntimeException("Critical Model Error: " + technicalReason);
    }

    public TokenStream translate(String text, String sourceLang, String sourceCode, String targetLang,
            String targetCode) {
        if (translatorModel == null)
            return null;
        String prompt = "You are a professional " + sourceLang + " (" + sourceCode + ") to " + targetLang + " ("
                + targetCode
                + ") translator.\n" +
                "Your goal is to accurately convey the meaning and nuances of the original " + sourceLang
                + " text while adhering to " + targetLang + " grammar, vocabulary, and cultural sensitivities.\n" +
                "Produce only the " + targetLang + " translation, without any additional explanations or commentary.\n"
                +
                "Please translate the following " + sourceLang + " text into " + targetLang + ":\n\n";
        return translatorBot.translate(prompt, text);
    }

    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
    private static final String USER_AGENT = "java:de.bsommerfeld.wsbg.terminal:v1.0 (by /u/WsbgTerminal)";

    private static class ImagePayload {
        final String base64;
        final String mimeType;

        ImagePayload(String base64, String mimeType) {
            this.base64 = base64;
            this.mimeType = mimeType;
        }
    }

    public String see(String imageUrl) {
        if (visionModel == null)
            return "Vision Brain not ready.";
        try {
            LOG.debug("Brain Looking at: {}", imageUrl);

            ImagePayload payload = fetchAndOptimizeImage(imageUrl);

            dev.langchain4j.data.message.UserMessage msg = dev.langchain4j.data.message.UserMessage.from(
                    dev.langchain4j.data.message.TextContent.from(
                            "Analyze this image comprehensively. 1. Transcribe all visible text and numbers (OCR). 2. Describe the visual scene, objects, and context. 3. If it's a chart/graph, detail the trends and data points. 4. If it's a meme or symbolic, explain the meaning. Be detailed."),
                    dev.langchain4j.data.message.ImageContent.from(payload.base64, payload.mimeType));

            String result = visionModel.generate(msg).content().text();
            LOG.info("Vision Analysis Result: {}", result);
            return result;
        } catch (Exception e) {
            LOG.warn("Vision Failure: {}", e.getMessage()); // Downgraded to WARN to avoid stacktrace spam
            return "[SYSTEM ERROR: Image retrieval failed (" + e.getMessage()
                    + "). THE IMAGE IS INVISIBLE. DO NOT HALLUCINATE OR GUESS ITS CONTENT. State explicitly that the image was unavailable.]";
        }
    }

    private ImagePayload fetchAndOptimizeImage(String url) throws Exception {
        // DO NOT modify Reddit URLs (e.g. stripping auto=webp) as it breaks the
        // cryptographic signature (s=...)
        // leading to 403 Forbidden. We must fetch exactly what is given.
        LOG.debug("Fetching image from URL: {}", url);

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        byte[] bytes = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray()).body();

        // 2. Try to optimize/standardize to JPEG using ImageIO
        boolean conversionSuccess = false;
        try {
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
            java.awt.image.BufferedImage originalImage = javax.imageio.ImageIO.read(bais);

            if (originalImage != null) {
                // Resize to max 1024 (better for GLM/LLaVA) and ensure 32px alignment
                int maxDim = 1024;
                int width = originalImage.getWidth();
                int height = originalImage.getHeight();

                double aspectRatio = (double) width / height;
                int newWidth = width;
                int newHeight = height;

                if (width > maxDim || height > maxDim) {
                    if (width > height) {
                        newWidth = maxDim;
                        newHeight = (int) (maxDim / aspectRatio);
                    } else {
                        newHeight = maxDim;
                        newWidth = (int) (maxDim * aspectRatio);
                    }
                }

                // Enforce 32px alignment (Critical for some Vision Models/GGML tensors)
                newWidth = (newWidth / 32) * 32;
                newHeight = (newHeight / 32) * 32;
                newWidth = Math.max(newWidth, 32);
                newHeight = Math.max(newHeight, 32);

                LOG.info("Standardizing image to JPEG {}x{} (was {}x{})...", newWidth, newHeight, width, height);
                java.awt.image.BufferedImage resized = new java.awt.image.BufferedImage(newWidth, newHeight,
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = resized.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                // White background to handle transparency
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, newWidth, newHeight);
                g.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
                g.dispose();

                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(resized, "jpg", baos);
                bytes = baos.toByteArray();
                conversionSuccess = true;
            } else {
                LOG.warn(
                        "ImageIO could not read the image data (len={}). Possible unsupported format (e.g. WebP without plugin).",
                        bytes.length);
            }
        } catch (Exception e) {
            LOG.warn("Image standardization failed, using original bytes. Error: {}", e.getMessage());
        }

        // 3. Determine MIME type correctly
        String mimeType;
        if (conversionSuccess) {
            mimeType = "image/jpeg";
        } else {
            // Check if it's actually Text/HTML (Error page) before guessing image
            if (isTextResponse(bytes)) {
                throw new RuntimeException(
                        "Fetched content is not an image (detected HTML/JSON/Text). URL might be forbidden or invalid.");
            }

            // Fallback: Detect from magic bytes
            mimeType = detectMimeTypeFromBytes(bytes);
            if (mimeType == null) {
                throw new RuntimeException(
                        "Content is not a recognized image format (No valid JPEG/PNG/WebP header found).");
            }
            LOG.info("Fallback to original bytes. Detected MIME: {}", mimeType);
        }

        return new ImagePayload(java.util.Base64.getEncoder().encodeToString(bytes), mimeType);
    }

    private boolean isTextResponse(byte[] data) {
        if (data == null || data.length < 5)
            return false;
        // Check for common text starts: <html, <!DOC, { (json), <xml, <?xml
        String start = new String(data, 0, Math.min(data.length, 20), java.nio.charset.StandardCharsets.US_ASCII).trim()
                .toLowerCase();
        return start.startsWith("<html") || start.startsWith("<!doc") || start.startsWith("{")
                || start.startsWith("<xml") || start.startsWith("<?xml") || start.contains("access denied");
    }

    private String detectMimeTypeFromBytes(byte[] data) {
        if (data == null || data.length < 12)
            return null; // Too small or null -> Not a valid image

        // JPEG magic: FF D8
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8)
            return "image/jpeg";

        // PNG magic: 89 50 4E 47
        if ((data[0] & 0xFF) == 0x89 && (data[1] & 0xFF) == 0x50 &&
                (data[2] & 0xFF) == 0x4E && (data[3] & 0xFF) == 0x47)
            return "image/png";

        // WebP magic: RIFF ... WEBP
        // RIFF (bytes 0-3) and WEBP (bytes 8-11)
        if ((data[0] & 0xFF) == 'R' && (data[1] & 0xFF) == 'I' &&
                (data[2] & 0xFF) == 'F' && (data[3] & 0xFF) == 'F' &&
                (data[8] & 0xFF) == 'W' && (data[9] & 0xFF) == 'E' &&
                (data[10] & 0xFF) == 'B' && (data[11] & 0xFF) == 'P') {
            return "image/webp";
        }

        return null; // Unknown binary format
    }

    // Default Chat (Global Memory)
    public TokenStream ask(String message) {
        return ask("default", message);
    }

    // Scoped Chat (Isolated Memory)
    public TokenStream ask(String memoryId, String message) {
        if (assistant == null) {
            // return empty stream or throw? For now return null and handle in service
            return null;
        }
        try {
            LOG.info("Brain thinking (Streaming) [ID={}]: {}", memoryId, message);
            return assistant.chat(memoryId, message);
        } catch (Exception e) {
            LOG.error("Brain Failure", e);
            return null;
        }
    }
}
