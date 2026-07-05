package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;

import java.util.concurrent.Semaphore;

/**
 * The ONE gemma4 concurrency gate, shared across every model call in the agent module:
 * the editorial subject extraction, per-unit composition, the discrete judge calls AND
 * the vision prefetch all hit the single resident model, so they all acquire the SAME
 * permit here. Sized to match Ollama's {@code NUM_PARALLEL}
 * ({@link OllamaServerManager#llmParallelism()}, fixed at 2) so the callers never
 * over-subscribe the server — vision used to run un-gated and starve the compose
 * workers.
 *
 * <p>This is a {@code @Singleton}: exactly ONE gate of {@code llmParallelism()} permits
 * exists per process, injected into {@link AgentBrain} (vision), {@link ChatGateway}
 * (every editorial chat call) and {@link EditorialPipeline} (contention logging).
 *
 * <p><b>Do not weaken the bracket.</b> {@link #acquire()}/{@link #release()} around the
 * real {@code model.chat} call is the documented "biggest throughput fix" — prep
 * extraction + worker composition + vision together must never exceed the shared
 * permit count. {@code acquire()} is uninterruptible: a daemon worker shut down
 * mid-acquire would otherwise abandon a permit it never took.
 */
@Singleton
public class LlmGate {

    private final Semaphore permits = new Semaphore(OllamaServerManager.llmParallelism());

    /** Free permits right now — for contention logging, not flow control. */
    public int availablePermits() {
        return permits.availablePermits();
    }

    /** Blocks uninterruptibly until a permit is free, then takes it. */
    public void acquire() {
        permits.acquireUninterruptibly();
    }

    /** Returns a permit taken by {@link #acquire()}. */
    public void release() {
        permits.release();
    }
}
