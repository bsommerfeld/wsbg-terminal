package de.bsommerfeld.wsbg.terminal.agent;

/**
 * The per-unit enqueue decision of the merge cadence, as a pure function: does a
 * freshly-dirty {@link SubjectUnit} get a compose job now, wait out its settle/cooldown
 * window, or get its dirty mark dropped because an in-flight copy already composed its
 * evidence. Extracted verbatim from {@link EditorialPipeline#mergeAndEnqueue} so the
 * three timing policies are testable in isolation. (Reads the unit's compose-timing
 * state; the state itself lives on the unit's own {@code ComposeGate}.)
 */
final class EnqueueGate {

    private EnqueueGate() {}

    /** ENQUEUE → compose now; COOLING → hold (keep the dirty mark); STALE → drop the dirty mark. */
    enum Decision { ENQUEUE, COOLING, STALE }

    /**
     * Per-unit compose cooldown: after a unit composes, hold off re-composing it for this
     * long even as fresh evidence arrives — the evidence keeps accumulating on the unit and
     * the next compose runs ONCE against the whole batch. Without it, every single new
     * comment re-wakes the unit (one compose per evidence increment), which both floods the
     * compose queue and produces a stream of near-identical "-Update:" lines on a story that
     * hasn't actually moved. A genuine fresh story still surfaces — just batched, not per-tick.
     * 3 min, up from the original 60 s: at 60 s a hot thread (the DAX daily) re-composed its
     * unit up to once a minute, and a 4B model rewords rather than returns the redundant-empty,
     * so the wire filled with same-ticker variants faster than the near-dup guard's window.
     * The first compose is untouched (only the settle gates it) — this spaces REPEATS.
     */
    private static final long COMPOSE_COOLDOWN_MS = 180_000;
    /**
     * Settle delay before a freshly-dirty unit is FIRST composed: give its evidence time to
     * accumulate AND its price/chart time to resolve, so the line is fuller and carries a
     * quote/sparkline instead of firing on a bare first mention. The audience prefers a
     * slightly later but richer headline over an instant thin one.
     */
    private static final long COMPOSE_SETTLE_MS = 30_000;

    static Decision evaluate(SubjectUnit unit, long nowMs) {
        // Drop a dirty mark whose evidence an in-flight copy already composed
        // against. Without this, a long in-flight compose lets the 1.5s cadence
        // keep re-marking the unit dirty, and once that copy finishes the lingering
        // mark fires a second, near-identical "-Update:" line over the same facts.
        if (!unit.hasUncomposedEvidence()) {
            return Decision.STALE;
        }
        // Settle: hold a freshly-dirty unit until its evidence + price/chart have had
        // COMPOSE_SETTLE_MS to land, so the first line is fuller and carries a quote,
        // not a bare first mention.
        long sinceDirty = nowMs - unit.dirtySinceMs();
        if (unit.dirtySinceMs() > 0 && sinceDirty < COMPOSE_SETTLE_MS) {
            return Decision.COOLING;
        }
        // Per-unit compose cooldown: a unit composed within COMPOSE_COOLDOWN_MS keeps
        // its dirty mark but is NOT re-enqueued yet — fresh evidence accumulates on it
        // and composes once when the window passes (batching, not one compose per
        // comment). The first compose is gated only by the settle above.
        long since = nowMs - unit.lastComposedAtMs();
        if (unit.lastComposedAtMs() > 0 && since < COMPOSE_COOLDOWN_MS) {
            return Decision.COOLING;
        }
        return Decision.ENQUEUE;
    }
}
