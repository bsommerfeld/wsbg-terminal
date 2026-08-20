package de.bsommerfeld.wsbg.terminal.web.impl.sources.tradingview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * TradingView's news mediator - the ARTICLE leg of this package, beside the
 * social {@link TradingViewMindsSource} (all endpoints live-probed 2026-08-02).
 * Three surfaces, three jobs:
 *
 * <ul>
 *   <li><b>General German wire</b> ({@code news-flow/v2/news?filter=lang:de&client=screener})
 *       - 200 items in ONE call, and the provider mix is the point: dpa-AFX 99,
 *       Reuters 68, FXStreet, AWP, pressetext, EQS. That is a German-language
 *       agency stream without a single RSS feed, instrument-free - the general
 *       door into this source.</li>
 *   <li><b>Per-symbol articles</b> ({@code public/view/v1/symbol?filter=symbol:<EXCHANGE:SYM>})
 *       - 25 headlines for the addressed instrument with {@code provider},
 *       {@code published}, {@code urgency} and {@code paywall}. Same item shape
 *       as the wire, so one parser serves both.</li>
 *   <li><b>Story body</b> ({@code news-headlines.tradingview.com/v2/story?id=…})
 *       - the full text as an {@code astDescription} tree plus
 *       {@code relatedSymbols} and {@code tags}.</li>
 * </ul>
 *
 * <p><b>THE PAYWALL RULE - do not "fix" this.</b> {@code /v2/story} returns the
 * COMPLETE body even for items flagged {@code paywall: true} (verified on a
 * Reuters item). Those items are third-party licensed agency copy (dpa-AFX,
 * Reuters, Dow Jones) that TradingView itself keeps behind a barrier. This
 * house's principle is "re-express, never reproduce" - and here that is not a
 * matter of style but the condition under which touching this endpoint is
 * defensible at all. Therefore: for a gated item the body is NEVER fetched
 * ({@link #fullText} returns empty without a request); headline, provider and
 * timestamp - the parts that carry the signal - are kept. Gated items are
 * marked on the {@code publisher} ({@link #gatedPublisher}) in the CNBC Pro
 * manner, so every downstream reader can see the barrier without knowing this
 * class. An item counts as gated when {@code paywall} is true OR
 * {@code permission} says {@code headline}/{@code preview} - both mean
 * TradingView is licensed to show only a stub.
 *
 * <p><b>Header gotcha:</b> see {@link TradingViewApi} - without the
 * Origin/Referer pair both hosts answer 403.
 *
 * <p><b>Dead surfaces, verified 2026-08-02, deliberately not wired:</b>
 * {@code public/story/v1|v2/<id>} (404), {@code news-headlines/v3/headlines}
 * (405), {@code /api/v1/symbols/<sym>/ideas/} (404).
 *
 * <p><b>Symbol addressing</b> reuses {@link TradingViewMindsSource}'s proven
 * mapping ({@code *.DE} → {@code XETR:}, a naked US symbol → {@code NASDAQ:}
 * then {@code NYSE:}) rather than inventing a second one; name and ISIN keys
 * go through {@link TradingViewSymbolSearch}, which is the only surface in this
 * package that knows ISINs. {@link #newsFor} fans the resolved keys additively
 * (ISIN through the primary listing, symbol through the venue candidates, name
 * through the best stock match), de-duplicated by story id.
 *
 * <p>Transport {@code DIRECT,BROWSER}: the mediator answers a bare client
 * keyless once the Origin/Referer pair is set. TradingView sits behind
 * Cloudflare, so the browser joker is the fallback if a challenge wall ever
 * appears.
 */
@Singleton
public class TradingViewNewsSource extends AbstractWebSource implements InstrumentSource {

    private static final Logger LOG = LoggerFactory.getLogger(TradingViewNewsSource.class);

    static final String NEWS_FLOW_URL =
            "https://news-mediator.tradingview.com/news-flow/v2/news?filter=lang%%3A%s&client=screener";
    static final String SYMBOL_NEWS_URL =
            "https://news-mediator.tradingview.com/public/view/v1/symbol"
                    + "?filter=lang%%3A%s&filter=symbol%%3A%s&client=overview&streaming=false";
    static final String STORY_URL =
            "https://news-headlines.tradingview.com/v2/story?id=%s&lang=%s";

    /** Marks an item whose BODY is licensed away - headline only. */
    static final String GATED_SUFFIX = " (paywall)";

    /** {@code permission} values that mean "stub only", beside {@code paywall}. */
    private static final Set<String> GATED_PERMISSIONS = Set.of("headline", "preview");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WIRE_TTL = Duration.ofMinutes(5);
    private static final Duration SYMBOL_TTL = Duration.ofMinutes(10);
    private static final int MAX_CACHED_SYMBOLS = 64;
    private static final int AST_MAX_DEPTH = 24;

    /** Newest first; items without a timestamp sort to the end. */
    private static final Comparator<Article> BY_RECENCY =
            Comparator.comparing(Article::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    /**
     * One story body.
     *
     * @param id             the story id ({@code provider:hash:0})
     * @param title          headline
     * @param provider       provider id ({@code reuters}, {@code dpa_afx})
     * @param source         provider display name ({@code Reuters})
     * @param published      publication instant, or null
     * @param link           the provider's own article URL, or null
     * @param relatedSymbols TradingView symbols the story tags
     * @param tags           TradingView's topical tags
     * @param text           the rendered {@code astDescription} body
     */
    public record Story(String id, String title, String provider, String source,
                        Instant published, String link, List<String> relatedSymbols,
                        List<String> tags, String text) {}

    private final TradingViewSymbolSearch symbolSearch;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    private record Cached(Instant fetchedAt, List<Article> items) {}

    private final Map<String, Cached> wireCache = new LinkedHashMap<>();

    private final Map<String, Cached> symbolCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_CACHED_SYMBOLS;
        }
    };

    @Inject
    public TradingViewNewsSource(WebFetcher fetcher, TradingViewSymbolSearch symbolSearch) {
        super(fetcher);
        this.symbolSearch = symbolSearch;
    }

    @Override
    public String sourceName() {
        return "tradingview-news";
    }

    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /** Reported agency news, not room opinion - this rides the press loom. */
    @Override
    public boolean socialSentiment() {
        return false;
    }

    /**
     * Additive key fan, de-duplicated by story id, newest first:
     * <ul>
     *   <li><b>ISIN</b> - resolved to the primary listing through
     *       {@link TradingViewSymbolSearch}, so a same-named twin can never
     *       answer here;</li>
     *   <li><b>symbol</b> - {@link TradingViewMindsSource}'s proven venue
     *       mapping, first venue with items wins;</li>
     *   <li><b>name</b> - resolved to a TradingView listing via the search
     *       index (primary equity listing preferred). Cheaper and far more
     *       precise than a full-text guess - the index is TradingView's own.</li>
     * </ul>
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        if (instrument == null || limit <= 0) return List.of();
        Map<String, Article> merged = new LinkedHashMap<>();

        instrument.isin().ifPresent(isin ->
                symbolSearch.primaryListing(isin.value()).ifPresent(hit -> {
                    for (Article a : forTvSymbol(hit.tvSymbol(), langFor(hit))) {
                        merged.putIfAbsent(a.uuid(), a);
                    }
                }));

        instrument.ticker().ifPresent(ticker -> {
            String house = ticker.value().trim().toUpperCase(Locale.ROOT);
            for (String tv : TradingViewMindsSource.tvSymbolCandidates(house)) {
                List<Article> items = forTvSymbol(tv, "en");
                if (!items.isEmpty()) {
                    for (Article a : items) merged.putIfAbsent(a.uuid(), a);
                    break;
                }
            }
        });

        String name = instrument.name();
        if (merged.size() < limit && name != null && !name.isBlank()) {
            symbolSearch.bestStockMatch(name).ifPresent(hit -> {
                for (Article a : forTvSymbol(hit.tvSymbol(), langFor(hit))) {
                    merged.putIfAbsent(a.uuid(), a);
                }
            });
        }

        return merged.values().stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * GENERAL stream: the German-language agency wire (dpa-AFX, Reuters, AWP,
     * pressetext, EQS …), newest first - the instrument-free door into this
     * source, 200 items per call. Stands ready beside the instrument fan.
     */
    public List<Article> latestGerman(int limit) {
        return latest("de", limit);
    }

    /**
     * GENERAL stream in a chosen language ({@code de}, {@code en}, …) - same
     * wire, different licensing pool.
     */
    public List<Article> latest(String lang, int limit) {
        if (limit <= 0) return List.of();
        String language = normalizeLang(lang);
        return wire(language).stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * GENERAL, instrument-addressed without the house symbol mapping: takes a
     * TradingView symbol verbatim ({@code MIL:PRY}, {@code ASX:PDI}) so a
     * caller that already resolved a venue - through
     * {@link TradingViewSymbolSearch} - is not forced back through the
     * Yahoo-style guess.
     */
    public List<Article> newsForTvSymbol(String tvSymbol, String lang, int limit) {
        if (tvSymbol == null || tvSymbol.isBlank() || limit <= 0) return List.of();
        return cap(forTvSymbol(tvSymbol.trim().toUpperCase(Locale.ROOT), normalizeLang(lang)), limit);
    }

    /**
     * The full body of an item, or empty when the item is GATED - see the
     * paywall rule in the class javadoc. A gated item costs NO request: the
     * marker rides on the publisher, so the decision is made before the wire is
     * touched. That is deliberate and must stay that way.
     */
    public Optional<String> fullText(Article item) {
        if (item == null || isGated(item.publisher())) return Optional.empty();
        return story(item.uuid(), "en").map(Story::text).filter(t -> !t.isBlank());
    }

    /**
     * The story behind an id - body, tags and related symbols. Callers holding
     * an {@link Article} should use {@link #fullText}, which honours the
     * paywall rule; this one is the raw door for a caller that already knows
     * the item is free.
     */
    public Optional<Story> story(String storyId, String lang) {
        if (storyId == null || storyId.isBlank()) return Optional.empty();
        String url = String.format(STORY_URL,
                URLEncoder.encode(storyId.trim(), StandardCharsets.UTF_8),
                normalizeLang(lang));
        String body = fetchBody(url, "story " + storyId);
        return body == null ? Optional.empty() : Optional.ofNullable(parseStory(body));
    }

    /** The TTL-cached wire for one language. */
    private synchronized List<Article> wire(String lang) {
        Cached c = wireCache.get(lang);
        if (c != null && c.fetchedAt().plus(WIRE_TTL).isAfter(Instant.now())) return c.items();
        String body = fetchBody(String.format(NEWS_FLOW_URL, lang), "news flow " + lang);
        List<Article> items = body == null ? null : parseItems(body);
        if (items == null) return c == null ? List.of() : c.items(); // outage: stale over empty
        wireCache.put(lang, new Cached(Instant.now(), items));
        return items;
    }

    /** The TTL-cached per-symbol articles, newest first. */
    private synchronized List<Article> forTvSymbol(String tvSymbol, String lang) {
        String key = tvSymbol + "|" + lang;
        Cached c = symbolCache.get(key);
        if (c != null && c.fetchedAt().plus(SYMBOL_TTL).isAfter(Instant.now())) return c.items();
        String url = String.format(SYMBOL_NEWS_URL, lang,
                URLEncoder.encode(tvSymbol, StandardCharsets.UTF_8));
        String body = fetchBody(url, "symbol news " + tvSymbol);
        List<Article> items = body == null ? null : parseItems(body);
        if (items == null) return c == null ? List.of() : c.items();
        List<Article> sorted = items.stream().sorted(BY_RECENCY).toList();
        symbolCache.put(key, new Cached(Instant.now(), sorted));
        return sorted;
    }

    /** One GET with the mandatory headers; {@code null} on anything but a 200. */
    private String fetchBody(String url, String what) {
        try {
            WebResponse resp = get(url, TradingViewApi.headers(), requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("TradingView {} answered status {}", what,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("TradingView {} failed: {}", what, e.getMessage());
        }
        return null;
    }

    /**
     * The mediator JSON ({@code items[]}) → items - the SAME shape on the
     * general wire and the per-symbol surface. Garbage or a body without an
     * {@code items} array yields {@code null} = "not a mediator answer", so a
     * torn reply is never cached as an empty result. Package-private for tests.
     */
    static List<Article> parseItems(String body) {
        if (body == null || body.isBlank()) return null;
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
        JsonNode items = root.path("items");
        if (!items.isArray()) return null;
        List<Article> out = new ArrayList<>();
        for (JsonNode n : items) {
            Article item = toItem(n);
            if (item != null) out.add(item);
        }
        return List.copyOf(out);
    }

    /** One mediator item → an {@link Article}, or null when unusable. */
    private static Article toItem(JsonNode n) {
        String id = text(n, "id");
        String title = text(n, "title");
        if (id == null || title == null) return null;

        String provider = n.path("provider").path("name").asText("").trim();
        if (provider.isEmpty()) provider = n.path("provider").path("id").asText("").trim();
        if (provider.isEmpty()) provider = "TradingView";

        // The provider's own URL where it exists, else TradingView's story page.
        String link = text(n, "link");
        if (link == null) link = TradingViewApi.permalink(text(n, "storyPath"));
        if (link == null) return null;

        boolean gated = isGatedItem(n);
        List<String> tickers = houseTickers(n.path("relatedSymbols"));
        long published = n.path("published").asLong(0);

        return new Article(
                id,
                title,
                gated ? gatedPublisher(provider) : provider,
                link,
                published > 0 ? Instant.ofEpochSecond(published) : null,
                tickers,
                null,
                null,
                false);
    }

    /**
     * True when TradingView is licensed to show only a stub - {@code paywall}
     * or a {@code permission} of {@code headline}/{@code preview}. The body of
     * such an item is never fetched. Package-private for tests.
     */
    static boolean isGatedItem(JsonNode n) {
        if (n.path("paywall").asBoolean(false)) return true;
        String permission = n.path("permission").asText("").trim().toLowerCase(Locale.ROOT);
        return GATED_PERMISSIONS.contains(permission);
    }

    /** The publisher a gated item is published under. Package-private for tests. */
    static String gatedPublisher(String provider) {
        return provider + GATED_SUFFIX;
    }

    /** True when a publisher string carries the gate marker. Package-private for tests. */
    static boolean isGated(String publisher) {
        return publisher != null && publisher.endsWith(GATED_SUFFIX);
    }

    /**
     * {@code relatedSymbols[].symbol} ({@code EXCHANGE:SYM}) → HOUSE (Yahoo-style)
     * symbols - the inverse of {@link TradingViewMindsSource}'s mapping:
     * {@code XETR:} → {@code *.DE}, the US venues → the bare symbol. Any other
     * venue is DROPPED rather than guessed: an unproven mapping in the ticker
     * index is worse than a missing one, because the pool keys off it.
     * Package-private for tests.
     */
    static List<String> houseTickers(JsonNode relatedSymbols) {
        if (relatedSymbols == null || !relatedSymbols.isArray()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode s : relatedSymbols) {
            String tv = s.path("symbol").asText("").trim().toUpperCase(Locale.ROOT);
            String house = houseSymbol(tv);
            if (house != null) out.add(house);
        }
        return List.copyOf(out);
    }

    /** {@code EXCHANGE:SYM} → house symbol, or {@code null} for unproven venues. */
    static String houseSymbol(String tvSymbol) {
        if (tvSymbol == null || tvSymbol.isBlank()) return null;
        int c = tvSymbol.indexOf(':');
        if (c < 0) return tvSymbol;
        String exchange = tvSymbol.substring(0, c);
        String ticker = tvSymbol.substring(c + 1);
        if (ticker.isEmpty()) return null;
        return switch (exchange) {
            case "XETR" -> ticker + ".DE";
            case "NASDAQ", "NYSE", "AMEX", "NYSE ARCA", "BATS" -> ticker;
            default -> null; // .PA, MIL, ASX … — this source only vouches for proven mappings
        };
    }

    /**
     * The story JSON → {@link Story}; {@code null} for garbage or a body
     * without a title. Package-private for tests.
     */
    static Story parseStory(String body) {
        if (body == null || body.isBlank()) return null;
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
        String title = text(root, "title");
        if (title == null) return null;
        List<String> symbols = new ArrayList<>();
        for (JsonNode s : root.path("relatedSymbols")) {
            String sym = s.path("symbol").asText("").trim();
            if (!sym.isEmpty()) symbols.add(sym);
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode t : root.path("tags")) {
            String tag = t.path("title").asText("").trim();
            if (!tag.isEmpty()) tags.add(tag);
        }
        long published = root.path("published").asLong(0);
        return new Story(
                text(root, "id"),
                title,
                text(root, "provider"),
                text(root, "source"),
                published > 0 ? Instant.ofEpochSecond(published) : null,
                text(root, "link"),
                List.copyOf(symbols),
                List.copyOf(tags),
                renderAst(root.path("astDescription")));
    }

    /**
     * {@code astDescription} → plain text: paragraph nodes become lines, inline
     * nodes ({@code b}, {@code i}, {@code a}, symbol/url spans) contribute their
     * text, {@code news-image} nodes are skipped - an image node carries only an
     * alt text and would read as a caption glued into the body. Depth-bounded
     * so a torn tree can never recurse away. Package-private for tests.
     */
    static String renderAst(JsonNode ast) {
        if (ast == null || ast.isMissingNode() || ast.isNull()) return "";
        StringBuilder sb = new StringBuilder();
        appendNode(ast, sb, 0);
        return sb.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    private static void appendNode(JsonNode node, StringBuilder sb, int depth) {
        if (node == null || depth > AST_MAX_DEPTH) return;
        if (node.isTextual()) {
            sb.append(node.asText());
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) appendNode(child, sb, depth + 1);
            return;
        }
        if (!node.isObject()) return;
        String type = node.path("type").asText("");
        if ("news-image".equals(type)) return;
        boolean block = switch (type) {
            case "p", "h1", "h2", "h3", "h4", "li", "blockquote", "root" -> true;
            default -> false;
        };
        for (JsonNode child : node.path("children")) appendNode(child, sb, depth + 1);
        if ("url".equals(type) || "symbol".equals(type)) {
            JsonNode params = node.path("params");
            String inline = params.path("text").asText("");
            if (inline.isEmpty()) inline = params.path("symbol").asText("");
            sb.append(inline);
        }
        if (block && sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
    }

    /** German venues get the German pool, everything else the English one. */
    private static String langFor(TradingViewSymbolSearch.SymbolHit hit) {
        return "DE".equalsIgnoreCase(hit.country()) ? "de" : "en";
    }

    private static String normalizeLang(String lang) {
        return lang == null || lang.isBlank() ? "en" : lang.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static List<Article> cap(List<Article> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }
}
