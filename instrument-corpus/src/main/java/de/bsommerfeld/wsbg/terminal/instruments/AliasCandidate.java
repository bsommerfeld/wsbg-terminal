package de.bsommerfeld.wsbg.terminal.instruments;

/**
 * One reading a spelling has been given, folded out of every posting the ledger
 * holds for it — plus how alive that reading currently is.
 *
 * <p>A spelling may carry SEVERAL of these at once, and that is the point: „EPS"
 * standing for a key figure and for Seiko Epson is not a contradiction the store
 * has to settle, it is a finding it has to report. Which one is meant needs the
 * context the identity judge has and this file does not; a listing that differs
 * only by venue is the price chain's call. The ledger records, it does not
 * arbitrate.
 *
 * @param symbol     the ticker this reading points at, or {@code null} for the
 *                   considered "this spelling is no instrument" (booked so the
 *                   same dead end is not re-judged on every single mention)
 * @param strength   the reading's vitality AT THE MOMENT IT WAS READ: every
 *                   posting adds one, and the sum halves every
 *                   {@link AliasStore#HALF_LIFE_DAYS} days. A spelling the room
 *                   keeps using stays strong for free; one that was coined once
 *                   and never again fades out on its own.
 * @param postings   how often this reading was booked, undecayed — the raw count
 * @param lastSeen   epoch seconds of the most recent posting
 * @param provenance the why behind the most recent posting
 */
public record AliasCandidate(String symbol, double strength, int postings, long lastSeen,
        AliasProvenance provenance) {

    /** The considered "no instrument" reading — a negative fact, booked like any other. */
    public boolean isNone() {
        return symbol == null;
    }

    /** Whether this reading still counts as learned at all (see {@link AliasStore#LIVE_THRESHOLD}). */
    public boolean isLive() {
        return strength >= AliasStore.LIVE_THRESHOLD;
    }

    /**
     * Whether this reading is solid enough to skip the resolver's work. Deliberately
     * higher than merely being alive: a single posting must never short-circuit the
     * guard tower, or one wrong judge call would keep re-confirming itself.
     */
    public boolean isTrusted() {
        return strength >= AliasStore.TRUSTED_STRENGTH;
    }
}
