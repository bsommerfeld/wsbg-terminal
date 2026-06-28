package de.bsommerfeld.wsbg.terminal.agent;
import de.bsommerfeld.wsbg.terminal.embedding.EmbeddingService;

import de.bsommerfeld.wsbg.terminal.aggregator.NewsAggregator;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.price.PriceRef;
import de.bsommerfeld.wsbg.terminal.core.price.PriceSource;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient.SearchResult;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, code-side resolution of an editorial <em>subject</em> against
 * Yahoo Finance — no LLM, no tool loop. Replaces the model-invoked
 * {@code lookupTicker} tool: the subject-extraction stage hands a subject name
 * here, and the resolver decides what it is and what data backs it.
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
 * <p>The matching heuristics (Jaccard token overlap, single-token strict mode,
 * stop-token filtering, symbol-equals fallback) are lifted verbatim from the
 * old {@code LookupTickerTool} so we keep the empirically-tuned behaviour that
 * rejects "Rheiner → RM Rheiner Management AG" while accepting "Micron →
 * Micron Technology Inc.".
 */
public final class TickerResolver {

    private static final Logger LOG = LoggerFactory.getLogger(TickerResolver.class);
    /**
     * How many quote candidates to pull from the (single) search response.
     * Larger than the old 3 so the exchange-preference in {@link #strongMatch}
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
    private static final double STRONG_MATCH_THRESHOLD = 0.34;
    /**
     * Yahoo search-relevance score above which a single-token query is trusted to
     * match a name that carries <em>extra</em> non-stop tokens (e.g. "Amazon" vs
     * "Amazon.com, Inc.", "Meta" vs "Meta Platforms, Inc."). Instead of growing a
     * stop-word list forever, we lean on Yahoo's own confidence: a well-known
     * match scores very high (megacaps reach 6–7 figures), an obscure fuzzy hit
     * ("Rheiner" → some micro-cap) scores low. The score is popularity-weighted,
     * which is exactly why it works here — the megacaps we keep missing are the
     * high-scoring ones; legitimate low-score names fall through to the embedding
     * fallback (tier 2) rather than being matched on a thin token overlap.
     * Deliberately conservative; tunable against live data.
     */
    private static final double MIN_CONFIDENT_SCORE = 100_000.0;

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

    private static final Set<String> STOP_TOKENS = Set.of(
            // "com" is the dotcom suffix glued onto a name ("Amazon.com",
            // "Salesforce.com", "Booking.com") — generic, like inc/corp, never a
            // distinguishing token. Without it a single-token query ("Amazon")
            // fails the strict match against "Amazon.com, Inc." and loses its ticker.
            "inc", "incorporated", "corp", "corporation", "co", "com", "company",
            "ag", "se", "kgaa", "gmbh", "ltd", "limited", "plc", "sa", "nv",
            "aktiengesellschaft", "kommanditgesellschaft", "gesellschaft",
            "the", "and",
            "technology", "technologies", "tech",
            "quantum", "semiconductor", "semiconductors",
            "pharmaceutical", "pharmaceuticals", "pharma",
            "bioscience", "biosciences", "therapeutic",
            "industries", "industrial",
            "interactive", "communications",
            "usd", "eur", "gbp", "jpy", "chf", "cad", "aud", "cny",
            "hkd", "krw", "sek", "nok", "dkk", "pln", "brl", "mxn",
            "usdt", "usdc",
            "etf", "fund", "trust", "shares");

    /**
     * Tier 2 acceptance bar: when token/score matching ({@link #strongMatch}) can't
     * confidently pick a candidate, the embedding ranker may — but only if the best
     * candidate name is at least this cosine-similar to the query. Doubles as a guard
     * (a fuzzy query with no semantically-close candidate stays unresolved). Raised
     * from 0.55 because the loose fallback was turning pure THEMES into bogus tickers
     * (Biotech→an index, Semiconductor→an ETF, Drones→some foreign line). Paired with
     * the EQUITY-only filter in {@link #embedMatch}, the fuzzy fallback now only ever
     * promotes a real, confidently-named stock. (The subject still keeps its Yahoo
     * NEWS regardless — that's attached independent of the ticker.)
     */
    private static final double EMBED_MATCH_THRESHOLD = 0.70;

    private final YahooFinanceClient yahoo;
    private final EmbeddingService embeddings; // Tier 2; null disables it

    /**
     * The live price chain (L&amp;S → Tradegate → NASDAQ → Yahoo, EUR). Optional:
     * injected only in production (AppModule); null in tests and the lab harness,
     * where snapshots fall back to the Yahoo batch below. Yahoo stays the SEARCH +
     * NEWS source regardless — the chain only supplies the price snapshot.
     */
    private PriceSource priceSource;

