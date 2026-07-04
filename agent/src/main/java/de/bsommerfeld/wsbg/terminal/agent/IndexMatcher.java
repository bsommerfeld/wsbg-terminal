package de.bsommerfeld.wsbg.terminal.agent;

import java.util.Optional;

/**
 * Guard stage 1 — a known stock index (DAX, S&amp;P 500, …) binds straight to its
 * Yahoo {@code ^}-symbol, bypassing the exact-symbol fast-path that would otherwise
 * grab a same-named tradeable ticker (e.g. „DAX" → a $44 US ETF) and FX-convert it
 * into nonsense. Index symbols are priced in points. Curated identity — bypasses
 * the veto.
 */
final class IndexMatcher implements SubjectMatcher {

    @Override
    public Optional<SubjectMatch> match(MatchContext ctx) {
        IndexCatalog.Index index = IndexCatalog.lookup(ctx.query());
        return index == null ? Optional.empty()
                : Optional.of(SubjectMatch.curated(index.symbol(), index.displayName()));
    }
}
