package de.bsommerfeld.wsbg.terminal.core.config;

import java.util.Locale;

/**
 * Which LLM backend the terminal talks to. Selected by the user in the Settings
 * view (top-right gear) and persisted under {@code [llm] backend} in
 * {@code config.toml}.
 *
 * <ul>
 *   <li>{@link #OLLAMA} — the bundled, isolated local Ollama (default). Needs the
 *       one-time ~15&nbsp;GB model download the launcher performs.</li>
 *   <li>{@link #OPENAI} — any OpenAI-compatible HTTP endpoint (OpenAI itself,
 *       Vercel AI Gateway, OpenRouter, Groq, a local server, …). One API key
 *       covers chat, vision <em>and</em> embeddings, so nothing is installed
 *       locally ("bring your own API key").</li>
 *   <li>{@link #ANTHROPIC} — Claude for chat + vision. Anthropic has no embeddings
 *       API, so embeddings fall back to the configured OpenAI-compatible embed
 *       endpoint (e.g. the Vercel gateway) or, if none is set, the local Ollama
 *       embedding model.</li>
 * </ul>
 */
public enum LlmBackend {
    OLLAMA,
    OPENAI,
    ANTHROPIC;

    /** Whether this backend is a remote HTTP API (i.e. not the local Ollama). */
    public boolean isRemote() {
        return this != OLLAMA;
    }

    /**
     * Lenient parse of the persisted string. Unknown/blank values degrade to
     * {@link #OLLAMA} so a stale or hand-edited config can never crash boot.
     */
    public static LlmBackend from(String raw) {
        if (raw == null) return OLLAMA;
        try {
            return LlmBackend.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return OLLAMA;
        }
    }
}