    /**
     * Installs the live price chain. Forwarded by {@code EditorialAgent} (which
     * Guice manages and which builds this resolver by hand); null in tests / the
     * lab harness, where snapshots fall back to the Yahoo batch.
     */
    void setPriceSource(PriceSource priceSource) {
        this.priceSource = priceSource;
    }

    /**
     * The multi-source news pool (Yahoo + NASDAQ + …). Forwarded by
     * {@code EditorialAgent}; null in tests / the lab harness, where news comes
     * straight from the Yahoo search result.
     */
    private NewsAggregator newsAggregator;

    void setNewsAggregator(NewsAggregator newsAggregator) {
        this.newsAggregator = newsAggregator;
    }

    public TickerResolver(YahooFinanceClient yahoo) {
        this(yahoo, null);
    }

    public TickerResolver(YahooFinanceClient yahoo, EmbeddingService embeddings) {
        this.yahoo = yahoo;
        this.embeddings = embeddings;
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

    /**
     * Resolves many subjects at once with a per-subject related allowance, and
     * — crucially — fetches every needed price snapshot (each subject's own
     * ticker + all related tickers) in a single batched call
     * ({@link YahooFinanceClient#fetchCharts}, spark endpoint). That collapses
     * what used to be ~one chart request per ticker into 1–2 requests, which is
     * the big Yahoo-call saving when a cluster names many subjects. News (the
     * per-name search and the per-related-symbol news) still goes one request at
     * a time — Yahoo has no batch news endpoint — but those are cached + the
     * related fan-out is bounded by {@code relatedAlloc}.
     *
     * @param names        subject names (slang already normalised)
     * @param relatedAlloc per-subject related cap, index-aligned with {@code names}
     */
    public List<ResolvedSubject> resolveAll(List<String> names, int[] relatedAlloc) {
        int n = names == null ? 0 : names.size();
        List<Pending> pending = new ArrayList<>(n);
        LinkedHashSet<String> symbols = new LinkedHashSet<>();

        // Phase 1 — search each subject, pick its ticker, gather related tickers
        // (+ their news). No snapshots yet; just collect the symbols we'll need.
        for (int i = 0; i < n; i++) {
            String query = names.get(i) == null ? "" : names.get(i).trim();
            int maxRelated = relatedAlloc != null && i < relatedAlloc.length ? Math.max(0, relatedAlloc[i]) : 0;
            if (query.isEmpty() || yahoo == null) {
                pending.add(Pending.empty(query));
                continue;
            }
            try {
                SearchResult sr = yahoo.search(query, MAX_QUOTES, NEWS_COUNT);
                if (sr.rateLimited()) {
                    // Yahoo is throttling — leave the subject unresolved (skip), don't
                    // guess a tickerless unit, and don't fan out into more calls.
                    pending.add(Pending.rateLimited(query));
                    continue;
                }
                YahooQuote strong = strongMatch(query, sr.quotes());
                if (strong == null) {
                    strong = embedMatch(query, sr.quotes()); // Tier 2: semantic fallback
                }
                String ownTicker = strong == null ? null : strong.symbol();
                String canonical = strong == null ? query : strong.displayName();

                // News: triangulated across all sources by the resolved ticker (Yahoo +
                // NASDAQ + …) so the wire doesn't depend on Yahoo alone. A ticker-less
                // theme keeps Yahoo's query-news (the only handle without a symbol); the
                // lab/tests (no aggregator) also keep the search news.
                List<RawNewsItem> news = (newsAggregator != null && ownTicker != null)
                        ? newsAggregator.newsFor(ownTicker, NEWS_COUNT)
                        : (sr.news() != null ? sr.news() : List.of());

                List<String> relSyms = new ArrayList<>();
                Map<String, List<RawNewsItem>> relNews = new LinkedHashMap<>();
                if (maxRelated > 0) {
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
                            relNews.put(sym, rn == null ? List.of() : rn);
                            if (relSyms.size() >= maxRelated) break collect;
                        }
                    }
                }
                if (ownTicker != null) symbols.add(ownTicker.toUpperCase(Locale.ROOT));
                symbols.addAll(relSyms);
                pending.add(new Pending(query, canonical, ownTicker, news, relSyms, relNews, false));
            } catch (Exception e) {
                LOG.debug("Subject resolution failed for '{}': {}", query, e.getMessage());
                pending.add(Pending.empty(query));
            }
        }

