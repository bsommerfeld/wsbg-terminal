package de.bsommerfeld.wsbg.terminal.core.config;

/**
 * Coordinates of our <strong>own, isolated</strong> Ollama instance, shared by
 * every module that talks to it.
 *
 * <p>The lifecycle (spawning/health-checking the private server) lives in the
 * agent's {@code OllamaServerManager}; only the endpoint <em>address</em> is
 * shared infrastructure knowledge, so it sits in {@code core} where both the
 * agent and the {@code embedding} module can reach it without depending on each
 * other. The port is deliberately not Ollama's default 11434, so we never
 * collide with a server the user is running.
 */
public final class OllamaEndpoint {

    /** Private port for our isolated instance — never Ollama's default 11434. */
    public static final int PORT = 11500;
    public static final String HOST = "127.0.0.1";
    public static final String BASE_URL = "http://" + HOST + ":" + PORT;

    private OllamaEndpoint() {
    }
}
