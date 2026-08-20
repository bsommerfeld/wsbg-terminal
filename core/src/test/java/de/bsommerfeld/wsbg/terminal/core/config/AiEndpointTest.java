package de.bsommerfeld.wsbg.terminal.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The endpoint resolution: what an untouched config means, what a remote one
 * means, and - the part that actually matters - what a HALF-WRITTEN remote one
 * means. Every degradation here is deliberate: a config the user is still
 * filling in must never be able to stop the app from booting.
 */
class AiEndpointTest {

    private static AgentConfig remote(String url, String model) {
        AgentConfig c = new AgentConfig();
        c.setEndpointMode("remote");
        c.setEndpointUrl(url);
        c.setEndpointModel(model);
        return c;
    }

    @Test
    void untouchedConfig_isTheManagedInstance() {
        AgentConfig cfg = new AgentConfig();
        AiEndpoint e = AiEndpoint.resolve(cfg);

        assertTrue(e.managed());
        assertEquals(AiEndpoint.Mode.MANAGED, e.mode());
        // The pre-endpoint behaviour, unchanged: our port, our tag, our maths.
        assertEquals(OllamaEndpoint.BASE_URL, e.baseUrl());
        assertEquals(cfg.resolveModelTag(), e.modelTag());
        assertEquals(cfg.resolveContextTokens(), e.contextTokens());
        assertEquals(OllamaEndpoint.PARALLELISM, e.parallelism());
        assertTrue(e.headers().isEmpty());
    }

    @Test
    void remoteWithUrlAndModel_isUsed() {
        AiEndpoint e = AiEndpoint.resolve(remote("http://192.168.1.20:11434", "qwen3:32b"));

        assertEquals(AiEndpoint.Mode.REMOTE, e.mode());
        assertFalse(e.managed());
        assertEquals("http://192.168.1.20:11434", e.baseUrl());
        assertEquals("qwen3:32b", e.modelTag());
    }

    @Test
    void remoteModelTag_isNotLimitedToOurFamilies() {
        // agent.model-tag degrades an unknown family to the managed default -
        // it names a model WE install. The remote store is the user's, so the
        // gate must not apply there, or we would silently ask their machine for
        // a model that is only ever on ours.
        AgentConfig cfg = remote("box.local:11434", "llama3.3:70b");
        assertFalse(Model.isDeployedFamily("llama3.3:70b"));
        assertEquals("llama3.3:70b", AiEndpoint.resolve(cfg).modelTag());
    }

    @Test
    void remoteWithoutUrl_fallsBackToManaged() {
        assertTrue(AiEndpoint.resolve(remote("", "qwen3:32b")).managed());
        assertTrue(AiEndpoint.resolve(remote("   ", "qwen3:32b")).managed());
    }

    @Test
    void remoteWithoutModel_fallsBackToManaged() {
        // Nothing sensible to ask for: our own default tag is a model on OUR
        // machine, and sending it to a foreign server is a guess, not a default.
        assertTrue(AiEndpoint.resolve(remote("http://box.local:11434", "")).managed());
    }

    @Test
    void urlIsNormalizedTheWayPeopleType() {
        assertEquals("http://box.local:11434", AiEndpoint.normalizeUrl("box.local:11434"));
        assertEquals("http://box.local:11434", AiEndpoint.normalizeUrl("  box.local:11434/  "));
        assertEquals("https://ai.example.org", AiEndpoint.normalizeUrl("https://ai.example.org/"));
        assertEquals("http://box.local", AiEndpoint.normalizeUrl("http://box.local///"));
        assertEquals("", AiEndpoint.normalizeUrl("   "));
    }

    @Test
    void authHeaderIsSentVerbatim_orNotAtAll() {
        AgentConfig cfg = remote("http://box.local:11434", "qwen3:32b");
        assertTrue(AiEndpoint.resolve(cfg).headers().isEmpty(), "plain Ollama has no auth");

        cfg.setEndpointAuth("Bearer abc123");
        assertEquals("Bearer abc123", AiEndpoint.resolve(cfg).headers().get("Authorization"));

        // A gateway that wants its own header name gets it - no scheme guessing.
        cfg.setEndpointAuthHeader("x-api-key");
        cfg.setEndpointAuth("abc123");
        var headers = AiEndpoint.resolve(cfg).headers();
        assertEquals(1, headers.size());
        assertEquals("abc123", headers.get("x-api-key"));
    }

