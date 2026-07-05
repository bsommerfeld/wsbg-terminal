package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.MatchJudge;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Guard stage 4 (tier 2) — when {@link StrongTokenMatcher} found nothing
 * (token/score couldn't decide), the LLM judge picks the ONE candidate that IS the
 * subject, or none (the guard: the subject stays unresolved). No-op when no judge
 * is wired. An EQUITY-only gate stops a pure theme/topic being promoted to an
 * ETF/index/crypto ticker with a bogus live price, and a prefix-trap guard rejects
 * a spelling-bleed the judge may confirm ('Polen' ⊂ 'Polenergia').
 */
final class JudgeMatcher implements SubjectMatcher {

    private static final Logger LOG = LoggerFactory.getLogger(JudgeMatcher.class);

    private final Supplier<MatchJudge> judge;

    JudgeMatcher(Supplier<MatchJudge> judge) {
        this.judge = judge;
    }

    @Override
    public Optional<SubjectMatch> match(MatchContext ctx) {
        YahooQuote q = judge(ctx.query(), ctx.context(), ctx.quotes());
        return q == null ? Optional.empty() : Optional.of(SubjectMatch.of(q));
    }

    /**
     * Tier 2: let the LLM judge pick the ONE candidate that IS the subject — or
     * none. Package-private for testing.
     */
    YahooQuote judge(String query, String context, List<YahooQuote> quotes) {
        MatchJudge matchJudge = judge.get();
        if (matchJudge == null || quotes == null || quotes.isEmpty()) return null;
        int i = matchJudge.pick(query, context, JudgeCandidates.candidateLines(quotes));
        if (i < 0 || i >= quotes.size()) return null;
        YahooQuote best = quotes.get(i);
        // EQUITY-only gate for the fuzzy fallback: a theme/topic ("Biotech",
        // "Drones", "Semiconductor") that has no token/score match must NOT be
        // promoted to an ETF/index/currency/crypto ticker with a live price the
        // room never meant. Only a real, confidently-named stock survives Tier 2;
        // anything else stays tickerless (its Yahoo NEWS is kept regardless,
        // attached independent of the ticker in resolveAll).
        String type = best.quoteType() == null ? "" : best.quoteType().trim().toUpperCase(Locale.ROOT);
        if (!type.equals("EQUITY")) return null;
        // Prefix-trap guard: tier 2 has no token overlap by definition, so the judge
        // may confirm a mere spelling-bleed ('Polen' ⊂ 'Polenergia' — live: a Poland/
        // Russia war thread got a Polish energy-stock ticker + a filler line). The
        // catch is that legit tier-2 cross-name picks (Google→Alphabet) are disjoint
        // strings, NOT prefixes — so this rejects the trap without touching them.
        if (NameMatching.isPrefixTrap(query, best.displayName())) {
            LOG.info("[RESOLVE] tier-2: '{}' → {} rejected (prefix-trap, not identity)",
                    query, best.symbol());
            return null;
        }
        return best;
    }
}
