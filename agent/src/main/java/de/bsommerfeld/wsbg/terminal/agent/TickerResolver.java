package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient.SearchResult;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooNewsItem;
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
            "inc", "incorporated", "corp", "corporation", "co", "company",
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

    private final YahooFinanceClient yahoo;

    public TickerResolver(YahooFinanceClient yahoo) {
        this.yahoo = yahoo;
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
                List<YahooNewsItem> news = sr.news() != null ? sr.news() : List.of();
                YahooQuote strong = strongMatch(query, sr.quotes());
                String ownTicker = strong == null ? null : strong.symbol();
                String canonical = strong == null ? query : strong.displayName();

                List<String> relSyms = new ArrayList<>();
                Map<String, List<YahooNewsItem>> relNews = new LinkedHashMap<>();
                if (maxRelated > 0) {
                    Set<String> seen = new HashSet<>();
                    if (ownTicker != null) seen.add(ownTicker.toUpperCase(Locale.ROOT));
                    collect:
                    for (YahooNewsItem ni : news) {
                        if (ni.relatedTickers() == null) continue;
                        for (String raw : ni.relatedTickers()) {
                            if (raw == null) continue;
                            String sym = raw.trim().toUpperCase(Locale.ROOT);
                            if (sym.isEmpty() || !seen.add(sym)) continue;
                            relSyms.add(sym);
                            List<YahooNewsItem> rn = yahoo.getNewsForSymbol(sym, RELATED_NEWS_COUNT);
                            relNews.put(sym, rn == null ? List.of() : rn);
                            if (relSyms.size() >= maxRelated) break collect;
                        }
                    }
                }
                if (ownTicker != null) symbols.add(ownTicker.toUpperCase(Locale.ROOT));
                symbols.addAll(relSyms);
                pending.add(new Pending(query, canonical, ownTicker, news, relSyms, relNews));
            } catch (Exception e) {
                LOG.debug("Subject resolution failed for '{}': {}", query, e.getMessage());
                pending.add(Pending.empty(query));
            }
        }

        // Phase 2 — ONE batched snapshot fetch for every ticker we touched.
        Map<String, MarketSnapshot> snaps = yahoo == null || symbols.isEmpty()
                ? Map.of() : yahoo.fetchCharts(new ArrayList<>(symbols));

        // Phase 3 — assemble, wiring the batched snapshots back in.
        List<ResolvedSubject> out = new ArrayList<>(pending.size());
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
            out.add(new ResolvedSubject(p.query, p.canonical, p.ownTicker, ownSnap, p.news, related));
        }
        return out;
    }

    /** Per-subject scratch between phase 1 (collect) and phase 3 (assemble). */
    private record Pending(String query, String canonical, String ownTicker,
            List<YahooNewsItem> news, List<String> relSyms,
            Map<String, List<YahooNewsItem>> relNews) {
        static Pending empty(String query) {
            return new Pending(query, query, null, List.of(), List.of(), Map.of());
        }
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
            boolean exactSymbol = q.symbol() != null && query.equalsIgnoreCase(q.symbol());
            boolean strong = bestSimilarity(queryTokens, q) >= STRONG_MATCH_THRESHOLD;
            if (strong && strictSingleToken && !hasOnlyQueryTokens(queryTokens, q)) {
                strong = false;
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
     * Preference among name matches — <b>lower is better</b>. No brittle
     * exchange whitelist; ranks on reliable, language-neutral signals:
     * <ul>
     *   <li>an <b>exact symbol</b> match wins outright (the subject WAS a ticker);</li>
     *   <li><b>numeric-prefixed symbols</b> ({@code 1MUV2.MI}) are foreign
     *       secondary listings on Borsa Italiana &amp; co. → heavy demotion;</li>
     *   <li><b>OTC / grey-market</b> exchanges (PNK, …) → demotion;</li>
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
        String type = q.quoteType() == null ? "" : q.quoteType().trim().toUpperCase(Locale.ROOT);
        if (!type.equals("EQUITY")) rank += 50;
        return rank;
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
            List<YahooNewsItem> news,
            List<RelatedInstrument> related) {

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
    public record RelatedInstrument(String ticker, MarketSnapshot snapshot, List<YahooNewsItem> news) {
        public boolean hasNews() {
            return news != null && !news.isEmpty();
        }
    }
}
