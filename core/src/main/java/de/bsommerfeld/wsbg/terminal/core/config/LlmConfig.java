package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;

/**
 * LLM backend selection — "bring your own API key" support.
 *
 * <p>Default is {@code backend = "ollama"}: the bundled local model, unchanged
 * behaviour. Switching to {@code "openai"} or {@code "anthropic"} makes the
 * terminal talk to a remote HTTP API instead, so the ~15&nbsp;GB local model
 * download is skipped entirely (the launcher also reads this to skip the Ollama
 * setup phase).
 *
 * <p>The {@code base-url} is what makes the OpenAI path universal: point it at
 * OpenAI ({@code https://api.openai.com/v1}), the Vercel AI Gateway
 * ({@code https://ai-gateway.vercel.sh/v1}), OpenRouter, Groq, or a local
 * OpenAI-compatible server. One key then serves chat, vision and embeddings.
 *
 * @see LlmBackend
 */
public class LlmConfig {

    @Key("llm.backend")
    @Comment("LLM backend: \"ollama\" (bundled local model, default), \"openai\" "
            + "(any OpenAI-compatible endpoint incl. Vercel AI Gateway / OpenRouter / Groq), "
            + "or \"anthropic\" (Claude). Anything other than ollama = bring-your-own-API-key: "
            + "the local ~15 GB model install is skipped.")
    private String backend = "ollama";

    @Key("llm.api-key")
    @Comment("API key for the remote backend (openai/anthropic). Ignored for ollama.")
    private String apiKey = "";

    @Key("llm.base-url")
    @Comment("Base URL for the OpenAI-compatible chat+vision endpoint. Examples: "
            + "https://api.openai.com/v1 · https://ai-gateway.vercel.sh/v1 · "
            + "https://openrouter.ai/api/v1 . Ignored for ollama and anthropic.")
    private String baseUrl = "https://api.openai.com/v1";

    @Key("llm.chat-model")
    @Comment("Chat + vision model name for the remote backend. For openai e.g. "
            + "gpt-4o-mini; for anthropic e.g. claude-sonnet-4-5; via the Vercel gateway "
            + "e.g. openai/gpt-4o-mini or anthropic/claude-sonnet-4-5. Must be multimodal "
            + "if image analysis is on.")
    private String chatModel = "gpt-4o-mini";

    @Key("llm.embed-model")
    @Comment("Embedding model (768d clustering). OpenAI-compatible only. Default "
            + "text-embedding-3-small. For anthropic set an embed-base-url/embed-api-key "
            + "pointing at an OpenAI-compatible embed endpoint, else embeddings fall back "
            + "to the local Ollama model.")
    private String embedModel = "text-embedding-3-small";

    @Key("llm.embed-base-url")
    @Comment("Optional separate base URL for embeddings. Blank = reuse base-url. Set this "
            + "when the chat backend is anthropic but you still want remote embeddings "
            + "(e.g. the Vercel gateway or OpenAI).")
    private String embedBaseUrl = "";

    @Key("llm.embed-api-key")
    @Comment("Optional separate API key for embeddings. Blank = reuse api-key.")
    private String embedApiKey = "";

    // -- resolved helpers --

    /** The parsed backend, defaulting to OLLAMA on any unknown/blank value. */
    public LlmBackend resolveBackend() {
        return LlmBackend.from(backend);
    }

    /** Whether the selected backend is a remote API (not the local Ollama). */
    public boolean isRemote() {
        return resolveBackend().isRemote();
    }

    /** Effective embeddings base URL: {@link #embedBaseUrl} if set, else {@link #baseUrl}. */
    public String effectiveEmbedBaseUrl() {
        return (embedBaseUrl == null || embedBaseUrl.isBlank()) ? baseUrl : embedBaseUrl;
    }

    /** Effective embeddings API key: {@link #embedApiKey} if set, else {@link #apiKey}. */
    public String effectiveEmbedApiKey() {
        return (embedApiKey == null || embedApiKey.isBlank()) ? apiKey : embedApiKey;
    }

    /**
     * Whether a usable remote embeddings endpoint is configured. When the chat
     * backend is anthropic and this is false, embeddings fall back to local Ollama.
     */
    public boolean hasRemoteEmbeddings() {
        String key = effectiveEmbedApiKey();
        String url = effectiveEmbedBaseUrl();
        return key != null && !key.isBlank() && url != null && !url.isBlank();
    }

    // -- accessors --

    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }

    public String getEmbedModel() { return embedModel; }
    public void setEmbedModel(String embedModel) { this.embedModel = embedModel; }

    public String getEmbedBaseUrl() { return embedBaseUrl; }
    public void setEmbedBaseUrl(String embedBaseUrl) { this.embedBaseUrl = embedBaseUrl; }

    public String getEmbedApiKey() { return embedApiKey; }
    public void setEmbedApiKey(String embedApiKey) { this.embedApiKey = embedApiKey; }
}
