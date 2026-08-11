package de.bsommerfeld.wsbg.terminal.web.impl.sources.tradingview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.WebSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * TradingView's own instrument index - the keyless resolver that hands out
 * <b>ISINs</b> ({@code symbol-search.tradingview.com/symbol_search/v3/},
 * live-probed 2026-08-02). A resolver BUILDING BLOCK for the TradingView
 * sources, not an article source itself.
 *
 * <p>Why this matters more than the endpoint looks: ISIN is this house's join
 * key for German instruments, and almost no free search surface prints one.
 * This one does, on nearly every equity row, and it works in BOTH directions -
 * a name query ({@code "siemens"} → 689 hits) returns the ISIN beside each
 * listing, and an ISIN query returns every venue that carries it with
 * {@code found_by_isin: true}. That makes it a name→ISIN resolver AND an
 * ISIN→venue resolver in one call, with {@code exchange}, {@code currency_code},
 * {@code type} and {@code is_primary_listing} to pick the right listing.
 *
 * <p><b>Header gotcha:</b> see {@link TradingViewApi} - without the
 * Origin/Referer pair this host answers 403.
 *
 * <p><b>{@code hl=1} highlighting:</b> the API wraps every matched span in
 * {@code <em>…</em>} - including inside the {@code isin} field, so a raw read
 * yields {@code <em>DE0007236101</em>}. Every text field is unwrapped in
 * {@link #parse} so no consumer ever sees markup; the highlighting stays
 * switched on because it is also the only signal of WHICH field matched.
 *
 * <p><b>Miss shape:</b> an unknown text answers 200 with
 * {@code symbols_remaining: 0} and an empty {@code symbols} array - never an
 * error status, so emptiness is the only miss signal.
 *
 * <p>Transport {@code DIRECT,BROWSER}: the host is keyless and wall-free once
 * the Origin/Referer pair is set; TradingView is Cloudflare-fronted, so the
 * browser joker remains the fallback if a challenge ever appears.
 */
@Singleton
public class TradingViewSymbolSearch extends AbstractWebSource implements WebSource {

    private static final Logger LOG = LoggerFactory.getLogger(TradingViewSymbolSearch.class);

    static final String SEARCH_URL =
            "https://symbol-search.tradingview.com/symbol_search/v3/"
                    + "?text=%s&hl=1&lang=%s&search_type=undefined&domain=production";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CACHE_TTL = Duration.ofHours(6);
    private static final int MAX_CACHED_QUERIES = 128;

    /**
     * One instrument row.
     *
     * @param symbol          venue ticker ({@code SIE}, {@code NVD})
     * @param description     instrument name ({@code Siemens AG})
     * @param type            {@code stock}, {@code futures}, {@code fund}, {@code dr}, …
     * @param exchange        venue code ({@code XETR}, {@code NSE}, {@code GETTEX})
     * @param currencyCode    quote currency ({@code EUR})
     * @param isin            the ISIN, or {@code null} where the row carries none
     *                        (derivatives/futures rows usually do not)
     * @param providerId      data provider behind the row ({@code ice}, …)
     * @param country         two-letter listing country, or {@code null}
     * @param foundByIsin     the query matched this row's ISIN
     * @param foundByCusip    the query matched this row's CUSIP
     * @param primaryListing  TradingView's own "this is the main listing" flag
     */
    public record SymbolHit(
            String symbol,
            String description,
            String type,
            String exchange,
            String currencyCode,
            String isin,
            String providerId,
            String country,
            boolean foundByIsin,
            boolean foundByCusip,
            boolean primaryListing) {

        /** {@code EXCHANGE:SYM} - the addressing form every other TradingView API wants. */
        public String tvSymbol() {
            return exchange == null || exchange.isBlank() ? symbol : exchange + ":" + symbol;
        }

        /** True for a plain equity row - the only type an instrument resolver should trust. */
        public boolean isStock() {
            return "stock".equalsIgnoreCase(type);
        }
    }

    /**
     * One answer page.
     *
     * @param remaining {@code symbols_remaining} - hits beyond this page
     * @param hits      the rows, in the API's own relevance order
     */
    public record SearchResult(int remaining, List<SymbolHit> hits) {
        public boolean isEmpty() {
            return hits.isEmpty();
        }
    }

    private final Duration requestTimeout = Duration.ofSeconds(10);

    private record Cached(Instant fetchedAt, SearchResult result) {}

    /** Query (text + lang) → result. Instrument metadata moves in months, not minutes. */
    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_CACHED_QUERIES;
        }
    };

    @Inject
    public TradingViewSymbolSearch(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "tradingview-symbol-search";
    }

    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /** Free-text search ({@code lang=de}), TTL-cached. Never null. */
    public SearchResult search(String text) {
        return search(text, "de");
    }

    /**
     * Free-text search in a given UI language. {@code lang} only steers the
     * description language of some rows - the instrument set is the same.
     */
    public SearchResult search(String text, String lang) {
        if (text == null || text.isBlank()) return new SearchResult(0, List.of());
        String query = text.trim();
        String language = lang == null || lang.isBlank() ? "de" : lang.trim();
        return cached(query + "|" + language, () -> fetch(query, language));
    }

    /**
     * ISIN → every listing carrying it, primary listing first. Only rows the API
     * itself marked {@code found_by_isin} are returned, so a fuzzy name-ish
     * match can never masquerade as an ISIN hit.
     */
    public List<SymbolHit> resolveByIsin(String isin) {
        if (isin == null || isin.isBlank()) return List.of();
        String wanted = isin.trim().toUpperCase(Locale.ROOT);
        List<SymbolHit> hits = new ArrayList<>();
        for (SymbolHit h : search(wanted, "en").hits()) {
            if (h.foundByIsin() && wanted.equalsIgnoreCase(h.isin())) hits.add(h);
        }
        hits.sort((a, b) -> Boolean.compare(b.primaryListing(), a.primaryListing()));
        return List.copyOf(hits);
    }

    /**
     * ISIN → the one listing to address ({@code is_primary_listing}, else the
     * first venue that carries it). Empty when the ISIN is unknown here.
     */
    public Optional<SymbolHit> primaryListing(String isin) {
        return resolveByIsin(isin).stream().findFirst();
    }

    /**
     * Company name → its ISIN, via the highest-ranked EQUITY row. Empty when
     * the search finds no stock row or that row carries no ISIN - never a
     * guessed ISIN off a futures/fund/CFD shell.
     */
    public Optional<String> isinForName(String companyName) {
        for (SymbolHit h : search(companyName).hits()) {
            if (h.isStock() && h.isin() != null && !h.isin().isBlank()) {
                return Optional.of(h.isin());
            }
        }
        return Optional.empty();
    }

    /**
     * Company name → the TradingView symbol to address ({@code EXCHANGE:SYM}),
     * preferring a primary equity listing. Used by
     * {@link TradingViewNewsSource} to answer name-addressed queries; public
     * because any caller needing TradingView addressing wants exactly this.
     */
    public Optional<SymbolHit> bestStockMatch(String text) {
        SymbolHit fallback = null;
        for (SymbolHit h : search(text).hits()) {
            if (!h.isStock()) continue;
            if (h.primaryListing()) return Optional.of(h);
            if (fallback == null) fallback = h;
        }
        return Optional.ofNullable(fallback);
    }

    private synchronized SearchResult cached(String key, java.util.function.Supplier<SearchResult> loader) {
        Cached c = cache.get(key);
        if (c != null && c.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) return c.result();
        SearchResult fresh = loader.get();
        if (fresh == null) return c == null ? new SearchResult(0, List.of()) : c.result();
        cache.put(key, new Cached(Instant.now(), fresh));
        return fresh;
    }

    /** One live search; {@code null} on a transport failure so it is not cached. */
    private SearchResult fetch(String text, String lang) {
        String url = String.format(SEARCH_URL,
                URLEncoder.encode(text, StandardCharsets.UTF_8),
                URLEncoder.encode(lang, StandardCharsets.UTF_8));
        try {
            WebResponse resp = get(url, TradingViewApi.headers(), requestTimeout);
            if (resp == null || resp.status() != 200) {
                LOG.debug("TradingView symbol-search answered status {}",
                        resp == null ? "null" : resp.status());
                return null;
            }
            return parse(resp.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("TradingView symbol-search failed for '{}': {}", text, e.getMessage());
            return null;
        }
    }

    /**
     * The search JSON → hits. Garbage (HTML shell, torn JSON, a body without a
     * {@code symbols} array) yields {@code null} = "not a search answer"; a
     * well-shaped answer with zero rows yields an empty result = the pinned
     * miss. Package-private for tests.
     */
    static SearchResult parse(String body) {
        if (body == null || body.isBlank()) return null;
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
        JsonNode symbols = root.path("symbols");
        if (!symbols.isArray()) return null;
        List<SymbolHit> hits = new ArrayList<>();
        for (JsonNode n : symbols) {
            String symbol = clean(n.path("symbol").asText(""));
            if (symbol.isEmpty()) continue;
            hits.add(new SymbolHit(
                    symbol,
                    blankToNull(clean(n.path("description").asText(""))),
                    blankToNull(clean(n.path("type").asText(""))),
                    blankToNull(clean(n.path("exchange").asText(""))),
                    blankToNull(clean(n.path("currency_code").asText(""))),
                    blankToNull(clean(n.path("isin").asText(""))),
                    blankToNull(clean(n.path("provider_id").asText(""))),
                    blankToNull(clean(n.path("country").asText(""))),
                    n.path("found_by_isin").asBoolean(false),
                    n.path("found_by_cusip").asBoolean(false),
                    n.path("is_primary_listing").asBoolean(false)));
        }
        return new SearchResult(root.path("symbols_remaining").asInt(0), List.copyOf(hits));
    }

    /**
     * Strips the {@code hl=1} highlight markup ({@code <em>…</em>}) the API
     * sprays over every matched field - the ISIN field included.
     * Package-private for tests.
     */
    static String clean(String s) {
        if (s == null) return "";
        return s.replace("<em>", "").replace("</em>", "").trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