        // Phase 2 — snapshots. With the live price chain each subject's OWN ticker
        // goes through L&S→Tradegate→NASDAQ→Yahoo (EUR, looked up by name); related
        // tickers (second-hop context, ticker-only) stay on the Yahoo batch. Without
        // a chain (tests / lab harness) everything uses the Yahoo batch as before.
        Map<String, MarketSnapshot> snaps = new LinkedHashMap<>();
        if (priceSource != null) {
            for (Pending p : pending) {
                if (p.ownTicker == null) continue;
                String key = p.ownTicker.toUpperCase(Locale.ROOT);
                if (snaps.containsKey(key)) continue;
                priceSource.snapshot(new PriceRef(p.canonical, p.ownTicker))
                        .ifPresent(s -> snaps.put(key, s));
            }
            // Own tickers are fully the chain's job — it already includes Yahoo as a
            // fallback (and in the overnight gap returns the last close, marked stale,
            // rather than nothing). So the Yahoo batch covers ONLY related/context
            // tickers; never re-fetch an own ticker the chain already handled.
            LinkedHashSet<String> ownTickers = new LinkedHashSet<>();
            for (Pending p : pending) {
                if (p.ownTicker != null) ownTickers.add(p.ownTicker.toUpperCase(Locale.ROOT));
            }
            LinkedHashSet<String> rest = new LinkedHashSet<>(symbols);
            rest.removeAll(ownTickers);
            rest.removeAll(snaps.keySet());
            if (yahoo != null && !rest.isEmpty()) snaps.putAll(yahoo.fetchCharts(new ArrayList<>(rest)));
        } else if (yahoo != null && !symbols.isEmpty()) {
            snaps.putAll(yahoo.fetchCharts(new ArrayList<>(symbols)));
        }

