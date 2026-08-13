package de.bsommerfeld.wsbg.terminal.web.gateway;

/**
 * What ONE source contributed to an inquiry — the per-source honesty line.
 * Sources fail routinely and partial answers are the norm; the outcome list
 * is how a consumer (the agent above all) SEES what is missing instead of
 * mistaking a half answer for the whole picture.
 *
 * @param sourceName the source's self-declared name
 * @param status     what happened
 * @param itemCount  articles/blocks delivered (0 unless {@link Status#DELIVERED})
 * @param note       short diagnostic ("HTTP 429", "no ISIN on instrument"), or empty
 */
public record SourceOutcome(String sourceName, Status status, int itemCount, String note) {

    public enum Status {
        /** The source answered with content. */
        DELIVERED,
        /** The source answered cleanly but had nothing for this inquiry. */
        EMPTY,
        /** The source failed (transport, parse, wall). */
        FAILED,
        /** The source was not asked (missing key, cooldown, not applicable). */
        SKIPPED
    }

    public SourceOutcome {
        if (note == null) note = "";
        // Debug tap (dev-only, JIT-removed in shipped builds): every outcome
        // line the house writes — gateway fan, facts graze, archive fan —
        // funnels through this constructor, so recording HERE is the one
        // collection point that sees them all without touching any call site.
        // Records name/status/count/note copies only; no behaviour change.
        if (de.bsommerfeld.wsbg.terminal.core.debug.Debug.ENABLED) {
            de.bsommerfeld.wsbg.terminal.core.debug.SourceHealthRegistry.get()
                    .record(sourceName, status == null ? "" : status.name(), itemCount, note);
        }
    }

    public static SourceOutcome delivered(String source, int count) {
        return new SourceOutcome(source, Status.DELIVERED, count, "");
    }

    public static SourceOutcome empty(String source) {
        return new SourceOutcome(source, Status.EMPTY, 0, "");
    }

    public static SourceOutcome failed(String source, String note) {
        return new SourceOutcome(source, Status.FAILED, 0, note);
    }

    public static SourceOutcome skipped(String source, String note) {
        return new SourceOutcome(source, Status.SKIPPED, 0, note);
    }
}
