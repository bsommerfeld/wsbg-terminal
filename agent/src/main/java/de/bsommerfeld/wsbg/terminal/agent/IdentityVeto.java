package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.MatchJudge;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Guard stage 3 post-processor — a <b>decorator over {@link StrongTokenMatcher}</b>.
 * When the inner matcher hit on SPELLING (the exact-symbol/fuzzy-name fast paths
 * match spelling, not identity — live they bound SPD the party to a same-lettered
 * US ETF, „Kakao" the commodity talk to Kakao Corp), the identity judge decides
 * whether it is the same real-world ENTITY, may redirect to a different candidate,
 * or strikes the ticker entirely (news-only subject). Verdicts are cached per
 * subject for the process lifetime, so the veto costs ~one small model call per
 * unique subject name.
 *
 * <p>The decorator preserves the resolver's original short-circuit: when the inner
 * matcher missed, the veto defers ({@code Optional.empty()}, tower continues to the
 * judge/corpus stages); when the inner matcher hit but the veto STRUCK, the subject
 * is still CLAIMED as {@link SubjectMatch#newsOnly} so the tower stops — it does NOT
 * fall through to the later stages. Catalogued indices/commodities never reach this
 * stage (they are curated identity that bypasses the veto).
 */
final class IdentityVeto implements SubjectMatcher {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityVeto.class);
    private static final String VETO_NONE = "";

    private final SubjectMatcher inner;
    private final Supplier<MatchJudge> judge;

    /**
     * Process-lifetime verdict cache: subject (lower-cased) → the symbol the judge
     * approved, or {@code ""} when the judge struck the match. Keeps the veto at
     * ~one small model call per unique subject name. Instance field of the single
     * shared stage instance — the cache is this stage's own state.
     */
    private final Map<String, String> vetoVerdicts = new ConcurrentHashMap<>();

    IdentityVeto(SubjectMatcher inner, Supplier<MatchJudge> judge) {
        this.inner = inner;
        this.judge = judge;
    }

    @Override
    public Optional<SubjectMatch> match(MatchContext ctx) {
        Optional<SubjectMatch> base = inner.match(ctx);
        if (base.isEmpty()) return base; // inner missed → tower continues to judge/corpus
        YahooQuote picked = base.get().quote();
        YahooQuote vetoed = veto(ctx.query(), ctx.context(), picked, ctx.quotes());
        // Struck → still a CLAIM (news-only), so the tower stops here as the old else-branch did.
        return Optional.of(vetoed == null ? SubjectMatch.newsOnly(ctx.query()) : SubjectMatch.of(vetoed));
    }

    /**
     * Tier-1 veto: spelling matched a candidate — the judge now decides whether it
     * is the same real-world ENTITY, may redirect to a different candidate, or
     * strikes the ticker entirely (news-only subject). No-op without a judge.
     * Package-private for testing.
     */
    YahooQuote veto(String query, String context, YahooQuote picked, List<YahooQuote> quotes) {
        MatchJudge matchJudge = judge.get();
        if (matchJudge == null || picked == null) return picked;
        // Deterministic pre-confirm — the name IS the name: a multi-word subject whose
        // significant words are exactly the candidate's name (legal forms stopped out)
        // is identity by spelling, no judge needed. Kills the judge's false strikes on
        // near-identical names (live: 'Lithium Americas' vs "Lithium Americas Corp."
        // was struck twice) and saves the call. Single-word subjects (Kakao, SPD, KO)
        // and names with ANY extra significant word (Solactive-Trade-Republic-Index,
        // United States ANTIMONY) still face the judge.
        if (NameMatching.nameEquivalent(query, picked.displayName())) return picked;
        String key = query.toLowerCase(Locale.ROOT);
        String verdict = vetoVerdicts.get(key);
        if (verdict == null) {
            int i = matchJudge.pick(query, context, JudgeCandidates.candidateLines(quotes));
            YahooQuote approved = i >= 0 && i < quotes.size() ? quotes.get(i) : null;
            // A REDIRECT (judge prefers a different candidate than tier-1's pick) is only
            // trusted when the target's name deterministically shares a word with the
            // subject — that keeps the essential wrong-twin fix ('BYD' away from Boyd
            // Gaming, onto BYD Company) while catching the 4B's least-wrong-candidate
            // lapse (live: 'KO' → Kohl's). A redirect that fails the word check means the
            // judge implicitly rejected tier-1's pick too → strike (precision over recall).
            // A redirect between LISTINGS of the same company (name sets subset each
            // other after legal-form stripping: "Infineon Technologies AG" ↔ "Infineon
            // Technologies ADR") is pure churn — the judge keeps preferring ADRs
            // (live: Nokia→NOK, Infineon→IFNNY) while tier-1 already holds the primary
            // listing. Keep tier-1's pick; a genuine wrong-twin (Boyd Gaming ↔ BYD
            // Company — disjoint name sets) still redirects.
            if (approved != null && !approved.symbol().equalsIgnoreCase(picked.symbol())
                    && NameMatching.sameCompanyName(picked.displayName(), approved.displayName())) {
                approved = picked;
            }
            if (approved != null && !approved.symbol().equalsIgnoreCase(picked.symbol())
                    && !NameMatching.sharesSignificantWord(query, approved.displayName(), approved.symbol())) {
                // The judge's redirect target is word-implausible (least-wrong lapse,
                // live: 'KO'→Kohl's, 'IBM'→IBM0.F). The judge did NOT say "none",
                // so it affirmed an instrument exists — when tier-1's own pick is
                // name-plausible, fall back to IT rather than striking a legit value
                // (live: IBM lost its ticker entirely under strike-always).
                boolean keepPicked = NameMatching.sharesSignificantWord(query, picked.displayName(), picked.symbol());
                LOG.info("[RESOLVE] veto: '{}' redirect {} → {} rejected (no shared word) — {}",
                        query, picked.symbol(), approved.symbol(),
                        keepPicked ? "keeping tier-1 pick" : "ticker struck");
                approved = keepPicked ? picked : null;
            }
            verdict = approved == null ? VETO_NONE
                    : (approved.symbol() == null ? VETO_NONE : approved.symbol().toUpperCase(Locale.ROOT));
            vetoVerdicts.put(key, verdict);
            if (VETO_NONE.equals(verdict)) {
                LOG.info("[RESOLVE] veto: '{}' is not '{}' ({}) — ticker struck, news-only",
                        query, picked.displayName(), picked.symbol());
            } else if (!verdict.equalsIgnoreCase(picked.symbol())) {
                LOG.info("[RESOLVE] veto: '{}' redirected {} → {}", query, picked.symbol(), verdict);
            }
        }
        if (VETO_NONE.equals(verdict)) return null;
        if (verdict.equalsIgnoreCase(picked.symbol())) return picked;
        for (YahooQuote q : quotes) {
            if (verdict.equalsIgnoreCase(q.symbol())) return q;
        }
        return picked; // approved symbol not among this search's candidates — keep tier-1's pick
    }
}
