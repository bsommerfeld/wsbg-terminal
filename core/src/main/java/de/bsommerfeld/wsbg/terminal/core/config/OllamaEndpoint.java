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

    /**
     * Concurrent request slots on our own instance — Ollama's
     * {@code OLLAMA_NUM_PARALLEL}, the app-side LLM gate, and the KV-cache
     * arithmetic in {@link AgentConfig} all read THIS number, so they can never
     * disagree. It sat in two places before (the agent's server manager and a
     * private copy in the context-window maths), which is exactly the kind of
     * hand-mirrored figure that drifts.
     *
     * <p>Fixed at 2. 3 was tried (RAM-adaptive) on the theory that the dominant
     * compose gate-wait was a permit shortage. Profiling refuted it: the resident
     * model is GPU-bound, so at 2 slots the GPU is already ~saturated — a 3rd
     * concurrent request just time-slices it, so every call's gen-time rose
     * (compose 8→12s, extraction 13→28s), gate-hold grew with it, and net
     * throughput FELL (4.0→3.5 composes/min). The real lever is less GPU work per
     * call, not more parallelism.
     *
     * <p>It is OUR server's number. A remote endpoint's slot count is its
     * operator's business and is configured, not assumed
     * ({@code agent.endpoint-parallelism}).
     */
    public static final int PARALLELISM = 2;

    private OllamaEndpoint() {
    }
}
