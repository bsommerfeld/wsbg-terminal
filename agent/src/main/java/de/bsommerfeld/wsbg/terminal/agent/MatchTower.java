package de.bsommerfeld.wsbg.terminal.agent;

import java.util.List;
import java.util.Optional;

/**
 * The guard tower — an ordered list of {@link SubjectMatcher} stages, run
 * first-claims-wins. No logic of its own; it makes the resolver's formerly-implicit
 * cascade a legible, testable, ordered list. The wired order is load-bearing and
 * empirically tuned (Index → Commodity → Strong⊕Veto → Judge → Corpus): it encodes
 * real fixes (DAX-ETF trap, „Gold" mining-stock trap, SPD/Kakao veto, „OP"/Polen
 * prefix-trap, tier-3 rescue) and MUST be preserved.
 */
final class MatchTower {

    private final List<SubjectMatcher> stages;

    MatchTower(List<SubjectMatcher> stages) {
        this.stages = List.copyOf(stages);
    }

    /** First stage to CLAIM the subject wins; empty means no stage claimed it (tickerless theme). */
    Optional<SubjectMatch> resolve(MatchContext ctx) {
        for (SubjectMatcher stage : stages) {
            Optional<SubjectMatch> m = stage.match(ctx);
            if (m.isPresent()) return m;
        }
        return Optional.empty();
    }

    /** The wired stages, in order — exposed so the order can be asserted in a test. */
    List<SubjectMatcher> stages() {
        return stages;
    }
}
