package de.bsommerfeld.wsbg.terminal.instruments;

/**
 * The <b>why</b> behind one alias posting — everything that was on the table the
 * moment a spelling was settled on a symbol, written down so a later reader (a
 * human or a model) can weigh the verdict instead of having to trust it blindly.
 *
 * <p>Two verdicts that look identical in the ledger are not worth the same: a
 * catalogue hit is a fact, a judge pick under a thin thread title is a guess.
 * Without the provenance the file cannot tell them apart — which is exactly how
 * a single wrong judge call ends up looking as solid as a thousand confirmed
 * catalogue hits.
 *
 * @param tier       the guard-tower stage that claimed the subject (the hardest
 *                   signal we have — objective, free, and it does not flatter itself)
 * @param isin       the stamped ISIN, the join key every German data leg needs
 * @param venueId    the stamped venue instrument id, or {@code 0}
 * @param category   the venue category ({@code STK}/{@code ETF}/{@code CUR}/{@code RES}/…)
 * @param context    the room's handle at decision time (the thread title) — the
 *                   single most useful field for a later re-reading: it says under
 *                   which headline this identity looked right
 * @param confidence the decider's OWN confidence, when it stated one. The weakest
 *                   of the three signals — a 4B model sounds equally certain when it
 *                   is right and when it is not — so it is recorded, never trusted.
 */
public record AliasProvenance(String tier, String isin, long venueId, String category,
        String context, String confidence) {

    /** Nothing known about the decision — the shape a bare two-argument learn() books. */
    public static final AliasProvenance UNKNOWN =
            new AliasProvenance(null, null, 0L, null, null, null);

    /** The provenance of a verdict that only knows which stage reached it. */
    public static AliasProvenance ofTier(String tier) {
        return new AliasProvenance(tier, null, 0L, null, null, null);
    }

    /** The same provenance with the room's handle attached. */
    public AliasProvenance withContext(String ctx) {
        return new AliasProvenance(tier, isin, venueId, category, ctx, confidence);
    }
}
