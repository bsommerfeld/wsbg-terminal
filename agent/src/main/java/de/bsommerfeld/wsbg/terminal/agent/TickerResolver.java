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
import java.util.List;
import java.util.Locale;
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
    private static final int MAX_QUOTES = 3;
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

    /**
     * Resolves a single subject name. Never throws — any Yahoo failure
     * degrades to a {@code nothing} result so the headline still publishes.
     */
    public ResolvedSubject resolve(String subjectName) {
        String query = subjectName == null ? "" : subjectName.trim();
        if (query.isEmpty() || yahoo == null) {
            return new ResolvedSubject(query, query, null, null, List.of(), List.of());
        }
        try {
            SearchResult sr = yahoo.search(query, MAX_QUOTES, NEWS_COUNT);
            List<YahooNewsItem> news = sr.news() != null ? sr.news() : List.of();
            YahooQuote strong = strongMatch(query, sr.quotes());
            String ownTicker = strong == null ? null : strong.symbol();
            List<RelatedInstrument> related = resolveRelated(news, ownTicker);
            if (strong == null) {
                // Theme/person (news-only) — still carries the instruments its
                // news is about, so the desk can read a causal chain.
                return new ResolvedSubject(query, query, null, null, news, related);
            }
            MarketSnapshot snapshot = yahoo.fetchChart(strong.symbol()).orElse(null);
            LOG.info("[RESOLVE] '{}' → {} ({}{})", query, strong.symbol(),
                    snapshot != null && snapshot.hasPrice() ? "with market data" : "no chart",
                    related.isEmpty() ? "" : ", +" + related.size() + " related");
            return new ResolvedSubject(query, strong.displayName(), strong.symbol(), snapshot, news, related);
        } catch (Exception e) {
            LOG.debug("Subject resolution failed for '{}': {}", query, e.getMessage());
            return new ResolvedSubject(query, query, null, null, List.of(), List.of());
        }
    }

    /**
     * Second-hop: pull a live snapshot for the distinct {@code relatedTickers}
     * Yahoo tagged on this subject's news (minus the subject's own ticker),
     * capped at {@link #MAX_RELATED}. These are surfaced as raw evidence — the
     * desk decides whether they connect, we never assert the link ourselves.
     */
    private List<RelatedInstrument> resolveRelated(List<YahooNewsItem> news, String ownTicker) {
        if (news.isEmpty()) return List.of();
        Set<String> seen = new HashSet<>();
        if (ownTicker != null) seen.add(ownTicker.toUpperCase(Locale.ROOT));
        List<RelatedInstrument> out = new ArrayList<>();
        for (YahooNewsItem n : news) {
            if (n.relatedTickers() == null) continue;
            for (String raw : n.relatedTickers()) {
                if (raw == null) continue;
                String sym = raw.trim().toUpperCase(Locale.ROOT);
                if (sym.isEmpty() || !seen.add(sym)) continue;
                MarketSnapshot snap = yahoo.fetchChart(sym).orElse(null);
                if (snap == null || !snap.hasPrice()) continue;
                // Attach the instrument's own news too, so every named ticker —
                // primary subject or second-hop — carries a "why", not just a move.
                List<YahooNewsItem> relNews = yahoo.getNewsForSymbol(sym, RELATED_NEWS_COUNT);
                out.add(new RelatedInstrument(sym, snap, relNews == null ? List.of() : relNews));
                if (out.size() >= MAX_RELATED) return out;
            }
        }
        return out;
    }

    /** Returns the first quote that confidently matches the query, or null. */
    private static YahooQuote strongMatch(String query, List<YahooQuote> quotes) {
        if (quotes == null || quotes.isEmpty()) return null;
        Set<String> queryTokens = tokenize(query);
        boolean strictSingleToken = queryTokens.size() == 1;
        for (YahooQuote q : quotes) {
            double sim = bestSimilarity(queryTokens, q);
            boolean strong = sim >= STRONG_MATCH_THRESHOLD;
            if (strong && strictSingleToken && !hasOnlyQueryTokens(queryTokens, q)) {
                strong = false;
            }
            if (!strong && query.equalsIgnoreCase(q.symbol())) {
                strong = true;
            }
            if (strong) return q;
        }
        return null;
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
