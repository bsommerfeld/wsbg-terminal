package de.bsommerfeld.wsbg.terminal.agent;

import java.util.Optional;

/**
 * Guard stage 2 — a known commodity („Gold", „Öl", …) binds to its Yahoo future
 * ({@code GC=F}, {@code CL=F}), the actual commodity price, not a same-named mining
 * stock or a „Gold.com" pennystock. NOT a guess: „Gold" IS gold. Priced in native
 * USD, not FX-converted. Curated identity — bypasses the veto. Only consulted when
 * {@link IndexMatcher} did not claim the subject (tower order preserves the old
 * {@code commodity = index == null ? lookup : null}).
 */
final class CommodityMatcher implements SubjectMatcher {

    @Override
    public Optional<SubjectMatch> match(MatchContext ctx) {
        CommodityCatalog.Commodity commodity = CommodityCatalog.lookup(ctx.query());
        return commodity == null ? Optional.empty()
                : Optional.of(SubjectMatch.curated(commodity.symbol(), commodity.displayName()));
    }
}
