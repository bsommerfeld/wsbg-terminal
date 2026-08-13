package de.bsommerfeld.wsbg.terminal.core.debug;

/**
 * The model-call ledger: the token counts Ollama MEASURED (prompt_eval_count /
 * eval_count, surfaced by langchain4j as TokenUsage) plus gate-wait and
 * generation time per call — captured at {@code ChatGateway}'s existing
 * profiling line, where they were previously logged and thrown away.
 *
 * <p>This is the "declared vs. measured" other half: the DECLARED num_ctx
 * comes from {@code AgentBrain.contextTokens()}; the MEASURED usage lives
 * here. A consumer must always show the pair, never one number alone.
 *
 * <p>Fed ONLY behind {@code if (Debug.ENABLED)}.
 */
public final class LlmDebug {

    private static final LlmDebug INSTANCE = new LlmDebug();

    public static LlmDebug get() {
        return INSTANCE;
    }

    static final int CALL_CAPACITY = 200;

    /**
     * One model call. Token counts are Ollama's own measurements;
     * {@code -1} means the server did not report a usage block.
     */
    public record Call(long atMs, String thread, long gateWaitMs, long genMs,
            int tokensIn, int tokensOut) {
    }

    /** Aggregates since process start; {@code maxTokensIn} is the measured ceiling. */
    public record Stats(long calls, int maxTokensIn, int maxTokensOut,
            int lastTokensIn, int lastTokensOut, long totalTokensIn, long totalTokensOut,
            long totalGateWaitMs, long totalGenMs) {
    }

    private final DebugRing<Call> calls = new DebugRing<>(CALL_CAPACITY);

    private final Object statsLock = new Object();
    private long callCount;
    private int maxIn;
    private int maxOut;
    private int lastIn;
    private int lastOut;
    private long totalIn;
    private long totalOut;
    private long totalGateWaitMs;
    private long totalGenMs;

    public void record(String thread, long gateWaitMs, long genMs, int tokensIn, int tokensOut) {
        long now = System.currentTimeMillis();
        calls.add(new Call(now, thread, gateWaitMs, genMs, tokensIn, tokensOut));
        synchronized (statsLock) {
            callCount++;
            lastIn = tokensIn;
            lastOut = tokensOut;
            if (tokensIn > maxIn) maxIn = tokensIn;
            if (tokensOut > maxOut) maxOut = tokensOut;
            if (tokensIn > 0) totalIn += tokensIn;
            if (tokensOut > 0) totalOut += tokensOut;
            totalGateWaitMs += gateWaitMs;
            totalGenMs += genMs;
        }
    }

    public Stats stats() {
        synchronized (statsLock) {
            return new Stats(callCount, maxIn, maxOut, lastIn, lastOut, totalIn, totalOut,
                    totalGateWaitMs, totalGenMs);
        }
    }

    public java.util.List<Call> recentCalls(int limit) {
        return calls.recent(limit);
    }

    /** Test seam. */
    public void reset() {
        calls.clear();
        synchronized (statsLock) {
            callCount = 0;
            maxIn = 0;
            maxOut = 0;
            lastIn = 0;
            lastOut = 0;
            totalIn = 0;
            totalOut = 0;
            totalGateWaitMs = 0;
            totalGenMs = 0;
        }
    }

    private LlmDebug() {
    }
}
