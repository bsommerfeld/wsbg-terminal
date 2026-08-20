package de.bsommerfeld.wsbg.terminal.web.impl.sources.tradersunion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traders Union's KEYLESS quotes API - the gainers/losers board across seven
 * asset classes, live-probed 2026-08-02. No key, no cookie, no referer:
 *
 * <pre>
 * GET https://quotes.tradersunion.com/api/v3/informer/{gainers|losers}/
 *        ?category={forex|crypto|stocks|indices|commodities|etfs|bonds}
 *        &amp;locale=de&amp;mode={short_term|long_term}
 * → {"status":"success","code":200,
 *    "data":[{"ticker_id":6551,"ticker":"REPL\/USD","symbol":"REPL",
 *             "percent":"107.02%","link":"\/de\/currencies\/forecast\/repl-usd\/…"}]}
 * </pre>
 *
 * <p>This is NOT a news source - it carries no articles. It is a market
 * surface with one property nothing else in the house has for this venue: the
 * numeric {@code ticker_id} is a STABLE join key that survives a symbol rename,
 * and it addresses Traders Union's own instrument pages.
 *
 * <p><b>The percent is a magnitude, not a signed change.</b> The losers board
 * answers {@code "7.31%"} exactly like the gainers board does; the direction
 * lives in {@link Mover#gainer()}. {@link Mover#signedPercentChange()} folds
 * the two together for a caller that wants one number.
 *
 * <p><b>Instrument pages ride the API's own links.</b> Traders Union's AI
 * manifest sets {@code url_generation_allowed: false} - URLs may only come from
 * sitemaps, canonical tags or a link the site itself emitted. Every
 * {@link Mover#link()} IS such a link, so {@link #instrumentPage(String)} is fed
 * a DISCOVERED url and never a composed one. The shapes seen live are the
 * forecast board ({@code /de/currencies/forecast/<asset>/daily-and-weekly/} and
 * {@code /signals/}), the price history
 * ({@code /de/currencies/price-history/<asset>/}), the ETF board
 * ({@code /de/etfs/<ticker>/short-term/}) and the bond yield board
 * ({@code /de/bonds/<ticker>/yield/}) - all of them carry
 * {@code data-symbol} and the numeric ticker id, so ONE parser serves all five.
 * ({@code /long-term-forecast/} answered 404 on the probe day and is not a
 * shape this venue serves - one more reason not to compose urls.)
 *
 * <p><b>Reliability.</b> Single pages 500 sporadically
 * ({@code /de/currencies/forecast/eur-usd/daily-and-weekly/} did on the probe
 * day while {@code cbk-usd} answered 200) - every call here answers with an
 * empty result instead of propagating the failure, so one sick page never kills
 * a sweep.
 *
 * <p><b>Transport.</b> Cloudflare fronts the host and rejects a naked HTTP/2
 * fingerprint; a browser-shaped request answers 200 throughout. That is exactly
 * what the shared {@link WebFetcher} chain does (browser-first), so production
 * rides it rather than a bare client.
 *
 * <p><b>Forecast content is a forecast.</b> The instrument pages carry price
 * predictions - anything downstream must present them as such, never as a
 * commitment, and must cite the source page.
 */
@Singleton
public class TradersUnionMoversClient {

    private static final Logger LOG = LoggerFactory.getLogger(TradersUnionMoversClient.class);

    static final String HOST = "https://tradersunion.com";
    static final String API_URL =
            "https://quotes.tradersunion.com/api/v3/informer/%s/?category=%s&locale=de&mode=%s";

    /** Browser-first (old unannotated binding): Cloudflare fronts both hosts. */
    private static final FetchUtil[] MODES = {FetchUtil.BROWSER, FetchUtil.DIRECT};

    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@code data-symbol="CBK/USD"} - the human-readable instrument pair. */
    private static final Pattern SYMBOL_ATTR = Pattern.compile("data-symbol=\"([^\"]+)\"");
    /** {@code data-ticker="1142"} - the numeric id, same value as {@code ticker_id}. */
    private static final Pattern TICKER_ID_ATTR = Pattern.compile("data-ticker=\"(\\d+)\"");
    /** {@code "ticker_id":1142} in the page's inline bootstrap JSON. */
    private static final Pattern TICKER_ID_JSON = Pattern.compile("\"ticker_id\"\\s*:\\s*(\\d+)");
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** The seven asset classes the informer serves. */
    public enum Category {
        FOREX, CRYPTO, STOCKS, INDICES, COMMODITIES, ETFS, BONDS;

        /** The value the {@code category} query parameter expects. */
        public String slug() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The two horizons the informer ranks over. */
    public enum Mode {
        SHORT_TERM, LONG_TERM;

        /** The value the {@code mode} query parameter expects. */
        public String slug() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One ranked instrument.
     *
     * @param tickerId       Traders Union's own numeric instrument id - stable
     *                       across symbol renames, and the join key into their
     *                       instrument pages
     * @param ticker         the quoted pair as printed ({@code REPL/USD})
     * @param symbol         the bare instrument symbol ({@code REPL})
     * @param percentChange  the move as a MAGNITUDE (always ≥ 0, see
     *                       {@link #signedPercentChange()})
     * @param link           absolute url of the instrument page the API pointed
     *                       at - a DISCOVERED url, safe to fetch
     * @param category       the {@link Category#slug()} this ranking came from
     * @param gainer         {@code true} for the gainers board, {@code false}
     *                       for the losers board
     */
    public record Mover(int tickerId, String ticker, String symbol, double percentChange,
                        String link, String category, boolean gainer) {

        /** The move with its sign applied - negative on the losers board. */
        public double signedPercentChange() {
            return gainer ? percentChange : -percentChange;
        }
    }

    /**
     * One Traders Union instrument page (forecast / signals / price history /
     * ETF / bond yield), reduced to the identity it carries. Currently UNUSED by
     * any caller - it stands ready so the forecast, signal and price-history
     * layer can be mobilised without touching this source again.
     *
     * @param url      the page it was read from (cite this, always)
     * @param symbol   {@code data-symbol}, e.g. {@code CBK/USD} or {@code KORU}
     * @param tickerId the numeric id, or {@code -1} when the page carried none
     * @param title    the page title as rendered in the requested language
     */
    public record InstrumentPage(String url, String symbol, int tickerId, String title) {}

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(15);

    @Inject
    public TradersUnionMoversClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The gainers board for one asset class and horizon. Empty on any failure. */
    public List<Mover> gainers(Category category, Mode mode) {
        return movers(category, true, mode);
    }

    /** The losers board for one asset class and horizon. Empty on any failure. */
    public List<Mover> losers(Category category, Mode mode) {
        return movers(category, false, mode);
    }

    /** One board: asset class × direction × horizon. Empty on any failure. */
    public List<Mover> movers(Category category, boolean gainer, Mode mode) {
        if (category == null || mode == null) return List.of();
        String url = String.format(API_URL, gainer ? "gainers" : "losers",
                category.slug(), mode.slug());
        String body = fetchText(url);
        return body == null ? List.of() : parse(body, category.slug(), gainer);
    }

    /**
     * The WHOLE board for one horizon: all seven asset classes × both
     * directions, gainers before losers per class. 14 calls - meant for a
     * periodic market sweep, not for a per-instrument lookup.
     */
    public List<Mover> allMovers(Mode mode) {
        List<Mover> out = new ArrayList<>();
        for (Category category : Category.values()) {
            out.addAll(movers(category, true, mode));
            out.addAll(movers(category, false, mode));
        }
        return List.copyOf(out);
    }

    /** Both horizons across every asset class and direction - 28 calls. */
    public List<Mover> allMovers() {
        List<Mover> out = new ArrayList<>();
        for (Mode mode : Mode.values()) out.addAll(allMovers(mode));
        return List.copyOf(out);
    }

    /**
     * Reads one instrument page. {@code discoveredUrl} MUST come from a
     * {@link Mover#link()}, a sitemap or a canonical tag - never from string
     * assembly (the site's AI manifest forbids generated urls). Currently
     * unused; it is the door to the forecast/signal/price-history layer.
     *
     * @return the page's identity, or {@code null} when the page failed (a 500
     *         on a single asset is routine here)
     */
    public InstrumentPage instrumentPage(String discoveredUrl) {
        if (discoveredUrl == null || discoveredUrl.isBlank()) return null;
        String url = absolute(discoveredUrl);
        String html = fetchText(url);
        return html == null ? null : parseInstrumentPage(url, html);
    }

    /** GET as text; any non-200 (403 wall, 500 sick page) answers {@code null}. */
    private String fetchText(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("Accept", "application/json, text/html;q=0.9, */*;q=0.8",
                            "Accept-Language", "de-DE,de;q=0.9,en;q=0.8"),
                    requestTimeout, MODES);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("[TradersUnion] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[TradersUnion] {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /**
     * Informer payload → movers. Network-free and garbage-tolerant.
     * Package-private for tests.
     */
    static List<Mover> parse(String json, String category, boolean gainer) {
        if (json == null || json.isBlank()) return List.of();
        List<Mover> out = new ArrayList<>();
        try {
            JsonNode data = JSON.readTree(json).path("data");
            if (!data.isArray()) return List.of();
            for (JsonNode n : data) {
                String ticker = text(n, "ticker");
                String symbol = text(n, "symbol");
                if (ticker.isEmpty() && symbol.isEmpty()) continue;
                out.add(new Mover(
                        n.path("ticker_id").asInt(-1),
                        ticker,
                        symbol.isEmpty() ? ticker : symbol,
                        parsePercent(text(n, "percent")),
                        absolute(text(n, "link")),
                        category,
                        gainer));
            }
        } catch (Exception e) {
            LOG.debug("[TradersUnion] unparseable informer payload: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * Instrument page → identity. Reads {@code data-symbol} plus the numeric id
     * from either {@code data-ticker} or the inline {@code "ticker_id"} bootstrap.
     * Package-private for tests.
     *
     * <p>⚠️ The 2026-08-02 field note named the attribute {@code data-ticker-id};
     * live it is {@code data-ticker} carrying the same integer. Both the
     * attribute and the JSON key are read, so either spelling resolves.
     */
    static InstrumentPage parseInstrumentPage(String url, String html) {
        if (html == null || html.isBlank()) return null;
        Matcher sym = SYMBOL_ATTR.matcher(html);
        String symbol = sym.find() ? sym.group(1).trim() : null;
        int tickerId = -1;
        Matcher attr = TICKER_ID_ATTR.matcher(html);
        if (attr.find()) {
            tickerId = parseIntOr(attr.group(1), -1);
        } else {
            Matcher inline = TICKER_ID_JSON.matcher(html);
            if (inline.find()) tickerId = parseIntOr(inline.group(1), -1);
        }
        Matcher t = TITLE.matcher(html);
        String title = t.find() ? collapse(t.group(1)) : null;
        if (symbol == null && tickerId < 0) return null;
        return new InstrumentPage(url, symbol, tickerId, title);
    }

    /** {@code "107.02%"} / {@code "7,31 %"} → {@code 107.02} / {@code 7.31}; junk → 0. */
    static double parsePercent(String raw) {
        if (raw == null || raw.isBlank()) return 0d;
        String v = raw.replace("%", "").replace(" ", "").trim().replace(',', '.');
        try {
            return Math.abs(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    /** Site-relative link → absolute; anything already absolute passes through. */
    static String absolute(String link) {
        if (link == null || link.isBlank()) return "";
        String v = link.trim();
        if (v.startsWith("http://") || v.startsWith("https://")) return v;
        return v.startsWith("/") ? HOST + v : HOST + "/" + v;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
