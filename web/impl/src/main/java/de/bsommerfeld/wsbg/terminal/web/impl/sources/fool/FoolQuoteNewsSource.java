package de.bsommerfeld.wsbg.terminal.web.impl.sources.fool;

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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Motley Fool's per-instrument QUOTE PAGE as its own source — the leg that
 * reaches weeks back where the chronological sitemap window is two days wide.
 *
 * <p>Why a second source instead of a second leg inside
 * {@link FoolNewsSource#newsFor}: these two legs do not have the same reach.
 * The pool answers "what did Fool publish about this instrument TODAY" — a
 * wire question. The quote page answers "what has Fool ever written about
 * it" — archive depth a dossier weighs and a headline drowns in (2026-08-03).
 *
 * <p><b>Quote pages</b> ({@code www.fool.com/quote/<exchange>/<symbol>/},
 * live-probed 2026-08-02) are Next.js server-rendered, carrying the ~20 most
 * recent Fool articles on exactly this instrument in two places: a clean
 * JSON-LD {@code ItemList} ({@code <script id="quote-page-related-schema">},
 * 10 entries with headline, date and image) and the escaped
 * {@code self.__next_f} payload (~22 dated entries, wider but image-less).
 * Both are read and joined — see {@link #parseQuoteArticles}.
 *
 * <p><b>Which exchange?</b> The quote path needs a venue segment the caller
 * doesn't know. {@link #quotePageFor} resolves it per symbol: a memo first,
 * otherwise probing {@code nasdaq → nyse → crypto} until one answers 200 — a
 * wrong venue returns a clean 404. An unresolvable symbol is remembered as
 * such for the page TTL, so a burst of queries probes once, not once per
 * call. (The old world could also consult a warm quotes-sitemap ticker map;
 * nothing warms one here, so that branch is gone.)
 *
 * <p>{@code robots.txt} (re-read 2026-08-02): quote pages are explicitly
 * listed or free — the blanket {@code Disallow: /} belongs to MauiBot and
 * Bytespider only.
 *
 * <p>Transport {@code BROWSER,DIRECT}: joker-first is the house standard for
 * public websites; fool.com carries no wall today, but the chain costs
 * nothing at this cadence and survives one growing.
 */
@Singleton
public class FoolQuoteNewsSource extends AbstractWebSource implements InstrumentSource {

    private static final Logger LOG = LoggerFactory.getLogger(FoolQuoteNewsSource.class);

    static final String QUOTE_BASE = "https://www.fool.com/quote/";

    /** Probe order when the venue is unknown — the two big venues first, crypto last. */
    static final List<String> EXCHANGES = List.of("nasdaq", "nyse", "crypto");

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /** A resolved venue holds for a day; an unresolved symbol is retried on the page's rhythm (a fresh listing must not stay invisible for a day). */
    private static final Duration EXCHANGE_TTL = Duration.ofHours(24);

    /** Quote pages this source keeps around, before the whole map is dropped. */
    private static final int QUOTE_PAGE_CACHE_MAX = 256;

    /** Newest first; items without a timestamp sort to the end. */
    private static final Comparator<Article> BY_RECENCY =
            Comparator.comparing(Article::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** symbol → venue segment ({@code ""} = probed, not found anywhere). */
    private final Map<String, CachedText> exchangeMemo = new ConcurrentHashMap<>();
    /** symbol → the fetched quote page, so a burst never pays twice. */
    private final Map<String, CachedPage> quotePages = new ConcurrentHashMap<>();

    private record CachedText(Instant fetchedAt, String value) {}

    private record CachedPage(Instant fetchedAt, String exchange, String html) {}

    /** One quote page, already resolved to its venue. {@code html} is {@code null} when the symbol has no Fool quote page. */
    record QuotePage(String symbol, String exchange, String html) {}

    private static boolean fresh(Instant fetchedAt, Duration ttl) {
        return fetchedAt != null && fetchedAt.plus(ttl).isAfter(Instant.now());
    }

    @Inject
    public FoolQuoteNewsSource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "fool-quote";
    }

    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.BROWSER, FetchUtil.DIRECT};
    }

    /**
     * Symbol-addressed only: the quote page IS a path segment per ticker
     * (Fool indexes under the bare US base symbol), so without a resolved
     * ticker the answer is empty. Newest first, capped at {@code limit}.
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        if (instrument == null || instrument.ticker().isEmpty() || limit <= 0) return List.of();
        String symbol = instrument.ticker().get().baseSymbol();
        QuotePage page = quotePageFor(symbol);
        if (page == null || page.html() == null) return List.of();
        return parseQuoteArticles(page.html(), page.symbol()).stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /**
     * Resolves a symbol's venue and hands back its quote page, cached for the
     * page TTL so a burst never pays twice. Venue resolution: memo → probe
     * {@code nasdaq → nyse → crypto} (a wrong venue is a clean 404). An
     * unresolvable symbol is remembered as such for the page TTL, so a burst
     * of queries probes once, not once per call.
     */
    QuotePage quotePageFor(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String sym = symbol.trim().toUpperCase(Locale.ROOT);
        if (!isQuotePathSymbol(sym)) return null;

        CachedPage cachedPage = quotePages.get(sym);
        if (cachedPage != null && fresh(cachedPage.fetchedAt(), CACHE_TTL)) {
            return new QuotePage(sym, cachedPage.exchange(), cachedPage.html());
        }

        CachedText memo = exchangeMemo.get(sym);
        if (memo != null && fresh(memo.fetchedAt(),
                memo.value().isEmpty() ? CACHE_TTL : EXCHANGE_TTL)) {
            if (memo.value().isEmpty()) return null;
            String html = fetchQuotePage(memo.value(), sym);
            return rememberPage(sym, memo.value(), html);
        }

        for (String exchange : EXCHANGES) {
            String html = fetchQuotePage(exchange, sym);
            if (html != null) {
                exchangeMemo.put(sym, new CachedText(Instant.now(), exchange));
                return rememberPage(sym, exchange, html);
            }
        }
        exchangeMemo.put(sym, new CachedText(Instant.now(), ""));
        return null;
    }

    /**
     * True when a symbol can appear in a quote path at all. Fool's quote URLs
     * are plain path segments, so an index symbol ({@code ^TECDAX}, {@code ^N225})
     * doesn't 404 - it fails URL parsing ("Illegal character in path") on every
     * one of the three venue probes, once per caller. Fool carries no index
     * quote pages either way, so the honest answer is a no-op before the URL is
     * built. Package-private for tests.
     */
    static boolean isQuotePathSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        return symbol.chars().allMatch(c ->
                (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-');
    }

    private QuotePage rememberPage(String symbol, String exchange, String html) {
        if (quotePages.size() > QUOTE_PAGE_CACHE_MAX) quotePages.clear();
        quotePages.put(symbol, new CachedPage(Instant.now(), exchange, html));
        return new QuotePage(symbol, exchange, html);
    }

    /** The quote page body, or {@code null} on anything but a 200 (a wrong venue 404s). */
    private String fetchQuotePage(String exchange, String symbol) {
        String url = QUOTE_BASE + exchange + "/" + symbol.toLowerCase(Locale.ROOT) + "/";
        try {
            WebResponse resp = get(url, Map.of("Accept", "text/html"), requestTimeout);
            if (resp != null && resp.status() == 200 && resp.body() != null
                    && !resp.body().isBlank()) {
                return resp.body();
            }
        } catch (Exception e) {
            LOG.debug("Fool quote page {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern RELATED_SCHEMA = Pattern.compile(
            "<script[^>]*id=\"quote-page-related-schema\"[^>]*>(.*?)</script>", Pattern.DOTALL);

    /**
     * One dated article inside the escaped Next.js payload: {@code publish_at},
     * {@code path} and {@code headline} sit in one flat object in that order.
     * {@code [^{}]*?} is the guard that keeps the three fields inside ONE object
     * — an object boundary breaks the match instead of pairing article A's date
     * with article B's link. The path shape is the date filter: the same rail
     * also lists undated evergreen guides ({@code /investing/stock-market/…}),
     * which are reference material, not news.
     */
    private static final Pattern PAYLOAD_ARTICLE = Pattern.compile(
            "\"publish_at\":\"([^\"]+)\""
                    + "[^{}]*?\"path\":\"(/[a-z0-9-]+/\\d{4}/\\d{2}/\\d{2}/[^\"/]+/)\""
                    + "[^{}]*?\"headline\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * The instrument's recent Fool coverage off its quote page, from both
     * surfaces the page carries:
     *
     * <ul>
     *   <li>the JSON-LD {@code ItemList} — 10 entries, each with headline,
     *       {@code datePublished} and an article image;</li>
     *   <li>the escaped {@code self.__next_f} payload — ~22 dated entries,
     *       roughly twice the reach but without images.</li>
     * </ul>
     *
     * <p>The rich leg leads, the wide leg appends what it doesn't already
     * carry (canonical-link join, same rule as the pool). Every item is tagged
     * with {@code symbol}: the page is the ticker join, so the tag is a fact,
     * not a guess. Garbage yields an empty list, never an exception.
     * Package-private for tests.
     */
    static List<Article> parseQuoteArticles(String html, String symbol) {
        if (html == null || html.isBlank()) return List.of();
        List<String> tickers = symbol == null || symbol.isBlank()
                ? List.of() : List.of(symbol.trim().toUpperCase(Locale.ROOT));

        List<Article> rich = new ArrayList<>();
        Matcher schema = RELATED_SCHEMA.matcher(html);
        if (schema.find()) {
            try {
                for (JsonNode a : JSON.readTree(schema.group(1)).path("itemListElement")) {
                    String url = FoolNewsSource.cleanLink(a.path("url").asText(null));
                    String headline = a.path("headline").asText(null);
                    if (url == null || url.isEmpty() || headline == null || headline.isEmpty()) {
                        continue;
                    }
                    rich.add(new Article(url, headline, FoolNewsSource.PUBLISHER, url,
                            FoolNewsSource.parseIsoDate(a.path("datePublished").asText(null)),
                            tickers, null, null, false, a.path("image").asText(null)));
                }
            } catch (Exception e) {
                LOG.debug("Unparseable Fool quote-page schema: {}", e.getMessage());
            }
        }

        List<Article> wide = new ArrayList<>();
        Matcher m = PAYLOAD_ARTICLE.matcher(unescapeJsString(html));
        while (m.find()) {
            String url = "https://www.fool.com" + m.group(2);
            String headline = unescapeJson(m.group(3));
            if (headline.isEmpty()) continue;
            wide.add(new Article(url, headline, FoolNewsSource.PUBLISHER, url,
                    FoolNewsSource.parseIsoDate(m.group(1)), tickers, null, null, false, null));
        }
        return FoolNewsSource.appendNew(List.copyOf(rich), List.copyOf(wide));
    }

    /**
     * Undoes ONE level of JavaScript string escaping — the Next.js payload is
     * JSON embedded in a JS string literal, so its quotes arrive as
     * {@code \"}. A single left-to-right pass, not a blind replace: a headline
     * that itself contains a quote arrives triple-escaped ({@code \\\"}), and
     * only a pass that consumes the {@code \\} pair FIRST — collapsing it to
     * one backslash — leaves valid JSON behind instead of a shredded string.
     * Package-private for tests.
     */
    static String unescapeJsString(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'n' -> out.append('\n');
                    default -> out.append('\\').append(n);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** JSON string escapes inside an already-unescaped payload value (headlines carry quotes and dashes). */
    private static String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"").replace("\\/", "/").replace("\\n", " ")
                .replace("\\\\", "\\").trim();
    }
}
