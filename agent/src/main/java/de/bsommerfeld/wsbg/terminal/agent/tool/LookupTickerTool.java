package de.bsommerfeld.wsbg.terminal.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves a free-text query (company name or ticker guess) to up to
 * three Yahoo Finance matches. Every symbol returned is recorded in
 * {@link ToolContext#recordValidatedTicker(String)} so subsequent
 * {@code publishHeadline} calls accept it — Yahoo is the single source
 * of truth for ticker symbols, and any ticker the model writes without
 * looking it up first will be silently dropped by publishHeadline.
 *
 * <p>
 * For German Reddit posts (XETRA listings), Yahoo typically returns the
 * {@code .DE} listing first. The agent picks whichever match best
 * matches the cluster's context.
 */
@Deprecated // legacy agent tool-loop — Yahoo matching now lives in TickerResolver; no longer wired
public final class LookupTickerTool implements Tool {

    private static final Logger LOG = LoggerFactory.getLogger(LookupTickerTool.class);
    private static final int MAX_RESULTS = 3;
    /**
     * Top-N news pre-fetched for the primary strong match. Three is
     * enough to give the headline real outside-of-Reddit context
     * (analyst targets, broker upgrades, M&A chatter) without bloating
     * the tool response — and Yahoo's cache makes repeat calls free
     * inside the 5-min TTL.
     */
    private static final int NEWS_PER_LOOKUP = 3;

    /**
     * Token-level Jaccard threshold above which a name is considered a
     * confident match for the query. 0.34 catches „Rambus" ↔ „Rambus Inc."
     * and „Outlook Therapeutics" ↔ „Outlook Therapeutics, Inc." while
     * filtering „Rheiner" ↔ „RheinErden AG" (token overlap 0 — different
     * words). Tuned empirically against the 2026-05-28 smoke test data.
     */
    private static final double STRONG_MATCH_THRESHOLD = 0.34;

    /**
     * Tokens that add no signal to a similarity check. Two flavours mixed
     * in here:
     *
     * <ol>
     *   <li>Corporate-form suffixes — {@code Inc.}, {@code AG}, {@code SE},
     *   {@code Aktiengesellschaft}. These are bureaucratic, not part of the
     *   actual brand.</li>
     *   <li>Industry-vertical generics — {@code Technology}, {@code Quantum},
     *   {@code Semiconductors}, {@code Pharmaceuticals}. „D-Wave" should
     *   match „D-Wave Quantum Inc." and „Micron" should match „Micron
     *   Technology Inc." — Yahoo extends the brand with the sector word, the
     *   query usually doesn't. We filter the sector word so the 1-token
     *   strict mode treats {„wave"} vs {„wave"} as equal.</li>
     * </ol>
     */
    private static final Set<String> STOP_TOKENS = Set.of(
            // corporate-form
            "inc", "incorporated", "corp", "corporation", "co", "company",
            "ag", "se", "kgaa", "gmbh", "ltd", "limited", "plc", "sa", "nv",
            "aktiengesellschaft", "kommanditgesellschaft", "gesellschaft",
            "the", "and",
            // industry generics added 2026-05-28 so brand-extended Yahoo
            // names („Micron Technology Inc", „D-Wave Quantum Inc",
            // „Peloton Interactive Inc") collapse back to the brand token
            "technology", "technologies", "tech",
            "quantum", "semiconductor", "semiconductors",
            "pharmaceutical", "pharmaceuticals", "pharma",
            "bioscience", "biosciences", "therapeutic",
            "industries", "industrial",
            "interactive", "communications",
            // Quote-denomination + instrument-shape suffixes. Yahoo
            // appends these to the brand for FX, crypto, ETFs and
            // commodities („Bitcoin USD", „MSCI World USD ETF"), but
            // the agent's query is just the brand. Filtering them lets
            // the single-token strict mode accept the canonical match
            // instead of flagging it WEAK.
            "usd", "eur", "gbp", "jpy", "chf", "cad", "aud", "cny",
            "hkd", "krw", "sek", "nok", "dkk", "pln", "brl", "mxn",
            "usdt", "usdc",
            "etf", "fund", "trust", "shares");

    @Override
    public String name() {
        return "lookupTicker";
    }

    @Override
    public ToolSpecification specification() {
        return ToolSpecification.builder()
                .name(name())
                .description("Resolves a company name or ticker guess to verified Yahoo Finance "
                        + "symbols. Returns up to 3 matches with symbol, name, exchange and "
                        + "sector. YOU MUST call this before publishing a headline with a "
                        + "tickerSymbol or subjects[].ticker — publishHeadline will drop any "
                        + "ticker that wasn't validated here. For German Reddit clusters prefer "
                        + "the .DE / XETRA listing; for US-focused posts the NASDAQ/NYSE listing.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query",
                                "Company name (Rheinmetall, Snowflake) or ticker guess (RHM, SNOW). "
                                        + "Free-text — fuzziness is OK, Yahoo's index is generous.")
                        .required("query")
                        .build())
                .build();
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) {
        String query = args.path("query").asText("").trim();
        if (query.isEmpty()) {
            return "Error: 'query' is required.";
        }

        List<YahooQuote> quotes = ctx.yahooFinance().searchQuotes(query, MAX_RESULTS);
        if (quotes.isEmpty()) {
            return "No Yahoo Finance match for '" + query + "'. Either the instrument is not "
                    + "listed on Yahoo (delisted, OTC obscurity, private) or your spelling is off. "
                    + "Publish the headline WITHOUT a tickerSymbol — the UI will render it without "
                    + "the ticker badge, which is still better than a wrong symbol.";
        }

        // Yahoo is fuzzy: query „Rheiner" returns „RheinErden AG" + „RM
        // Rheiner Management AG" — neither is Rheinmetall, but the
        // second shares the „rheiner" token and would slip through a
        // plain Jaccard test. To stop that, single-token queries
        // („Rheiner", „Gold") require the match to have NO extra
        // non-stopword tokens — i.e. „Apple Inc" passes ({apple} ==
        // {apple} after Inc-stop), „RM Rheiner Management AG" doesn't
        // ({rheiner} vs {rheiner, management}). Multi-token queries
        // („Outlook Therapeutics") keep the soft Jaccard rule because
        // they're already specific.
        Set<String> queryTokens = tokenize(query);
        boolean strictSingleToken = queryTokens.size() == 1;
        int strongCount = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("Yahoo Finance matches for '").append(query).append("' (").append(quotes.size())
                .append(" of top ").append(MAX_RESULTS).append("):\n");
        for (YahooQuote q : quotes) {
            double sim = bestSimilarity(queryTokens, q);
            boolean strong = sim >= STRONG_MATCH_THRESHOLD;
            // 1-token-query strict-mode: the match's name must be a
            // pure expansion of the query (same content tokens after
            // stop-word filtering). Anything that introduces an extra
            // brand word („Hospitality", „Management", „International")
            // is treated as a different company sharing only the keyword.
            if (strong && strictSingleToken && !hasOnlyQueryTokens(queryTokens, q)) {
                strong = false;
            }
            // Symbol-equals fallback: when the query IS the Yahoo
            // symbol (case-insensitive), Yahoo already gave the perfect
            // answer regardless of how the name tokenises. „APP" →
            // symbol APP (AppLovin) qualifies even though the name
            // „AppLovin Corporation" shares no token with „app".
            if (!strong && query.equalsIgnoreCase(q.symbol())) {
                strong = true;
            }
            // Only validate symbols whose company name actually matches the
            // query — weak matches return as info but are NOT added to the
            // validated set, so publishHeadline will drop them.
            if (strong) {
                ctx.recordValidatedTicker(q.symbol());
                strongCount++;
            }
            sb.append("  - ").append(q.symbol()).append("  ");
            sb.append(q.displayName());
            if (!q.exchangeDisplay().isEmpty()) {
                sb.append("  (").append(q.exchangeDisplay()).append(", ").append(q.quoteType()).append(")");
            }
            if (!q.sector().isEmpty()) {
                sb.append("  · ").append(q.sector());
                if (!q.industry().isEmpty()) {
                    sb.append(" / ").append(q.industry());
                }
            }
            if (!Double.isNaN(q.regularMarketPercentChange())) {
                sb.append(String.format("  · day %+.2f%%", q.regularMarketPercentChange()));
            }
            if (!strong) {
                sb.append("  ⚠ WEAK NAME MATCH — name doesn't share tokens with your query, "
                        + "Yahoo is fuzzy-guessing. NOT validated.");
            }
            sb.append('\n');
        }
        if (strongCount == 0) {
            sb.append("None of the returned names confidently matches '").append(query)
                    .append("' — Yahoo returned fuzzy guesses, not the instrument you asked for. "
                            + "Publish WITHOUT a tickerSymbol. Do NOT pick one of the weak matches.");
        } else {
            sb.append("Use one of the symbols above (without the ⚠ flag) verbatim as tickerSymbol "
                    + "or subjects[].ticker. If none of the confident matches is the instrument the "
                    + "cluster is actually about, publish without a ticker — do NOT invent one.");
            // Auto-fetch Yahoo news for the primary strong match and
            // surface it INLINE so the agent reads it before drafting the
            // headline. Replaces the old post-publish auto-fetch path,
            // which fed news into the next-tick getCluster instead of the
            // current headline. Cached per-symbol; only the first lookup
            // pays the network cost.
            YahooQuote primary = quotes.stream()
                    .filter(q -> bestSimilarity(queryTokens, q) >= STRONG_MATCH_THRESHOLD)
                    .findFirst().orElse(null);
            if (primary != null) {
                appendSnapshotBlock(sb, ctx, primary.symbol());
                appendNewsBlock(sb, ctx, primary.symbol());
            }
        }
        LOG.info("[AGENT] lookupTicker '{}' → {} matches ({} strong)",
                query, quotes.size(), strongCount);
        return sb.toString();
    }

    /**
     * Appends a live market snapshot under the lookup result — the hard
     * numbers behind the highlight rubric. Surfaces the exact day move
     * (Yahoo's, not the agent's guess), intraday range, 52-week position,
     * and volume so the agent picks the right highlight tier and
     * priceMovePercent instead of eyeballing it off Reddit. Silently
     * omitted when Yahoo returns no chart (delisted / pre-IPO / OTC).
     */
    private static void appendSnapshotBlock(StringBuilder sb, ToolContext ctx, String symbol) {
        if (ctx.yahooFinance() == null) return;
        try {
            MarketSnapshot s = ctx.yahooFinance().fetchChart(symbol).orElse(null);
            if (s == null || !s.hasPrice()) return;
            String ccy = s.currency() == null || s.currency().isEmpty() ? "" : " " + s.currency();
            sb.append("\nLive market data for ").append(symbol).append(":\n");
            sb.append(String.format(Locale.ROOT, "  price %.2f%s", s.price(), ccy));
            if (Double.isFinite(s.dayChangePercent())) {
                sb.append(String.format(Locale.ROOT, "  ·  day %+.2f%%", s.dayChangePercent()));
            }
            sb.append('\n');
            if (Double.isFinite(s.dayLow()) && Double.isFinite(s.dayHigh())) {
                sb.append(String.format(Locale.ROOT, "  day range %.2f – %.2f%n", s.dayLow(), s.dayHigh()));
            }
            if (Double.isFinite(s.fiftyTwoWeekLow()) && Double.isFinite(s.fiftyTwoWeekHigh())) {
                sb.append(String.format(Locale.ROOT, "  52w range %.2f – %.2f", s.fiftyTwoWeekLow(), s.fiftyTwoWeekHigh()));
                // Position within the 52w band — "near 52w high" is a
                // breakout tell the agent should weigh.
                double span = s.fiftyTwoWeekHigh() - s.fiftyTwoWeekLow();
                if (span > 0 && s.hasPrice()) {
                    double pos = (s.price() - s.fiftyTwoWeekLow()) / span * 100.0;
                    sb.append(String.format(Locale.ROOT, "  (%.0f%% of band)", pos));
                }
                sb.append('\n');
            }
            if (s.volume() > 0) {
                sb.append("  volume ").append(formatVolume(s.volume())).append('\n');
            }
            sb.append("  ↳ use this day% for priceMovePercent and to size the highlight tier; "
                    + "do NOT override it with a Reddit-claimed number unless the post is clearly "
                    + "more recent than Yahoo.\n");
        } catch (Exception e) {
            LOG.debug("Yahoo snapshot fetch failed for {}: {}", symbol, e.getMessage());
        }
    }

    /** Compact volume formatter: 141557394 → "141.6M". */
    private static String formatVolume(long v) {
        if (v >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1fB", v / 1_000_000_000.0);
        if (v >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", v / 1_000_000.0);
        if (v >= 1_000L) return String.format(Locale.ROOT, "%.1fK", v / 1_000.0);
        return Long.toString(v);
    }

    /**
     * Appends a compact news block under the lookup result. Silently
     * omits the block when Yahoo has nothing on the symbol — common for
     * German pennystocks and obscure OTC names; that absence is itself
     * usable information (the headline shouldn't claim a Wall-Street
     * catalyst).
     */
    private static void appendNewsBlock(StringBuilder sb, ToolContext ctx, String symbol) {
        if (ctx.yahooFinance() == null) return;
        try {
            List<RawNewsItem> news = ctx.yahooFinance().getNewsForSymbol(symbol, NEWS_PER_LOOKUP);
            if (news.isEmpty()) return;
            sb.append("\nRecent Yahoo news for ").append(symbol).append(":\n");
            Instant now = Instant.now();
            for (RawNewsItem item : news) {
                sb.append("  - ");
                if (item.publishedAt() != null) {
                    long mins = Duration.between(item.publishedAt(), now).toMinutes();
                    if (mins < 60) sb.append(mins).append("m ago  ");
                    else if (mins < 24 * 60) sb.append(mins / 60).append("h ago  ");
                    else sb.append(mins / (24 * 60)).append("d ago  ");
                }
                sb.append(item.title());
                if (item.publisher() != null && !item.publisher().isEmpty()) {
                    sb.append("  · ").append(item.publisher());
                }
                sb.append('\n');
            }
        } catch (Exception e) {
            LOG.debug("Yahoo news fetch failed for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Normalises a string to a token set: lower-case, split on
     * non-alphanumeric, drop stop-tokens and tokens shorter than 3 chars.
     * „RHEINMETALL AG    I" → {„rheinmetall"}; „Rambus Inc." → {„rambus"};
     * „RheinErden AG" → {„rheinerden"}.
     */
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

    /**
     * Jaccard similarity of the query tokens against the best of the
     * quote's shortname/longname token sets. Picking the better of the
     * two avoids penalising Yahoo when only one name field is populated.
     */
    private static double bestSimilarity(Set<String> queryTokens, YahooQuote q) {
        double s1 = jaccard(queryTokens, tokenize(q.shortName()));
        double s2 = jaccard(queryTokens, tokenize(q.longName()));
        return Math.max(s1, s2);
    }

    /**
     * Strict 1-token-query check: at least one of the quote's name
     * fields must contain exactly the query's content tokens, no extras.
     * „Apple" matches „Apple Inc." (stop-word Inc filtered, tokens equal)
     * but not „Apple Hospitality REIT" or „RM Rheiner Management AG".
     */
    private static boolean hasOnlyQueryTokens(Set<String> queryTokens, YahooQuote q) {
        return tokenize(q.shortName()).equals(queryTokens)
                || tokenize(q.longName()).equals(queryTokens);
    }

    /**
     * Prefix fallback for 1-token queries the tokenizer collapses into
     * a too-narrow set. „APP" → tokenizes to {„app"}; the Yahoo match
     * „AppLovin Corporation" tokenizes to {„applovin", „corporation"},
     * so token-equality fails. But the raw match name starts with the
     * raw query („AppLovin" begins with „APP") — that's a confident
     * prefix relationship, treat as strong.
     */

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        if (inter.isEmpty()) return 0.0;
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }
}
