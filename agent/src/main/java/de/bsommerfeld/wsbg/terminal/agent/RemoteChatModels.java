package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.config.LlmConfig;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;

/**
 * Builds a provider-neutral {@link ChatModel} for the <em>remote</em> ("bring your
 * own API key") backends, so {@link AgentBrain} can keep every downstream caller
 * on the same {@code ChatModel.chat(...)} seam it already uses for local Ollama.
 *
 * <ul>
 *   <li><b>OpenAI-compatible</b> — a configurable {@code baseUrl} makes this cover
 *       OpenAI, the Vercel AI Gateway, OpenRouter, Groq and local servers alike.
 *       JSON-object response mode is turned on for the deterministic pipeline
 *       calls (extraction/compose), matching the local Ollama JSON mode.</li>
 *   <li><b>Anthropic</b> — Claude for chat + vision. Claude has no forced-JSON
 *       response mode, so those calls rely on the prompt + the EditorialAgent
 *       salvage cascade (the same belt-and-braces that guards gemma4).</li>
 * </ul>
 *
 * The Ollama-specific knobs (num_ctx, think, top_k, constrained-grammar schema)
 * have no portable equivalent and are deliberately dropped here — a remote model
 * manages its own context window, and {@code maxTokens} stands in for numPredict.
 */
final class RemoteChatModels {

    private RemoteChatModels() {
    }

    /**
     * @param llm         the resolved LLM config (must be a remote backend)
     * @param temperature sampling temperature
     * @param maxTokens   output cap (the remote analogue of Ollama's numPredict)
     * @param timeout     per-request timeout
     * @param maxRetries  langchain4j retry count
     * @param json        request JSON-object output (OpenAI only; ignored for Anthropic)
     */
    static ChatModel chat(LlmConfig llm, double temperature, int maxTokens,
            Duration timeout, int maxRetries, boolean json) {
        return switch (llm.resolveBackend()) {
            case OPENAI -> {
                OpenAiChatModel.OpenAiChatModelBuilder b = OpenAiChatModel.builder()
                        .baseUrl(llm.getBaseUrl())
                        .apiKey(llm.getApiKey())
                        .modelName(llm.getChatModel())
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .timeout(timeout)
                        .maxRetries(maxRetries);
                if (json) {
                    b.responseFormat(ResponseFormat.JSON);
                }
                yield b.build();
            }
            case ANTHROPIC -> AnthropicChatModel.builder()
                    .apiKey(llm.getApiKey())
                    .modelName(llm.getChatModel())
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .timeout(timeout)
                    .maxRetries(maxRetries)
                    .build();
            case OLLAMA -> throw new IllegalStateException(
                    "RemoteChatModels must not be used for the local OLLAMA backend");
        };
    }
}
