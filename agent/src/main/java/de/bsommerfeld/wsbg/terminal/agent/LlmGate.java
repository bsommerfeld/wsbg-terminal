package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;

/**
 * The ONE gemma4 concurrency gate, shared across every model call in the agent module:
 * the editorial subject extraction, per-unit composition, the discrete judge calls AND
 * the vision prefetch all hit the single resident model, so they all acquire the SAME
 * permit here. Sized to match Ollama's {@code NUM_PARALLEL}
 * ({@link OllamaServerManager#llmParallelism()}, fixed at 2) so the callers never
 * over-subscribe the server — vision used to run un-gated and starve the compose
 * workers.
 *
 * <p><b>Two lanes since 2026-07-14 (user mandate):</b> an INTERACTIVE caller — the
 * on-demand KI-DD a human visibly waits for — overtakes the background lanes (wire,
 * digest, weather, watchlist) when a permit frees up. Measured need: during the
 * Abendausgabe hour every DD call queued ~16 s behind background work, roughly
 * doubling a run. A running generation is never preempted — only the order at the
 * next free permit changes. <b>Anti-starvation guarantee:</b> after
 * {@link #BACKGROUND_GUARANTEE}−1 consecutive interactive grants with background
 * waiting, the next grant goes to the background lane — the wire keeps publishing
 * (throttled, never stalled) while a DD runs.
 *
 * <p>This is a {@code @Singleton}: exactly ONE gate of {@code llmParallelism()} permits
 * exists per process, injected into {@link AgentBrain} (vision), {@link ChatGateway}
 * (every editorial chat call) and {@link EditorialPipeline} (contention logging).
 *
 * <p><b>Do not weaken the bracket.</b> {@code acquire*()}/{@link #release()} around the
 * real {@code model.chat} call is the documented "biggest throughput fix" — prep
 * extraction + worker composition + vision together must never exceed the shared
 * permit count. Acquisition is uninterruptible: a daemon worker shut down
 * mid-acquire would otherwise abandon a permit it never took.
 */
@Singleton
public class LlmGate {

    /** Every Nth grant under full contention goes to the background lane. */
    static final int BACKGROUND_GUARANTEE = 3;

    private final int permits = OllamaServerManager.llmParallelism();
    private int inUse;
    private int interactiveWaiting;
    private int backgroundWaiting;
    /** Interactive grants since the last background grant — the guarantee counter. */
    private int interactiveStreak;

    /** Free permits right now — for contention logging, not flow control. */
    public synchronized int availablePermits() {
        return permits - inUse;
    }

    /** Blocks uninterruptibly until a permit is free — the background lane. */
    public void acquire() {
        acquire(false);
    }

    /** Blocks uninterruptibly until a permit is free — the interactive lane (on-demand DD). */
    public void acquireInteractive() {
        acquire(true);
    }

    private synchronized void acquire(boolean interactive) {
        if (interactive) interactiveWaiting++;
        else backgroundWaiting++;
        boolean interrupted = false;
        try {
            while (!mayProceed(interactive)) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            inUse++;
            if (interactive) interactiveStreak++;
            else interactiveStreak = 0;
            // A grant can CHANGE policy state (the streak reset re-enables a
            // policy-blocked interactive waiter while a permit is still free)
            // — release() alone would leave that waiter sleeping.
            notifyAll();
        } finally {
            if (interactive) interactiveWaiting--;
            else backgroundWaiting--;
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private boolean mayProceed(boolean interactive) {
        if (inUse >= permits) return false;
        boolean guaranteeDue = interactiveStreak >= BACKGROUND_GUARANTEE - 1;
        if (interactive) {
            // Yield the guaranteed slot when background is actually waiting.
            return !(backgroundWaiting > 0 && guaranteeDue);
        }
        return interactiveWaiting == 0 || guaranteeDue;
    }

    /** Returns a permit taken by an {@code acquire*()}. */
    public synchronized void release() {
        inUse--;
        notifyAll();
    }
}
