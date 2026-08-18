package de.bsommerfeld.wsbg.terminal.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The single choke point for a chat call: the num_ctx overflow estimate/warn,
 * the {@link AgentBrain} semaphore bracket that caps concurrency at Ollama's
 * {@code NUM_PARALLEL=2}, and the {@code [LLM]} profiling line. Every model call in
 * the editorial pipeline funnels through here.
 *
 * <p><b>Do not weaken the semaphore bracket.</b> {@link LlmGate#acquire()}/{@link
 * LlmGate#release()} around {@code model.chat} is the documented "biggest throughput fix"
 * — prep extraction + worker composition + vision together must never exceed the shared
 * {@code Semaphore(2)}. The gate is the single {@link LlmGate} {@code @Singleton}, the same
 * instance the vision prefetch acquires.
 */
final class ChatGateway {

    /**
     * Marks the CURRENT THREAD's calls as interactive (a human visibly waits):
     * an on-demand run sets it on its worker for the run's duration, so
     * every call it makes — sections, weaves, judges, inline digests — rides
     * the gate's interactive lane. Background lanes (wire, digest worker,
     * digest) never touch it.
     */
    static final ThreadLocal<Boolean> INTERACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * App-teardown latch: once set (by {@link OllamaServerManager#shutdown()},
     * just before the managed server is killed), a connect failure is a verdict,
     * not a transient — the retry ladder is skipped so daemon lanes caught
     * mid-call don't sit out ~45 s of backoff against a deliberately dead server.
     */
    private static volatile boolean appShutdown = false;

    /** Marks the app as shutting down — every in-flight ChatGateway stops retrying. */
    static void noteAppShutdown() {
        appShutdown = true;
    }


    private static final Logger LOG = LoggerFactory.getLogger(ChatGateway.class);

    private final AgentBrain brain;
    private final LlmGate llmGate;

    ChatGateway(AgentBrain brain, LlmGate llmGate) {
        this.brain = brain;
        this.llmGate = llmGate;
    }

    String chat(ChatModel model, String systemPrompt, String userMessage) {
        // Prompt-budget guard. What actually happens beyond the window depends on
        // the runner (both verified live 2026-08-13):
        //  - GGUF: Ollama silently TRUNCATES the prompt, and not at num_ctx but at
        //    numCtx - max((numCtx - numKeep) / 2, 1) with numKeep = 5 — deliberate
        //    generation headroom (contextShiftPromptLimit, Ollama PR #16856;
        //    measured 5/5 ladder steps to the token, e.g. 24576 → 12291). The old
        //    threshold here (ctx - num_predict = 20992) sat ABOVE that limit, so
        //    between 12291 and 20992 tokens Ollama mangled the brief silently and
        //    this warning stayed quiet — the exact failure it was built to catch.
        //  - MLX (the Apple-Silicon default): num_ctx is not enforced at all
        //    (routes.go: `if m.IsMLX() { truncate = false }`); the whole prompt is
        //    read and memory grows with it. No truncation, but the budget below is
        //    the only thing bounding memory — the runner enforces nothing.
        // So this warns at the GGUF truncation limit, which is also the honest
        // budget line for MLX. The ~3.2 chars/token divisor is the measured German
        // figure (the ~4 rule is English and flattered every prompt by a fifth).
        int estTokens = (int) ((systemPrompt.length() + userMessage.length()) / 3.2);
        int ctx = brain.contextTokens();
        int truncationLimit = ctx - Math.max((ctx - 5) / 2, 1);
        if (estTokens > truncationLimit) {
            LOG.warn("[CTX] prompt ~{} tok vs GGUF truncation limit {} (num_ctx {}) — "
                    + "on GGUF Ollama silently truncates from there; on MLX it all loads "
                    + "but unbounded (sys={} chars, user={} chars)",
                    estTokens, truncationLimit, ctx, systemPrompt.length(), userMessage.length());
        }
        List<ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt), UserMessage.from(userMessage));
        // Gate the actual model call through AgentBrain's SHARED gate so prep
        // extraction + worker composition + vision together never exceed Ollama's
        // NUM_PARALLEL=2. Uninterruptible: a daemon worker shut down mid-acquire would
        // otherwise abandon a permit it never took.
        // A briefly unreachable Ollama (the macOS app restarting its runner —
        // live-observed 2026-07-14: ConnectException killed four of five
        // concurrent calls in six seconds) is a transient, not a verdict:
        // retry with backoff, sleeping OUTSIDE the gate so a waiting worker
        // isn't blocked by a held permit.
        RuntimeException lastConnectFailure = null;
        for (int attempt = 0; attempt <= CONNECT_RETRY_BACKOFF_MS.length; attempt++) {
            if (attempt > 0) {
                if (appShutdown) throw lastConnectFailure; // server was killed on purpose — no backoff
                long backoff = CONNECT_RETRY_BACKOFF_MS[attempt - 1];
                LOG.warn("[LLM] Ollama unreachable — retry {}/{} in {} ms",
                        attempt, CONNECT_RETRY_BACKOFF_MS.length, backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw lastConnectFailure;
                }
            }
            long t0 = System.nanoTime();
            if (INTERACTIVE.get()) llmGate.acquireInteractive();
            else llmGate.acquire();
            long tAcq = System.nanoTime();
            try {
                ChatResponse response = model.chat(ChatRequest.builder().messages(messages).build());
                long t1 = System.nanoTime();
                AiMessage ai = response.aiMessage();
                // PROFILING: gate-wait (semaphore contention) vs gen (the model itself); in/out
                // token counts expose a JSON-mode whitespace-loop (out ≫ the ~80 a headline needs)
                // and a heavy prefill (in). Thread name (editorial-worker vs editorial-prep) tells
                // compose from extraction.
                var tu = response.tokenUsage();
                LOG.info("[LLM] gate-wait={}ms gen={}ms in={} out={}",
                        (tAcq - t0) / 1_000_000, (t1 - tAcq) / 1_000_000,
                        tu == null ? -1 : tu.inputTokenCount(), tu == null ? -1 : tu.outputTokenCount());
                // Debug tap (dev-only, JIT-removed when off): keep what the line
                // above throws away — Ollama's MEASURED token counts, the only
                // honest counterpart to the declared num_ctx. Recording only.
                if (de.bsommerfeld.wsbg.terminal.core.debug.Debug.ENABLED) {
                    de.bsommerfeld.wsbg.terminal.core.debug.LlmDebug.get().record(
                            Thread.currentThread().getName(),
                            (tAcq - t0) / 1_000_000, (t1 - tAcq) / 1_000_000,
                            tu == null || tu.inputTokenCount() == null ? -1 : tu.inputTokenCount(),
                            tu == null || tu.outputTokenCount() == null ? -1 : tu.outputTokenCount());
                }
                return ai == null || ai.text() == null ? "" : ai.text();
            } catch (RuntimeException e) {
                if (isClientReject(e)) {
                    // An HTTP 4xx is a VERDICT on this request, not a transient:
                    // retrying the identical body would 400 again, forever. Today
                    // this branch is practically unreachable (~7.5k-token prompts
                    // against a 131k model window), but Ollama PR #17232 would turn
                    // an unsupported `format` into exactly this 400 on the MLX
                    // default path — and then every compose/agent call on Apple
                    // Silicon would rip through the retry ladder as an uncaught
                    // RuntimeException and kill its lane. Degrade to an empty reply
                    // instead: every caller already treats "" as a whiff (retry /
                    // salvage / park), so the pipeline survives and the log names
                    // the real cause.
                    LOG.error("[LLM] Ollama rejected the request (HTTP 4xx) — returning "
                            + "empty reply so the lane degrades instead of dying: {}",
                            e.toString());
                    return "";
                }
                if (!isConnectFailure(e)) throw e;
                lastConnectFailure = e;
            } finally {
                llmGate.release();
            }
        }
        throw lastConnectFailure;
    }

    /** Backoff ladder for a transiently unreachable server — ~45 s total patience. */
    private static final long[] CONNECT_RETRY_BACKOFF_MS = {3_000, 12_000, 30_000};

    /** True when the failure is connection-level (server down/restarting), not a model error. */
    private static boolean isConnectFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof java.net.ConnectException
                    || c instanceof java.net.http.HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the server REJECTED the request as malformed/unsupported (HTTP 4xx —
     * langchain4j maps it to {@link dev.langchain4j.exception.InvalidRequestException},
     * with the raw {@link dev.langchain4j.exception.HttpException} in the chain).
     * Deterministic per request body, so never retried. Package-private for testing.
     */
    static boolean isClientReject(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof dev.langchain4j.exception.InvalidRequestException) return true;
            if (c instanceof dev.langchain4j.exception.HttpException he
                    && he.statusCode() >= 400 && he.statusCode() < 500) {
                return true;
            }
        }
        return false;
    }
}
