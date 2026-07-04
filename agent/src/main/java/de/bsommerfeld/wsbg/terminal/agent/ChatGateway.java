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
        long t0 = System.nanoTime();
        llmGate.acquire();
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
        } finally {
            llmGate.release();
        }
    }
}
