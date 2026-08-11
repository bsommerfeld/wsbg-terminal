package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.MatchJudge;
import de.bsommerfeld.wsbg.terminal.instruments.AliasProvenance;
import de.bsommerfeld.wsbg.terminal.instruments.AliasStore;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The last stage of the tower — the one that reads the room's own words.
 *
 * <p>Every stage before this one matches on something the outside world already
 * knows: a catalogue, a Yahoo search result, a registered corpus name. The cage does
 * not write those. It writes „Keinmetall" for Rheinmetall after a red day, „SpaceEx"
 * and „SpaceSex" for SpaceX, and a fresh coinage whenever a stock embarrasses
 * someone. None of them share a whole token with the name they corrupt, so every
 * earlier stage is structurally blind to them, and the subject splits off as its own
 * tickerless unit: the room's talk about ONE company lands in two separate places —
 * two headline threads, two evidence piles.
 *
 * <p>This was papered over by a hand-kept glossary of thirteen nicknames that
 * somebody had to notice and type in. That glossary is gone. This stage replaces it
 * with a mechanism that learns the fourteenth by itself.
 *
 * <h3>How</h3>
 * Two cheap proposals, then one judgement:
 * <ol>
 *   <li><b>Near misses from the corpus</b> — a trigram-indexed lookup for names
 *       within a couple of edits of the spelling. „Keinmetall" is two edits from
 *       „Rheinmetall", „SpaceEx" one from „SpaceX".</li>
 *   <li><b>What this very thread already resolved</b> — a coinage in a thread that is
 *       demonstrably about one company almost always means that company. This catches
 *       the nicknames that look nothing like the name (a logo, a person, a joke), which
 *       distance alone can never reach.</li>
 * </ol>
 * The identity judge then picks one or none, with the thread title as context — the
 * same fail-closed contract every other judge call uses. <b>One call per novel
 * spelling, ever</b>: the verdict is written into the learned memory, positive or
 * negative, so the next occurrence is answered from the ledger. That is what makes
 * an aggressive forgetting horizon affordable — re-learning costs a single call.
 *
 * <h3>Why the negative is written too</h3>
 * Most unresolved subjects are not coinages at all; they are macro themes, memes and
 * ordinary words. Without booking the considered "this is no instrument", every one
 * of them would re-enter this stage on every mention and pay for a judge call each
 * time. The negative decays like everything else, so a "no" stops blocking a fresh
 * look once the world may have moved on.
 */
final class CoinageMatcher implements SubjectMatcher {

    private static final Logger LOG = LoggerFactory.getLogger(CoinageMatcher.class);

    /** How many near misses the judge sees at most. */
    private static final int NEAREST_CANDIDATES = 6;
    /** How many of the thread's own resolved instruments ride along. */
    private static final int NEIGHBOUR_CANDIDATES = 4;
    /**
     * Below this length a spelling carries no signal — the tower already sits behind
     * Yahoo and the corpus, so anything this short that got here is room noise.
     */
    private static final int MIN_LENGTH = 4;

    private final Supplier<MatchJudge> judge;
    private final Supplier<InstrumentCorpus> corpus;
    private final Supplier<AliasStore> aliases;

    /** Per-process verdict cache: spelling → the pick (empty = judged none). */
    private final Map<String, Optional<InstrumentEntry>> verdicts = new ConcurrentHashMap<>();

    CoinageMatcher(Supplier<MatchJudge> judge, Supplier<InstrumentCorpus> corpus,
            Supplier<AliasStore> aliases) {
        this.judge = judge;
        this.corpus = corpus;
        this.aliases = aliases;
    }

    @Override
    public Optional<SubjectMatch> match(MatchContext ctx) {
        InstrumentEntry picked = pick(ctx);
        return picked == null ? Optional.empty()
                : Optional.of(new SubjectMatch(picked.symbol(), picked.name(), quoteOf(picked),
                        picked.isin(), 0L, picked.type()));
    }

    /** The coinage verdict for one subject. Package-private for testing. */
    InstrumentEntry pick(MatchContext ctx) {
        MatchJudge matchJudge = judge.get();
        InstrumentCorpus instrumentCorpus = corpus.get();
        String query = ctx.query() == null ? "" : ctx.query().trim();
        if (matchJudge == null || instrumentCorpus == null || query.length() < MIN_LENGTH) {
            return null;
        }
        String key = query.toLowerCase(Locale.ROOT);
        Optional<InstrumentEntry> cached = verdicts.get(key);
        if (cached != null) return cached.orElse(null);

        AliasStore store = aliases.get();
        // A settled "no instrument" from the ledger — do not pay for the same verdict
        // twice. This is the guard that keeps the room's ordinary vocabulary cheap.
        if (store != null && store.isRuledOut(query)) {
            verdicts.put(key, Optional.empty());
            return null;
        }

        List<InstrumentEntry> candidates = candidates(instrumentCorpus, ctx, query);
        InstrumentEntry picked = null;
        if (!candidates.isEmpty()) {
            List<String> lines = new ArrayList<>(candidates.size());
            for (InstrumentEntry c : candidates) lines.add(c.candidateLine());
            int i = matchJudge.pick(query, ctx.context(), lines);
            if (i >= 0 && i < candidates.size()) picked = candidates.get(i);
        }
        verdicts.put(key, Optional.ofNullable(picked));
        if (picked != null) {
            LOG.info("[RESOLVE] coinage: '{}' → {} ({}) — the room's own word, now learned",
                    query, picked.symbol(), picked.name());
        } else if (store != null && !candidates.isEmpty()) {
            // Only a spelling that HAD plausible candidates and was still rejected is a
            // considered no. Having found nothing to offer says something about the
            // corpus, not about the word.
            store.learnNothing(query, provenance(ctx));
        }
        return picked;
    }

    /**
     * The proposal set: near misses from the corpus first (they carry the concrete
     * ground-truth line), then the instruments this same thread already settled, so a
     * nickname that resembles nothing still has its context on the table. Deduplicated
     * by symbol, order preserved — the judge reads a numbered list, and a duplicate
     * would split its own vote.
     */
    private List<InstrumentEntry> candidates(InstrumentCorpus corpus, MatchContext ctx, String query) {
        Map<String, InstrumentEntry> bySymbol = new LinkedHashMap<>();
        for (InstrumentEntry e : corpus.nearest(query, NEAREST_CANDIDATES)) {
            bySymbol.putIfAbsent(e.symbol().toUpperCase(Locale.ROOT), e);
        }
        int taken = 0;
        for (String neighbour : ctx.neighbours()) {
            if (taken >= NEIGHBOUR_CANDIDATES) break;
            for (InstrumentEntry e : corpus.search(neighbour, 2)) {
                if (bySymbol.putIfAbsent(e.symbol().toUpperCase(Locale.ROOT), e) == null) {
                    taken++;
                    break;
                }
            }
        }
        return List.copyOf(bySymbol.values());
    }

    private static AliasProvenance provenance(MatchContext ctx) {
        return AliasProvenance.ofTier("CoinageMatcher").withContext(ctx.context());
    }

    private static YahooQuote quoteOf(InstrumentEntry e) {
        return new YahooQuote(e.symbol(), e.name(), e.name(),
                e.type() == null ? "EQUITY" : e.type(),
                e.exchange(), e.exchange(), null, null,
                Double.NaN, Double.NaN, 0.0);
    }
}