        // Phase 3 — assemble, wiring the batched snapshots back in.
        List<ResolvedSubject> out = new ArrayList<>(pending.size());
        int rateLimited = 0;
        for (Pending p : pending) {
            MarketSnapshot ownSnap = p.ownTicker == null ? null
                    : snaps.get(p.ownTicker.toUpperCase(Locale.ROOT));
            List<RelatedInstrument> related = new ArrayList<>();
            for (String sym : p.relSyms) {
                MarketSnapshot s = snaps.get(sym);
                if (s == null || !s.hasPrice()) continue;
                related.add(new RelatedInstrument(sym, s, p.relNews.getOrDefault(sym, List.of())));
            }
            if (p.ownTicker != null) {
                LOG.info("[RESOLVE] '{}' → {} ({}{})", p.query, p.ownTicker,
                        ownSnap != null && ownSnap.hasPrice() ? "with market data" : "no chart",
                        related.isEmpty() ? "" : ", +" + related.size() + " related");
            }
            if (p.rateLimited) rateLimited++;
            out.add(new ResolvedSubject(p.query, p.canonical, p.ownTicker, ownSnap, p.news, related,
                    p.rateLimited));
        }
        if (rateLimited > 0) {
            LOG.warn("[RESOLVE] Yahoo rate-limited — {} subject(s) un-enriched this pass "
                    + "(still published from room evidence; re-enriched on next evidence)", rateLimited);
        }
        return out;
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
     * Tier 2: when {@link #strongMatch} found nothing (token/score didn't decide),
     * pick the candidate whose NAME is most semantically similar to the query via
     * the embedding ranker — but only above {@link #EMBED_MATCH_THRESHOLD}, so a
     * fuzzy query with no close candidate stays unresolved (the guard). No-op when
     * no embedder is wired. Source-agnostic: the candidates are just names, so the
     * same path will rank a local symbol-corpus once that exists. Package-private
     * for testing.
     */
    YahooQuote embedMatch(String query, List<YahooQuote> quotes) {
        if (embeddings == null || quotes == null || quotes.isEmpty()) return null;
        List<String> names = new ArrayList<>(quotes.size());
        for (YahooQuote q : quotes) names.add(q.displayName());
        int i = embeddings.bestMatch(query, names, EMBED_MATCH_THRESHOLD);
        if (i < 0) return null;
        YahooQuote best = quotes.get(i);
        // EQUITY-only gate for the fuzzy fallback: a theme/topic ("Biotech",
        // "Drones", "Semiconductor") that has no token/score match must NOT be
        // promoted to an ETF/index/currency/crypto ticker with a live price the
        // room never meant. Only a real, confidently-named stock survives Tier 2;
        // anything else stays tickerless (its Yahoo NEWS is kept regardless,
        // attached independent of the ticker in resolveAll).
        String type = best.quoteType() == null ? "" : best.quoteType().trim().toUpperCase(Locale.ROOT);
        if (!type.equals("EQUITY")) return null;
        return best;
    }

    /**
     * Returns the <em>preferred</em> quote among those that confidently match
     * the query, or null. Unchanged match test (token overlap + single-token
     * strict mode + exact-symbol), but instead of taking the FIRST match we
     * collect all matches and pick the primary/most-relevant listing via
     * {@link #preferenceRank} — so a foreign secondary line ({@code 1MUV2.MI})
     * no longer beats the home listing ({@code MUV2.DE}) just by appearing first.
     * Package-private for unit testing.
     */
    static YahooQuote strongMatch(String query, List<YahooQuote> quotes) {
        if (quotes == null || quotes.isEmpty()) return null;
        Set<String> queryTokens = tokenize(query);
        boolean strictSingleToken = queryTokens.size() == 1;

        YahooQuote best = null;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < quotes.size(); i++) {
            YahooQuote q = quotes.get(i);
            // A generic theme/macro acronym (AI, KI, EV, FED, …) is almost always the
            // theme, not the same-letter ticker (e.g. "AI" the topic, not C3.ai). Don't
            // let it exact-match a ticker — leave it unresolved (a clear ticker-less line).
            boolean exactSymbol = q.symbol() != null && query.equalsIgnoreCase(q.symbol())
                    && !THEME_WORDS.contains(query.trim().toUpperCase(Locale.ROOT));
            boolean strong = bestSimilarity(queryTokens, q) >= STRONG_MATCH_THRESHOLD;
            if (strong && strictSingleToken && !hasOnlyQueryTokens(queryTokens, q)) {
                // The name carries extra, non-stop tokens beyond the single query
                // token (e.g. "Amazon" vs "Amazon.com, Inc."). Rather than police
                // this with an ever-growing stop-word list, defer to Yahoo's own
                // relevance score: a confident megacap match clears the bar, an
                // obscure fuzzy hit does not. (Tier 2, embedding, is the fallback
                // for the legitimate low-score names this still rejects.)
                strong = q.score() >= MIN_CONFIDENT_SCORE;
            }
            if (exactSymbol) strong = true;
            if (!strong) continue;

            int rank = preferenceRank(q, i, exactSymbol);
            if (rank < bestRank) {
                bestRank = rank;
                best = q;
            }
        }
        return best;
    }

    /** Yahoo exchange codes for OTC / grey-market venues — deprioritised. */
    private static final Set<String> OTC_EXCHANGES =
            Set.of("PNK", "OTC", "OQB", "OQX", "OTCBB", "OTCQ");

    /**
     * Generic theme/macro/slang acronyms the room uses as topics, not tickers —
     * even though each happens to BE a listed symbol. Gated from the exact-symbol
     * fast-path so "AI" stays the AI theme (not C3.ai), "IT" stays IT (not Gartner).
     */
    private static final Set<String> THEME_WORDS = Set.of(
            "AI", "KI", "EV", "IT", "FED", "ECB", "EZB", "USA", "USD", "EUR", "GBP",
            "CPI", "GDP", "BIP", "PCE", "ETF", "IPO", "ATH", "FOMO", "DD", "YOLO",
            "CEO", "CFO", "KGV", "QE", "BTC");

    // ---- Venue preference (the "primary → Frankfurt → never obscure" directive) ----
    // Symbol-suffix is the most reliable venue signal Yahoo gives ("TTWO.WA" =
    // Warsaw, "RHM.DE" = Xetra); the US primary listing simply has no suffix. We
    // rank a listing's venue into three tiers so a US/home primary beats a thin
    // foreign secondary (Take-Two → TTWO, not TTWO.WA) and, when no primary is in
    // the candidate set, Frankfurt (a real, EUR-quoted, accessible venue for the
    // German user base) beats any other obscure foreign line.

    /** Suffixes of primary/home venues — kept at tier 0 (no venue malus). */
    private static final Set<String> PRIMARY_SUFFIXES = Set.of(
            "DE",                       // Xetra (German home)
            "L",                        // London Stock Exchange
            "PA", "AS", "BR", "LS",     // Euronext Paris/Amsterdam/Brussels/Lisbon
            "MC",                       // Madrid
            "MI",                       // Borsa Italiana (primary; numeric secondaries already demoted)
            "SW",                       // SIX Swiss
            "VI",                       // Vienna
            "CO", "ST", "HE", "OL",     // Copenhagen/Stockholm/Helsinki/Oslo
            "TO", "V",                  // Toronto / TSX-V
            "HK", "T", "AX", "NZ");     // Hong Kong / Tokyo / Australia / New Zealand

    /** Suffixes of German regional venues — Frankfurt &amp; co., the fallback tier. */
    private static final Set<String> FRANKFURT_SUFFIXES = Set.of(
            "F",                                    // Frankfurt
            "BE", "MU", "SG", "HM", "DU", "HA");    // Berlin/Munich/Stuttgart/Hamburg/Düsseldorf/Hannover

    private static final int VENUE_FRANKFURT = 100; // German fallback venue
    private static final int VENUE_OBSCURE = 400;   // unclassified foreign secondary (e.g. .WA Warsaw)

    /**
     * Preference among name matches — <b>lower is better</b>. Ranks on reliable,
     * mostly language-neutral signals (additive, so they compose):
     * <ul>
     *   <li>an <b>exact symbol</b> match wins outright (the subject WAS a ticker);</li>
     *   <li><b>numeric-prefixed symbols</b> ({@code 1MUV2.MI}) are foreign
     *       secondary listings on Borsa Italiana &amp; co. → heavy demotion;</li>
     *   <li><b>OTC / grey-market</b> exchanges (PNK, …) → demotion;</li>
     *   <li><b>venue tier</b> (the directive): a primary/home listing (US no-suffix,
     *       {@code .DE}, {@code .L}, …) is preferred; Frankfurt ({@code .F}) is the
     *       fallback; any other obscure foreign line ({@code .WA}, …) is demoted —
     *       so Take-Two resolves to {@code TTWO} (Nasdaq), not {@code TTWO.WA};</li>
     *   <li>real <b>EQUITY</b> mildly preferred over a same-name ETF/index (soft,
     *       so an ETF still wins when it's the only/best match);</li>
     *   <li>ties fall back to <b>Yahoo's own order</b> (≈ relevance).</li>
     * </ul>
     */
    private static int preferenceRank(YahooQuote q, int yahooIndex, boolean exactSymbol) {
        if (exactSymbol) return -1000 + yahooIndex;
        int rank = yahooIndex; // Yahoo order is the baseline (≈ relevance)
        String sym = q.symbol() == null ? "" : q.symbol();
        if (!sym.isEmpty() && Character.isDigit(sym.charAt(0))) rank += 1000;
        String exch = q.exchange() == null ? "" : q.exchange().trim().toUpperCase(Locale.ROOT);
        if (OTC_EXCHANGES.contains(exch)) rank += 500;
        rank += venueMalus(sym);
        String type = q.quoteType() == null ? "" : q.quoteType().trim().toUpperCase(Locale.ROOT);
        if (!type.equals("EQUITY")) rank += 50;
        // Prefer the cash index / spot over a derivative future: "NASDAQ 100" → ^NDX, not NQ=F.
        if (type.equals("FUTURE") || sym.toUpperCase(Locale.ROOT).endsWith("=F")) rank += 200;
        return rank;
    }

    /**
     * Venue tier from a symbol's exchange suffix: 0 for a primary/home listing
     * (incl. US, which has no suffix), {@link #VENUE_FRANKFURT} for a German
     * regional venue (Frankfurt &amp; co.), {@link #VENUE_OBSCURE} for an
     * unrecognised foreign suffix (treated as a thin secondary line).
     */
    private static int venueMalus(String symbol) {
        int dot = symbol.lastIndexOf('.');
        if (dot < 0 || dot == symbol.length() - 1) return 0; // no suffix → US/home primary
        String suffix = symbol.substring(dot + 1).toUpperCase(Locale.ROOT);
        if (PRIMARY_SUFFIXES.contains(suffix)) return 0;
        if (FRANKFURT_SUFFIXES.contains(suffix)) return VENUE_FRANKFURT;
        return VENUE_OBSCURE;
    }

    private static Set<String> tokenize(String s) {
        if (s == null) return Set.of();
        String norm = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ");
        Set<String> out = new HashSet<>();
        for (String t : Arrays.asList(norm.trim().split("\\s+"))) {
            if (t.length() < 3) continue;
            if (STOP_TOKENS.contains(t)) continue;
            out.add(t);
        }
        return out;
    }

    private static double bestSimilarity(Set<String> queryTokens, YahooQuote q) {
        return Math.max(jaccard(queryTokens, tokenize(q.shortName())),
                jaccard(queryTokens, tokenize(q.longName())));
    }

    private static boolean hasOnlyQueryTokens(Set<String> queryTokens, YahooQuote q) {
        return tokenize(q.shortName()).equals(queryTokens)
                || tokenize(q.longName()).equals(queryTokens);
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        if (inter.isEmpty()) return 0.0;
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
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