    @Test
    void remoteLimits_defaultConservativelyAndAreConfigurable() {
        AgentConfig cfg = remote("http://box.local:11434", "qwen3:32b");
        AiEndpoint fallback = AiEndpoint.resolve(cfg);
        // We cannot probe a foreign machine, so we assume the floor rather than
        // a window that silently truncates every brief.
        assertEquals(AiEndpoint.REMOTE_DEFAULT_CONTEXT_TOKENS, fallback.contextTokens());
        assertEquals(OllamaEndpoint.PARALLELISM, fallback.parallelism());

        cfg.setEndpointContextTokens(32768);
        cfg.setEndpointParallelism(4);
        AiEndpoint tuned = AiEndpoint.resolve(cfg);
        assertEquals(32768, tuned.contextTokens());
        assertEquals(4, tuned.parallelism());
    }

    @Test
    void managedIgnoresTheRemoteOnlyKeys() {
        // Leftovers from a remote experiment must not leak into the managed
        // path - there the machine decides the window, not a stale key.
        AgentConfig cfg = new AgentConfig();
        cfg.setEndpointUrl("http://box.local:11434");
        cfg.setEndpointContextTokens(32768);
        cfg.setEndpointParallelism(8);
        cfg.setEndpointAuth("Bearer abc123");

        AiEndpoint e = AiEndpoint.resolve(cfg);
        assertEquals(OllamaEndpoint.BASE_URL, e.baseUrl());
        assertEquals(cfg.resolveContextTokens(), e.contextTokens());
        assertEquals(OllamaEndpoint.PARALLELISM, e.parallelism());
        assertTrue(e.headers().isEmpty());
    }

    @Test
    void theProtocolDefaultsToOllamaAndManagedIsAlwaysOllama() {
        // Ollama is the protocol the pipeline was tuned on and loses nothing,
        // so it is the safe end of an unset or garbage value.
        assertEquals(AiEndpoint.Api.OLLAMA,
                AiEndpoint.resolve(remote("box.local:11434", "qwen3:32b")).api());

        AgentConfig cfg = remote("box.local:11434", "qwen3:32b");
        cfg.setEndpointApi("nonsense");
        assertEquals(AiEndpoint.Api.OLLAMA, AiEndpoint.resolve(cfg).api());

        // A leftover openai key must never reach the managed instance - that is
        // our own Ollama, and nothing else can be true about it.
        AgentConfig managed = new AgentConfig();
        managed.setEndpointApi("openai");
        assertEquals(AiEndpoint.Api.OLLAMA, AiEndpoint.resolve(managed).api());
        assertFalse(AiEndpoint.resolve(managed).openAi());
    }

    @Test
    void theOpenAiProtocolIsHonoured() {
        AgentConfig cfg = remote("box.local:8080", "llama-3.3-70b");
        cfg.setEndpointApi("openai");
        AiEndpoint e = AiEndpoint.resolve(cfg);

        assertEquals(AiEndpoint.Api.OPENAI, e.api());
        assertTrue(e.openAi());
        assertEquals(AiEndpoint.Mode.REMOTE, e.mode(), "protocol and ownership are separate axes");
    }

    @Test
    void theV1PrefixIsAddedExactlyOnce() {
        // People paste addresses out of documentation, and every OpenAI docs
        // page quotes the /v1. Typed or not, the client must get it once.
        AgentConfig cfg = remote("box.local:8080", "m");
        cfg.setEndpointApi("openai");
        assertEquals("http://box.local:8080/v1", AiEndpoint.resolve(cfg).openAiBaseUrl());

        cfg.setEndpointUrl("http://box.local:8080/v1");
        assertEquals("http://box.local:8080/v1", AiEndpoint.resolve(cfg).openAiBaseUrl());
        assertEquals("http://box.local:8080", AiEndpoint.resolve(cfg).baseUrl(),
                "the stored address stays the bare server, the prefix is the API's");
    }

    @Test
    void headersAreImmutable() {
        var headers = AiEndpoint.resolve(new AgentConfig()).headers();
        assertThrows(UnsupportedOperationException.class, () -> headers.put("X", "y"));
    }
}
