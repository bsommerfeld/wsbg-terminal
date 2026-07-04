package de.bsommerfeld.wsbg.terminal.agent;

import java.util.Optional;

/**
 * One ordered stage of the resolver's guard tower. Each stage is a single-purpose
 * identity strategy (curated index/commodity, strong token match + veto, LLM judge
 * fallback, local corpus) that either CLAIMS the subject (a present
 * {@link SubjectMatch}, stopping the tower) or defers to the next stage (empty).
 */
interface SubjectMatcher {

    /** Empty = "not me, next stage". Present = "I claim this subject" (ticker or news-only). */
    Optional<SubjectMatch> match(MatchContext ctx);
}
