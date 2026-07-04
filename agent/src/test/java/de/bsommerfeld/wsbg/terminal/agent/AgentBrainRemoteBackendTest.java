package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Bring your own API key": the remote backends must build their three model
 * instances <em>without</em> ever touching the local Ollama server. These tests
 * exercise the real {@link AgentBrain} constructor with a remote {@code [llm]}
 * config and assert the produced {@link ChatModel}s are the remote provider's
 * (not {@code OllamaChatModel}).
 *
 * <p>Construction is deliberately fast and offline: langchain4j builders don't
 * contact the API until a call is made, and the (unreachable) base URL / dummy
 * key are never dialled. If the remote guard regressed and the constructor tried
 * to start Ollama, it would block for ~15 s and then throw — so a fast, green run
 * is itself the proof that no local model plumbing was invoked.
 */
class AgentBrainRemoteBackendTest {

    private static AgentBrain brainFor(String backend, String model) {
        GlobalConfig cfg = new GlobalConfig();
        cfg.getLlm().setBackend(backend);
        cfg.getLlm().setApiKey("sk-test-not-used");
        cfg.getLlm().setBaseUrl("https://example.invalid/v1");
        cfg.getLlm().setChatModel(model);
        return new AgentBrain(cfg, new ApplicationEventBus(), new OllamaServerManager());
    }

    @Test
    void openAiBackendBuildsRemoteModels() {
        AgentBrain brain = brainFor("openai", "gpt-4o-mini");
        ChatModel agent = brain.getAgentModel();
        ChatModel compose = brain.getComposeModel();
        assertNotNull(agent);
        assertNotNull(compose);
        assertTrue(agent.getClass().getName().contains("OpenAi"),
                "expected an OpenAI chat model, got " + agent.getClass().getName());
        assertTrue(compose.getClass().getName().contains("OpenAi"),
                "expected an OpenAI compose model, got " + compose.getClass().getName());
        assertFalse(agent.getClass().getName().toLowerCase().contains("ollama"),
                "remote backend must not fall back to Ollama");
    }

    @Test
    void ollamaBackendWithoutServerDegradesGracefullyInsteadOfCrashing() {
        GlobalConfig cfg = new GlobalConfig(); // default backend = ollama
        // No local Ollama is installed/running in the test env. The constructor
        // must NOT throw — the app has to boot so the user can enter an API key.
        AgentBrain brain = new AgentBrain(cfg, new ApplicationEventBus(), new OllamaServerManager());
        ChatModel agent = brain.getAgentModel();
        assertNotNull(agent, "must fall back to a placeholder model, not null");
        assertTrue(agent.getClass().getSimpleName().contains("Unconfigured"),
                "expected the unconfigured placeholder, got " + agent.getClass().getName());
        // Using it fails fast with a clear, actionable message.
        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> agent.chat(dev.langchain4j.data.message.UserMessage.from("hi")));
        assertTrue(ex.getMessage().toLowerCase().contains("api key"),
                "message should point the user to entering an API key: " + ex.getMessage());
    }

    @Test
    void anthropicBackendBuildsRemoteModels() {
        AgentBrain brain = brainFor("anthropic", "claude-sonnet-4-5");
        ChatModel agent = brain.getAgentModel();
        assertNotNull(agent);
        assertTrue(agent.getClass().getName().contains("Anthropic"),
                "expected an Anthropic chat model, got " + agent.getClass().getName());
    }
}
