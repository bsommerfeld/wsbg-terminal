package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;

import java.util.Locale;
import java.util.Set;

/**
 * The IMPORTANT trigger gate: red must name the concrete, schema-forced trigger
 * that fired, and a price-shaped trigger needs a resolver-verified price. A
 * deterministic backstop that only ever demotes, never promotes. Extracted
 * verbatim from {@link HeadlineWriter}.
 */
final class HighlightReconciler {

    private HighlightReconciler() {}

    /** Triggers that justify IMPORTANT on their own — catalyst-shaped, no price needed
     *  (the quiet pennystock pooled call must stay red-capable without an L&S listing). */
    private static final Set<String> CATALYST_TRIGGERS = Set.of("HARD_CATALYST", "POOLED_CALL");
    /** Triggers whose whole case IS price action — without a resolver-verified price the
     *  "move" is the room's own screenshot claim, which never earns red on its own. */
    private static final Set<String> PRICE_TRIGGERS =
            Set.of("RUNNER", "SQUEEZE", "BREAKOUT", "EXTREME_DIRECTION");

    static String normalizeTrigger(String trigger) {
        return trigger == null ? "" : trigger.trim().toUpperCase(Locale.ROOT);
    }

    /** True when the normalised trigger's whole case is price action (needs a verified price). */
    static boolean isPriceShaped(String trigger) {
        return PRICE_TRIGGERS.contains(normalizeTrigger(trigger));
    }

    /**
     * Deterministic backstop for the IMPORTANT rubric: red must name the concrete
     * trigger that fired (schema-forced {@code trigger} field), so a "feels
     * important" classification with no nameable play demotes to NORMAL. Rules,
     * mirroring the prompt rubric verbatim:
     * <ul>
     *   <li>NORMAL passes through untouched — this gate only ever demotes, never
     *       promotes (red must stay scarce; a missed red is cheaper than a false one).</li>
     *   <li>IMPORTANT with a catalyst-shaped trigger (HARD_CATALYST, POOLED_CALL)
     *       stands — these are evidence-borne and legal without any price.</li>
     *   <li>IMPORTANT with a price-shaped trigger (RUNNER, SQUEEZE, BREAKOUT,
     *       EXTREME_DIRECTION) needs a resolver-verified price on the unit —
     *       the rubric's "an unverified screenshot % never earns red on its own".</li>
     *   <li>IMPORTANT with trigger NONE / blank / unknown (a salvage-path reply,
     *       a legacy draft) is the doubt case, and doubt reads NORMAL.</li>
     * </ul>
     * Package-private for testing.
     */
    static HeadlineHighlight reconcileHighlight(HeadlineHighlight highlight, String trigger,
            MarketSnapshot snapshot) {
        if (highlight != HeadlineHighlight.IMPORTANT) return highlight;
        String t = normalizeTrigger(trigger);
        if (CATALYST_TRIGGERS.contains(t)) return HeadlineHighlight.IMPORTANT;
        if (PRICE_TRIGGERS.contains(t) && snapshot != null && snapshot.hasPrice()) {
            return HeadlineHighlight.IMPORTANT;
        }
        return HeadlineHighlight.NORMAL;
    }
}
