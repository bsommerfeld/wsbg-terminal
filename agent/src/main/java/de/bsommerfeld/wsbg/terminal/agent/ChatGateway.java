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
 * The single choke point for a gemma4 chat call: the num_ctx overflow estimate/warn,
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
     * the on-demand KI-DD sets it on its worker for the run's duration, so
     * every call it makes — sections, weaves, judges, inline digests — rides
     * the gate's interactive lane. Background lanes (wire, digest worker,
     * weather, watchlist) never touch it.
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
        // Ollama TRUNCATES a prompt beyond num_ctx silently — the model then sees a
        // cut-off brief and produces exactly the confused output that looks like
        // sudden dumbness, with no error anywhere. Estimate (~4 chars/token) and
        // at least make the overflow visible. 512 tokens headroom for the reply.
        int estTokens = (systemPrompt.length() + userMessage.length()) / 4;
        int ctx = brain.contextTokens();
        if (estTokens > ctx - 512) {
            LOG.warn("[CTX] prompt ~{} tok vs num_ctx {} — Ollama will silently truncate; "
                    + "brief should have been budgeted tighter (sys={} chars, user={} chars)",
                    estTokens, ctx, systemPrompt.length(), userMessage.length());
        }
        List<ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt), UserMessage.from(userMessage));
        // Gate the actual model call through AgentBrain's SHARED gemma4 gate so prep
        // extraction + worker composition + vision together never exceed Ollama's
        // NUM_PARALLEL=2. Uninterruptible: a daemon worker shut down mid-acquire would
        // otherwise abandon a permit it never took.
        // A briefly unreachable Ollama (the macOS app restarting its runner —
        // live-observed 2026-07-14: ConnectException killed four of five
        // Wetterbericht sections in six seconds) is a transient, not a verdict:
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
                return ai == null || ai.text() == null ? "" : ai.text();
            } catch (RuntimeException e) {
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

    /** Shared HTTP client for the raw tool lane. */
    private static final java.net.http.HttpClient RAW_HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5)).build();

    /**
     * The RAW TOOL lane: one {@code /api/chat} round with tool definitions,
     * spoken directly to the managed Ollama — the langchain4j binding carries
     * no tool surface for our loop, and the question lives in Ollama's native
     * protocol anyway. Same semaphore bracket, same connect-retry ladder as
     * {@link #chat}; the caller builds and parses the JSON body. Thinking
     * stays ON for tool rounds (measured 2026-07-17 against the live server:
     * with {@code think=false} gemma4:e4b answers prose instead of a
     * tool_call, 3/3 — thinking is load-bearing exactly here, while every
     * non-tool call keeps the proven {@code think=false} speedup).
     */
    String rawToolChat(String requestBody) {
        RuntimeException lastConnectFailure = null;
        for (int attempt = 0; attempt <= CONNECT_RETRY_BACKOFF_MS.length; attempt++) {
            if (attempt > 0) {
                if (appShutdown) throw lastConnectFailure;
                long backoff = CONNECT_RETRY_BACKOFF_MS[attempt - 1];
                LOG.warn("[LLM] Ollama unreachable (tool lane) — retry {}/{} in {} ms",
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
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(AgentBrain.OLLAMA_BASE_URL + "/api/chat"))
                        .timeout(java.time.Duration.ofMinutes(5))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                java.net.http.HttpResponse<String> response = RAW_HTTP.send(
                        request, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("Ollama /api/chat answered HTTP "
                            + response.statusCode() + ": " + response.body());
                }
                LOG.info("[LLM] tool round gate-wait={}ms round={}ms",
                        (tAcq - t0) / 1_000_000, (System.nanoTime() - tAcq) / 1_000_000);
                return response.body();
            } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
                lastConnectFailure = new RuntimeException(e);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                llmGate.release();
            }
        }
        throw lastConnectFailure;
    }

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
}
