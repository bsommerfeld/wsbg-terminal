package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.aggregator.NewsAggregator;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.price.PriceRef;
import de.bsommerfeld.wsbg.terminal.core.price.PriceSource;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient.SearchResult;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, code-side resolution of an editorial <em>subject</em> against
 * Yahoo Finance — no LLM tool loop. The subject-extraction stage hands a subject
 * name here, and the resolver decides what it is and what data backs it.
 *
 * <p>A subject is "anything market-relevant" (see the prompt), so resolution
 * branches three ways from a single Yahoo search:
 * <ul>
 *   <li><b>Instrument</b> — a quote whose name confidently matches the query
 *       (token-overlap, not Yahoo's fuzzy guess). Carries a validated ticker,
 *       a live {@link MarketSnapshot}, and news.</li>
 *   <li><b>Theme/person</b> — no confident ticker, but Yahoo has news (e.g.
 *       "Trump", "inflation", "tariffs"). Carries news only.</li>
 *   <li><b>Nothing</b> — no ticker and no news; the headline rests on the
 *       cluster's own sentiment.</li>
 * </ul>
 *
 * <p>The identity decision is the ordered {@link MatchTower}
 * (Index → Commodity → Strong⊕Veto → Judge → Corpus); the text/venue/quote
 * algebra behind it lives in {@link NameMatching}, {@link VenuePreference} and
 * {@link QuoteClassifier}. This class is the orchestrator: it wires the deps, runs
 * the three phases of {@link #resolveAll} (search+match → snapshots → assemble),
 * and owns the output records.
 */
public final class TickerResolver {

    private static final Logger LOG = LoggerFactory.getLogger(TickerResolver.class);

    /**
     * How many quote candidates to pull from the (single) search response.
     * Larger than the old 3 so the exchange-preference in {@link StrongTokenMatcher}
     * has the primary listing to choose from — e.g. {@code MUV2.DE} may sit
     * behind a foreign secondary line ({@code 1MUV2.MI}) in Yahoo's order.
     * Load-neutral: these come back in the same search call, no extra request.
     */
    private static final int MAX_QUOTES = 8;
    /**
     * How many Yahoo news items to carry per subject. Kept moderate not for any
     * technical limit (the search endpoint returns them in the same response, so
     * more news is free) but to keep the compose prompt readable; the desk wants
     * breadth of evidence, so this is deliberately generous.
     */
    private static final int NEWS_COUNT = 6;

    /**
     * Cap on the second-hop: distinct {@code relatedTickers} mentioned across a
     * subject's news that we pull a live snapshot for. This is what lets a
     * person/theme subject ("Trump") surface the instruments its news is about
     * ("…sends oil climbing" → CL=F, XOM) with their live moves — the raw
     * material for a causal read, without the model having to guess a ticker.
     */
    private static final int MAX_RELATED = 4;

    /** News items to attach to each related instrument (so it carries a "why", not just a move). */
    private static final int RELATED_NEWS_COUNT = 2;

    /**
     * The LLM identity judge — a discrete "which of these candidates IS the
     * subject, or none" pick (with a hard bias to none), fed the room's thread
     * title as context so a generic word is distinguishable from a same-named
     * instrument. It serves TWO spots:
     * <ul>
     *   <li><b>Tier-2 fallback</b> ({@link JudgeMatcher}) when token/score matching
     *       found nothing.</li>
     *   <li><b>Veto over every tier-1 Yahoo pick</b> ({@link IdentityVeto}): the
     *       exact-symbol/fuzzy-name fast paths match on SPELLING, not identity;
     *       every search-derived pick is judged ONCE per subject (verdict cached for
     *       the process lifetime); the judge may confirm, redirect, or strike the
     *       ticker (news-only). Catalogued indices/commodities bypass the veto — they
     *       are curated identity.</li>
     * </ul>
     * The judge returns the 0-based index of the matching candidate, or -1.
     */
    @FunctionalInterface
    public interface MatchJudge {
        int pick(String subject, String context, List<String> candidateNames);
    }

    private final YahooFinanceClient yahoo;

    /** Identity judge; null disables veto + tier 2 + tier 3. Set post-construction by {@code EditorialAgent}. */
    private MatchJudge matchJudge;

    /**
     * Tier 3 — the auto-updating local instrument corpus (SEC US listings + the
     * learned wallstreet-online ISIN memory). Null in tests/harnesses — tier 3 off.
     */
    private InstrumentCorpus corpus;

    /**
     * The live price chain (L&amp;S → …, EUR). Optional: injected only in production
     * (AppModule); null in tests, where snapshots fall back to the Yahoo batch. Yahoo
     * stays the SEARCH + NEWS source regardless — the chain only supplies the price.
     */
    private PriceSource priceSource;

    /** The multi-source news pool (Yahoo + …). Null in tests, where news comes from the search result. */
    private NewsAggregator newsAggregator;

    // ---- The guard tower and its stages ----
    // Single shared stage instances: the veto/corpus verdict caches are instance fields
    // of these, so caching survives across calls. The judge/corpus deps are read LIVE
    // via suppliers so the post-construction setters below take effect.
    private final IdentityVeto identityVeto = new IdentityVeto(new StrongTokenMatcher(), () -> matchJudge);
    private final JudgeMatcher judgeMatcher = new JudgeMatcher(() -> matchJudge);
    private final CorpusMatcher corpusMatcher = new CorpusMatcher(() -> matchJudge, () -> corpus);
    private final MatchTower tower = new MatchTower(List.of(
            new IndexMatcher(), new CommodityMatcher(), identityVeto, judgeMatcher, corpusMatcher));

    public TickerResolver(YahooFinanceClient yahoo) {
        this.yahoo = yahoo;
    }

    /** Installs the tier-2 LLM match judge. Forwarded by {@code EditorialAgent}; null in tests. */
    void setMatchJudge(MatchJudge matchJudge) {
        this.matchJudge = matchJudge;
    }

    /** Installs the tier-3 instrument corpus. Forwarded by {@code EditorialAgent}; null in tests. */
    void setInstrumentCorpus(InstrumentCorpus corpus) {
        this.corpus = corpus;
    }

    /** Installs the live price chain. Forwarded by {@code EditorialAgent}; null in tests. */
    void setPriceSource(PriceSource priceSource) {
        this.priceSource = priceSource;
    }

    /** Installs the multi-source news pool. Forwarded by {@code EditorialAgent}; null in tests. */
    void setNewsAggregator(NewsAggregator newsAggregator) {
        this.newsAggregator = newsAggregator;
    }

    /** Resolves a single subject with the default related allowance ({@link #MAX_RELATED}). */
    public ResolvedSubject resolve(String subjectName) {
        return resolve(subjectName, MAX_RELATED);
    }

    /**
     * Resolves a single subject name with a specific related allowance. Never
     * throws — any Yahoo failure degrades to a {@code nothing} result so the
     * headline still publishes. (Thin wrapper around {@link #resolveAll}.)
     *
     * @param maxRelated cap on second-hop related instruments ({@code 0} = none)
     */
    public ResolvedSubject resolve(String subjectName, int maxRelated) {
        return resolveAll(List.of(subjectName == null ? "" : subjectName),
                new int[]{Math.max(0, maxRelated)}).get(0);
    }

    /** Context-less variant (tests, single lookups): the judge sees only the names. */
    public List<ResolvedSubject> resolveAll(List<String> names, int[] relatedAlloc) {
        return resolveAll(names, relatedAlloc, "");
    }

    /**
     * Resolves many subjects at once with a per-subject related allowance, and
     * — crucially — fetches every needed price snapshot (each subject's own
     * ticker + all related tickers) in a single batched call
     * ({@link YahooFinanceClient#fetchCharts}, spark endpoint). That collapses
     * what used to be ~one chart request per ticker into 1–2 requests, which is
     * the big Yahoo-call saving when a cluster names many subjects.
     *
     * @param names        subject names (slang already normalised)
     * @param relatedAlloc per-subject related cap, index-aligned with {@code names}
     * @param context      the room's handle on these subjects (the thread title) —
     *                     fed to the identity judge so „Kakao" in a commodity thread
     *                     is distinguishable from Kakao Corp; may be blank
     */
    public List<ResolvedSubject> resolveAll(List<String> names, int[] relatedAlloc, String context) {
        int n = names == null ? 0 : names.size();
        List<Pending> pending = new ArrayList<>(n);
        LinkedHashSet<String> symbols = new LinkedHashSet<>();

        // Phase 1 — search each subject, pick its ticker, gather related tickers
        // (+ their news). No snapshots yet; just collect the symbols we'll need.
        for (int i = 0; i < n; i++) {
            // Deterministic slang→canonical FIRST (the 4B model applies the prompt aliases
            // unreliably): „Rheiner" becomes „Rheinmetall" here, every time, so it resolves to
            // RHM.DE and merges instead of splitting off as a tickerless name-unit.
            String query = WsbgJargon.canonicalize(names.get(i) == null ? "" : names.get(i).trim());
            int maxRelated = relatedAlloc != null && i < relatedAlloc.length ? Math.max(0, relatedAlloc[i]) : 0;
            Pending p = searchAndMatch(query, context, maxRelated);
            if (p.ownTicker() != null) symbols.add(p.ownTicker().toUpperCase(Locale.ROOT));
            symbols.addAll(p.relSyms());
            pending.add(p);
        }

        // Phase 2 — snapshots (the price-chain-vs-Yahoo-batch split).
        Map<String, MarketSnapshot> snaps = fetchSnapshots(pending, symbols);

        // Phase 3 — assemble, wiring the batched snapshots back in.
        return assemble(pending, snaps);
    }

    /**
     * Phase-1 body for ONE subject: Yahoo search, rate-limit short-circuit, the
     * {@link MatchTower} identity decision, news resolution and related collection.
     * Never throws — any failure degrades to an empty result.
     */
    private Pending searchAndMatch(String query, String context, int maxRelated) {
        if (query.isEmpty() || yahoo == null) {
            return Pending.empty(query);
        }
        try {
            SearchResult sr = yahoo.search(query, MAX_QUOTES, NEWS_COUNT);
            if (sr.rateLimited()) {
                // Yahoo is throttling — leave the subject unresolved (skip), don't
                // guess a tickerless unit, and don't fan out into more calls.
                return Pending.rateLimited(query);
            }
            SubjectMatch match = tower.resolve(new MatchContext(query, context, sr.quotes())).orElse(null);
            String ownTicker = match == null ? null : match.symbol();
            String canonical = match == null ? query : match.canonicalName();

            // News: triangulated across all sources by the resolved ticker AND the
            // company name — Yahoo answers the symbol, the German name-addressed venues
            // (wallstreet-online) answer the name, closing the German small-cap gap. A
            // ticker-less theme keeps Yahoo's query-news (the only handle without a
            // symbol); the tests (no aggregator) also keep the search news.
            List<RawNewsItem> news = resolveNews(ownTicker, canonical, sr);
            Map<String, List<RawNewsItem>> relNews = new LinkedHashMap<>();
            List<String> relSyms = collectRelated(news, ownTicker, maxRelated, relNews);
            return new Pending(query, canonical, ownTicker, news, relSyms, relNews, false);
        } catch (Exception e) {
            LOG.debug("Subject resolution failed for '{}': {}", query, e.getMessage());
            return Pending.empty(query);
        }
    }

    /** The triangulated-vs-search-news choice for a resolved subject. */
    private List<RawNewsItem> resolveNews(String ownTicker, String canonical, SearchResult sr) {
        return (newsAggregator != null && ownTicker != null)
                ? newsAggregator.newsFor(ownTicker, canonical, NEWS_COUNT)
                : (sr.news() != null ? sr.news() : List.of());
    }

    /**
     * The {@code relatedTickers} second-hop fan-out: distinct related symbols across
     * the subject's news (bounded by {@code maxRelated}), each with its own news,
     * collected into {@code outRelNews}. Returns the ordered related symbols.
     */
    private List<String> collectRelated(List<RawNewsItem> news, String ownTicker, int maxRelated,
            Map<String, List<RawNewsItem>> outRelNews) {
        List<String> relSyms = new ArrayList<>();
        if (maxRelated <= 0) return relSyms;
        Set<String> seen = new HashSet<>();
        if (ownTicker != null) seen.add(ownTicker.toUpperCase(Locale.ROOT));
        collect:
        for (RawNewsItem ni : news) {
            if (ni.relatedTickers() == null) continue;
            for (String raw : ni.relatedTickers()) {
                if (raw == null) continue;
                String sym = raw.trim().toUpperCase(Locale.ROOT);
                if (sym.isEmpty() || !seen.add(sym)) continue;
                relSyms.add(sym);
                List<RawNewsItem> rn = newsAggregator != null
                        ? newsAggregator.newsFor(sym, RELATED_NEWS_COUNT)
                        : yahoo.getNewsForSymbol(sym, RELATED_NEWS_COUNT);
                outRelNews.put(sym, rn == null ? List.of() : rn);
                if (relSyms.size() >= maxRelated) break collect;
            }
        }
        return relSyms;
    }

    /**
     * Phase 2 — price snapshots. With the live price chain each subject's OWN ticker
     * goes through the chain (EUR, looked up by name); related tickers (second-hop
     * context, ticker-only) stay on the Yahoo batch. Without a chain (tests)
     * everything uses the Yahoo batch as before.
     */
    private Map<String, MarketSnapshot> fetchSnapshots(List<Pending> pending, Set<String> symbols) {
        Map<String, MarketSnapshot> snaps = new LinkedHashMap<>();
        if (priceSource != null) {
            for (Pending p : pending) {
                if (p.ownTicker() == null) continue;
                String key = p.ownTicker().toUpperCase(Locale.ROOT);
                if (snaps.containsKey(key)) continue;
                priceSource.snapshot(new PriceRef(p.canonical(), p.ownTicker()))
                        .ifPresent(s -> snaps.put(key, s));
            }
            // Own tickers are fully the chain's job — it already includes Yahoo as a
            // fallback (and in the overnight gap returns the last close, marked stale,
            // rather than nothing). So the Yahoo batch covers ONLY related/context
            // tickers; never re-fetch an own ticker the chain already handled.
            LinkedHashSet<String> ownTickers = new LinkedHashSet<>();
            for (Pending p : pending) {
                if (p.ownTicker() != null) ownTickers.add(p.ownTicker().toUpperCase(Locale.ROOT));
            }
            LinkedHashSet<String> rest = new LinkedHashSet<>(symbols);
            rest.removeAll(ownTickers);
            rest.removeAll(snaps.keySet());
            if (yahoo != null && !rest.isEmpty()) snaps.putAll(yahoo.fetchCharts(new ArrayList<>(rest)));
        } else if (yahoo != null && !symbols.isEmpty()) {
            snaps.putAll(yahoo.fetchCharts(new ArrayList<>(symbols)));
        }
        return snaps;
    }

    /** Phase 3 — assemble the resolved subjects, wiring the batched snapshots back in. */
    private List<ResolvedSubject> assemble(List<Pending> pending, Map<String, MarketSnapshot> snaps) {
        List<ResolvedSubject> out = new ArrayList<>(pending.size());
        int rateLimited = 0;
        for (Pending p : pending) {
            MarketSnapshot ownSnap = p.ownTicker() == null ? null
                    : snaps.get(p.ownTicker().toUpperCase(Locale.ROOT));
            List<RelatedInstrument> related = new ArrayList<>();
            for (String sym : p.relSyms()) {
                MarketSnapshot s = snaps.get(sym);
                if (s == null || !s.hasPrice()) continue;
                related.add(new RelatedInstrument(sym, s, p.relNews().getOrDefault(sym, List.of())));
            }
            if (p.ownTicker() != null) {
                LOG.info("[RESOLVE] '{}' → {} ({}{})", p.query(), p.ownTicker(),
                        ownSnap != null && ownSnap.hasPrice() ? "with market data" : "no chart",
                        related.isEmpty() ? "" : ", +" + related.size() + " related");
            }
            if (p.rateLimited()) rateLimited++;
            out.add(new ResolvedSubject(p.query(), p.canonical(), p.ownTicker(), ownSnap, p.news(), related,
                    p.rateLimited()));
        }
        if (rateLimited > 0) {
            LOG.warn("[RESOLVE] Yahoo rate-limited — {} subject(s) un-enriched this pass "
                    + "(still published from room evidence; re-enriched on next evidence)", rateLimited);
        }
        return out;
    }

    // ---- Thin delegators to the guard-tower stages (package-private test entry points) ----
    // The identity logic + verdict caches live in the stage classes; these keep the
    // resolver-level test surface (setMatchJudge → veto/judge/corpus) intact.

    YahooQuote vetoMatch(String query, String context, YahooQuote picked, List<YahooQuote> quotes) {
        return identityVeto.veto(query, context, picked, quotes);
    }

    YahooQuote judgeMatch(String query, String context, List<YahooQuote> quotes) {
        return judgeMatcher.judge(query, context, quotes);
    }

    YahooQuote corpusMatch(String query, String context) {
        return corpusMatcher.corpus(query, context);
    }

    /** The wired guard tower — exposed package-private so the stage ORDER can be asserted in a test. */
    MatchTower matchTower() {
        return tower;
    }

    /** Per-subject scratch between phase 1 (collect) and phase 3 (assemble). */
    private record Pending(String query, String canonical, String ownTicker,
            List<RawNewsItem> news, List<String> relSyms,
            Map<String, List<RawNewsItem>> relNews, boolean rateLimited) {
        static Pending empty(String query) {
            return new Pending(query, query, null, List.of(), List.of(), Map.of(), false);
        }

        /** Search skipped/throttled by Yahoo — the subject is left unresolved, not tickerless. */
        static Pending rateLimited(String query) {
            return new Pending(query, query, null, List.of(), List.of(), Map.of(), true);
        }
    }

    /**
     * Outcome of resolving one subject. {@code ticker} is null for
     * theme/person subjects and for failed lookups; {@code snapshot} is null
     * whenever there is no ticker or Yahoo had no chart; {@code news} may be
     * empty.
     */
    public record ResolvedSubject(
            String query,
            String canonicalName,
            String ticker,
            MarketSnapshot snapshot,
            List<RawNewsItem> news,
            List<RelatedInstrument> related,
            // unresolved: Yahoo was rate-limiting (breaker open) so this subject was
            // NOT enriched — a marker, NOT a skip. It's still attributed + headlined
            // from the room evidence (Yahoo only enriches); it re-resolves to its
            // ticker on the next evidence, and the identity-merge folds any duplicate.
            boolean unresolved) {

        public boolean isInstrument() {
            return ticker != null && !ticker.isBlank();
        }

        public boolean hasNews() {
            return news != null && !news.isEmpty();
        }

        public boolean hasRelated() {
            return related != null && !related.isEmpty();
        }
    }

    /**
     * An instrument tagged on a subject's news (via Yahoo {@code relatedTickers})
     * with its live snapshot. Carried as evidence for a possible causal link;
     * never asserted as one.
     */
    public record RelatedInstrument(String ticker, MarketSnapshot snapshot, List<RawNewsItem> news) {
        public boolean hasNews() {
            return news != null && !news.isEmpty();
        }
    }
}
