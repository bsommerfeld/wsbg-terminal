package de.bsommerfeld.wsbg.terminal.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Placeholder {@link ChatModel} used when no usable LLM backend is available at
 * startup — e.g. the local model isn't installed and no API key has been entered
 * yet. It lets the app boot fully (UI, settings, data feeds) instead of crashing,
 * so the user can simply open Settings → KI-Backend and enter an API key. Any
 * actual model call fails fast with a clear, actionable message rather than a
 * confusing stack trace.
 */
final class UnconfiguredChatModel implements ChatModel {

    private final String reason;

    UnconfiguredChatModel(String reason) {
        this.reason = reason;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        throw new IllegalStateException("No LLM backend available (" + reason
                + "). Open Settings (top-right gear) → KI-Backend and enter an API key, "
                + "then restart.");
    }
}
