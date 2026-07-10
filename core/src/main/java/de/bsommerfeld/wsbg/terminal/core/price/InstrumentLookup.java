package de.bsommerfeld.wsbg.terminal.core.price;

import java.util.List;
import java.util.Optional;

/**
 * The venue candidate-search seam: a subject name in, the venue's typed search
 * candidates out — <b>unfiltered and unranked</b>. This is deliberately dumber than
 * {@link PriceSource}: no picking, no coverage gates, no twin guards. The caller
 * (the identity desk) hands ALL candidates to the LLM judge together with the
 * Yahoo facts and lets it decide which one — if any — IS the subject; the verdict
 * is then carried as a stamped {@link PriceRef}, so no later stage re-resolves
 * the name.
 *
 * <p>Implementations must never throw — a venue failure returns an empty list
 * (the desk then judges on the facts it has, or abstains).
 */
public interface InstrumentLookup {

    /** All typed candidates the venue's search returns for the query; empty on miss/failure. */
    List<InstrumentCandidate> search(String query);

    /**
     * The venue's CURRENT price for one candidate (venue currency) — the desk's
     * plausibility fact: a judged pick whose venue price sits orders of magnitude
     * off the Yahoo reference is the wrong paper (a depositary receipt, a bond, a
     * same-named twin), no matter how well the name matched. Optional capability:
     * the default answers empty (no check possible), and implementations must
     * never throw.
     */
    default Optional<Double> lastPrice(InstrumentCandidate candidate) {
        return Optional.empty();
    }
}
