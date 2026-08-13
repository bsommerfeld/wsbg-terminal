package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;

import java.util.Locale;
import java.util.Set;

/**
 * The IMPORTANT trigger gate: red must name the concrete trigger that fired
 * (a required field of the compose reply — grammar-enforced on GGUF only,
 * prompt-requested on MLX, hence the defensive normalisation here), and a
 * price-shaped trigger needs a resolver-verified price. A deterministic
 * backstop that only ever demotes, never promotes. Extracted verbatim from
 * {@link HeadlineWriter}.
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

    /**
     * Analyst-action vocabulary: the mechanical form of the prompt's own exclusion
     * ("Ratings, Kursziele, Sektortrends, Rückblicke sind KEINER") — the model
     * demonstrably ignores the sentence (live: Hertz red on a Jefferies
     * price-target cut), so the cheaper closing point is this code gate, exactly
     * the reconciler's charter. Not a curated noise list but the trigger
     * taxonomy's own definition, spelled as a pattern; it only ever demotes, and
     * a missed red is cheaper than a false one.
     */
    private static final java.util.regex.Pattern ANALYST_ACTION = java.util.regex.Pattern.compile(
            "(?iu)kursziel|price\\s*target|\\brating\\b|einstufung|"
                    + "\\bupgrade\\b|\\bdowngrade\\b|stuft\\b|belässt\\b|belaesst\\b|"
                    + "\\bhochgestuft\\b|\\bherabgestuft\\b|\\binitiates coverage\\b|"
                    + "\\büberbewertet von analyst|\\banalystenkonsens\\b");

    /** True when the line's substance is an analyst action (rating / price target). */
    static boolean isAnalystAction(String headline) {
        return headline != null && ANALYST_ACTION.matcher(headline).find();
    }

    static String normalizeTrigger(String trigger) {
        return trigger == null ? "" : trigger.trim().toUpperCase(Locale.ROOT);
    }

    /** True when the normalised trigger's whole case is price action (needs a verified price). */
    static boolean isPriceShaped(String trigger) {
        return PRICE_TRIGGERS.contains(normalizeTrigger(trigger));
    }

    /**
     * Deterministic backstop for the IMPORTANT rubric: red must name the concrete
     * trigger that fired (the required {@code trigger} field — not guaranteed by
     * the runner on the MLX path, which is exactly why this gate exists), so a
     * "feels important" classification with no nameable play demotes to NORMAL.
     * Rules, mirroring the prompt rubric verbatim:
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
        return reconcileHighlight(highlight, trigger, snapshot, null);
    }

    /**
     * Full form: {@code headline} enables the analyst-action gate — a
     * HARD_CATALYST whose line is a rating or price-target move is exactly what
     * the rubric excludes ("Ratings, Kursziele … sind KEINER") and demotes to
     * NORMAL no matter what the model claimed (live case: Hertz red at −10.3 %
     * on a Jefferies price-target cut).
     */
    static HeadlineHighlight reconcileHighlight(HeadlineHighlight highlight, String trigger,
            MarketSnapshot snapshot, String headline) {
        if (highlight != HeadlineHighlight.IMPORTANT) return highlight;
        String t = normalizeTrigger(trigger);
        if (CATALYST_TRIGGERS.contains(t)) {
            return "HARD_CATALYST".equals(t) && isAnalystAction(headline)
                    ? HeadlineHighlight.NORMAL : HeadlineHighlight.IMPORTANT;
        }
        if (PRICE_TRIGGERS.contains(t) && snapshot != null && snapshot.hasPrice()) {
            return HeadlineHighlight.IMPORTANT;
        }
        return HeadlineHighlight.NORMAL;
    }
}
