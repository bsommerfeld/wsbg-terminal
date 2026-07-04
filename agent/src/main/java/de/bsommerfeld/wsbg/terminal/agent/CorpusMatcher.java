package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.MatchJudge;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Guard stage 5 (tier 3) — neither token/score nor the judge found the subject
 * among Yahoo's candidates, so ask the LOCAL corpus (SEC US listings + the learned
 * wallstreet-online ISIN memory). Lexical top-K → judge pick over GROUND-TRUTH
 * candidate lines (name + type + exchange from the feed) → word-guard (the same
 * least-wrong protection as the veto redirect). Rescues known instruments Yahoo's
 * search missed (MicroStrategy→MSTR class) and German names only the learned WSO
 * memory carries. Verdicts cached per subject.
 */
final class CorpusMatcher implements SubjectMatcher {

    private static final Logger LOG = LoggerFactory.getLogger(CorpusMatcher.class);

    /** How many corpus candidates the judge sees at most — never the whole list. */
    private static final int CORPUS_CANDIDATES = 8;

    private final Supplier<MatchJudge> judge;
    private final Supplier<InstrumentCorpus> corpus;

    /** Tier-3 verdict cache: subject (lower-cased) → the corpus pick (empty = judged none). */
    private final Map<String, Optional<InstrumentEntry>> corpusVerdicts = new ConcurrentHashMap<>();

    CorpusMatcher(Supplier<MatchJudge> judge, Supplier<InstrumentCorpus> corpus) {
        this.judge = judge;
        this.corpus = corpus;
    }

    @Override
    public Optional<SubjectMatch> match(MatchContext ctx) {
        YahooQuote q = corpus(ctx.query(), ctx.context());
        return q == null ? Optional.empty() : Optional.of(SubjectMatch.of(q));
    }

    /**
     * Tier 3: ask the local corpus, judge over ground-truth candidate lines, then
     * word-guard the pick. Package-private for testing.
     */
    YahooQuote corpus(String query, String context) {
        InstrumentCorpus instrumentCorpus = corpus.get();
        MatchJudge matchJudge = judge.get();
        if (instrumentCorpus == null || matchJudge == null) return null;
        // A 1-2 char subject that even Yahoo's search couldn't place is almost
        // certainly room slang, not an instrument (live: 'OP' — Reddit's Original
        // Poster — lexically hit "Empire State Realty OP, L.P."). Tier 3 sits
        // BEHIND Yahoo, so legit short symbols (MU, KO) never reach this guard.
        if (query.length() < 3) return null;
        String key = query.toLowerCase(Locale.ROOT);
        Optional<InstrumentEntry> cached = corpusVerdicts.get(key);
        if (cached == null) {
            List<InstrumentEntry> cands = instrumentCorpus.search(query, CORPUS_CANDIDATES);
            InstrumentEntry picked = null;
            if (!cands.isEmpty()) {
                List<String> lines = new ArrayList<>(cands.size());
                for (var c : cands) lines.add(c.candidateLine());
                int i = matchJudge.pick(query, context, lines);
                if (i >= 0 && i < cands.size()
                        && NameMatching.sharesSignificantWord(query, cands.get(i).name(), cands.get(i).symbol())) {
                    picked = cands.get(i);
                }
            }
            cached = Optional.ofNullable(picked);
            corpusVerdicts.put(key, cached);
            if (picked != null) {
                LOG.info("[RESOLVE] corpus: '{}' → {} ({}) [{}]",
                        query, picked.symbol(), picked.name(), picked.source());
            }
        }
        return cached.map(e -> new YahooQuote(
                e.symbol(), e.name(), e.name(),
                e.type() == null ? "EQUITY" : e.type(),
                e.exchange(), e.exchange(), null, null,
                Double.NaN, Double.NaN, 0.0)).orElse(null);
    }
}
