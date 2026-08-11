package de.bsommerfeld.wsbg.terminal.asx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ASX website's own Markit backends (probed 2026-08-05): keyless GETs on
 * {@code asx.api.markitdigital.com} answer the live quote ({@code /header}:
 * last, bid/ask, volume, market cap and the internal {@code xid}) and the
 * fundamentals ({@code /key-statistics}: ISIN, average volume, EPS,
 * dividend). History comes from Markit's chartworks backend - a POST whose
 * symbol is the {@code xid}, NOT the ticker - and reaches roughly TWENTY
 * YEARS of daily OHLCV in AUD.
 *
 * <p><b>Token:</b> chartworks wants an {@code access_token} that the ASX
 * hardcodes into its page bundle ({@code clientlib-base-*.js}, as
 * {@code API_TOKEN="<32 hex>"}). That is a scrape-at-startup secret, never
 * one to hardcode blindly: this client carries the last-known value only as
 * a FALLBACK and, on 401/403, re-scrapes the current one from the live page
 * bundle and caches it for the JVM's lifetime.
 *
 * <p><b>Transport:</b> the project's {@link WebFetcher} speaks only GET, so
 * the chartworks POST goes through {@link java.net.http.HttpClient} directly -
 * the same documented deviation the GraphQL case made.
 *
 * <p>Bare ASX codes only ({@code BHP}, {@code CBA}, {@code VAS}) - 3 to 6
 * characters, letters and digits. A Yahoo-suffixed {@code BHP.AX} or anything
 * else foreign-shaped is a network-free no-op; the caller hands over the
 * naked code.
 */
@Singleton
public class AsxMarketClient {

    private static final Logger LOG = LoggerFactory.getLogger(AsxMarketClient.class);

    static final String HEADER_URL =
            "https://asx.api.markitdigital.com/asx-research/1.0/companies/%s/header";
    static final String KEY_STATS_URL =
            "https://asx.api.markitdigital.com/asx-research/1.0/companies/%s/key-statistics";
    static final String CHART_URL =
            "https://content.markitcdn.com/api.markitondemand.com/apiman-gateway/MOD/chartworks-data/1.0/chartapi/series?access_token=%s";
    static final String TOKEN_PAGE_URL = "https://www.asx.com.au/markets/company/BHP";
    static final String ASX_ORIGIN = "https://www.asx.com.au";

    /** Last-known page-bundle token (2026-08-05) - fallback only, see class doc. */
    static final String FALLBACK_TOKEN = "83ff96335c2d45a094df02a206a39ff4";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Bare ASX code: companies are 3 letters, ETPs/hybrids up to 6 alphanumerics. */
    private static final Pattern ASX_CODE = Pattern.compile("[A-Z0-9]{3,6}");
    /** The bundle carrying the token, as referenced from the company page. */
    private static final Pattern BUNDLE_PATH =
            Pattern.compile("[^\"' ]*clientlib-base[^\"' ]*\\.js");
    /** {@code F2.API_TOKEN="83ff..."} in the bundle - verified live 2026-08-05. */
    private static final Pattern API_TOKEN =
            Pattern.compile("API_TOKEN\\s*[:=]\\s*\"([0-9a-f]{32})\"");

    private final WebFetcher fetcher;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String userAgent = BrowserUserAgent.random();
    /** Twenty years of bars in one reply - give it room. */
    private final Duration requestTimeout = Duration.ofSeconds(30);
    private volatile String cachedToken;

    /** Test/default: plain direct transport. */
    public AsxMarketClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public AsxMarketClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The live quote for one bare ASX code, fundamentals merged in.
     *
     * @return the quote, or empty when the code is not ASX-shaped, unknown or
     *         the endpoint failed - never {@code null}
     */
    public Optional<AsxQuote> quote(String symbol) {
        String code = gate(symbol);
        if (code == null) return Optional.empty();
        try {
            String header = get(String.format(HEADER_URL, code));
            if (header == null) return Optional.empty();
            String stats = get(String.format(KEY_STATS_URL, code));
            Optional<AsxQuote> quote = parseQuote(header, stats);
            quote.ifPresent(q -> LOG.info(
                    "[ASX] {} ({}) → last={} bid={} ask={} vol={} mcap={} isin={} xid={}",
                    q.symbol(), q.name(), q.priceLast(), q.bid(), q.ask(),
                    q.volume(), q.marketCap(), q.isin(), q.xid()));
            return quote;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[ASX] quote for {} failed: {}", code, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Daily OHLCV history for one bare ASX code - resolves the {@code xid}
     * via {@code /header} first, then asks chartworks.
     *
     * @param days calendar days back from today ({@code 7300} ≈ the full
     *        twenty years the backend holds)
     * @return the history, oldest bar first, or empty on any failure -
     *         never {@code null}
     */
    public Optional<AsxHistory> history(String symbol, int days) {
        String code = gate(symbol);
        if (code == null || days <= 0) return Optional.empty();
        try {
            String header = get(String.format(HEADER_URL, code));
            if (header == null) return Optional.empty();
            String xid = MAPPER.readTree(header).path("data").path("xid").asText("");
            if (xid.isEmpty()) return Optional.empty();
            String reply = postChart(xid, days);
            Optional<AsxHistory> history = parseHistory(code, reply);
            history.ifPresent(h -> LOG.info("[ASX] {} history → {} bars ({}, xid {})",
                    h.symbol(), h.bars().size(), h.currency(), h.xid()));
            return history;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[ASX] history for {} failed: {}", code, e.getMessage());
            return Optional.empty();
        }
    }

    /** Uppercased bare code, or {@code null} when not ASX-shaped. */
    private static String gate(String symbol) {
        if (symbol == null) return null;
        String code = symbol.trim().toUpperCase(Locale.ROOT);
        return ASX_CODE.matcher(code).matches() ? code : null;
    }

    private String get(String url) throws Exception {
        WebResponse resp = fetcher.fetch(url,
                Map.of("User-Agent", userAgent, "Accept", "application/json",
                        "Origin", ASX_ORIGIN, "Referer", ASX_ORIGIN + "/"),
                requestTimeout);
        if (resp == null || resp.status() != 200) {
            LOG.debug("[ASX] status {} for {}",
                    resp == null ? "null" : resp.status(), url);
            return null;
        }
        return resp.body();
    }

    /**
     * The chartworks POST - direct {@link HttpClient} because the house
     * {@link WebFetcher} only speaks GET. On 401/403 the token is re-scraped
     * from the live page bundle and the call retried ONCE.
     */
    private String postChart(String xid, int days) throws Exception {
        String token = cachedToken != null ? cachedToken : FALLBACK_TOKEN;
        HttpResponse<String> resp = sendChart(xid, days, token);
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            String fresh = scrapeToken();
            if (fresh == null || fresh.equals(token)) return null;
            cachedToken = fresh;
            LOG.info("[ASX] chart token rotated, re-scraped from the page bundle");
            resp = sendChart(xid, days, fresh);
        }
        if (resp.statusCode() != 200) {
            LOG.debug("[ASX] chartworks status {} for xid {}", resp.statusCode(), xid);
            return null;
        }
        return resp.body();
    }

    private HttpResponse<String> sendChart(String xid, int days, String token)
            throws Exception {
        String body = String.format(Locale.ROOT, """
                {"days":%d,"dataNormalized":false,"dataPeriod":"Day","dataInterval":1,\
                "realtime":false,"yFormat":"0.###","timeServiceFormat":"JSON",\
                "returnDateType":"ISO8601","elements":[\
                {"Label":"price","Type":"price","Symbol":"%s","OverlayIndicators":[],"Params":{}},\
                {"Label":"volume","Type":"volume","Symbol":"%s","OverlayIndicators":[],"Params":{}}]}""",
                days, xid, xid);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(String.format(CHART_URL, token)))
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .header("Origin", ASX_ORIGIN)
                .header("Referer", ASX_ORIGIN + "/")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** The current token off the live page bundle, {@code null} when unreachable. */
    private String scrapeToken() {
        try {
            String page = get(TOKEN_PAGE_URL);
            if (page == null) return null;
            Matcher path = BUNDLE_PATH.matcher(page);
            if (!path.find()) return null;
            String bundle = get(ASX_ORIGIN + path.group());
            return bundle == null ? null : extractToken(bundle);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[ASX] token scrape failed: {}", e.getMessage());
            return null;
        }
    }

    /** The 32-hex API token out of the bundle source. Package-private for tests. */
    static String extractToken(String js) {
        if (js == null) return null;
        Matcher m = API_TOKEN.matcher(js);
        return m.find() ? m.group(1) : null;
    }

    /** Merges header + key-statistics replies. Package-private for tests. */
    static Optional<AsxQuote> parseQuote(String headerJson, String keyStatsJson) {
        if (headerJson == null || headerJson.isBlank()) return Optional.empty();
        try {
            JsonNode h = MAPPER.readTree(headerJson).path("data");
            String symbol = text(h, "symbol");
            String xid = text(h, "xid");
            double last = num(h, "priceLast");
            if (symbol == null || xid == null
                    || !Double.isFinite(last) || last <= 0) {
                return Optional.empty();
            }
            JsonNode k = MAPPER.missingNode();
            if (keyStatsJson != null && !keyStatsJson.isBlank()) {
                try {
                    k = MAPPER.readTree(keyStatsJson).path("data");
                } catch (Exception e) {
                    LOG.debug("[ASX] key-statistics unreadable: {}", e.getMessage());
                }
            }
            return Optional.of(new AsxQuote(symbol,
                    text(h, "displayName"), xid,
                    last, num(h, "priceBid"), num(h, "priceAsk"),
                    count(h, "volume"), count(h, "marketCap"),
                    text(k, "isin"), num(k, "volumeAverage"),
                    num(k, "earningsPerShare"), num(k, "dividend")));
        } catch (Exception e) {
            LOG.warn("[ASX] unparseable header payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Walks the chartworks reply: one shared {@code Dates} axis, one price
     * element (Open/High/Low/Close series) and one volume element, paired by
     * index. Package-private for tests.
     */
    static Optional<AsxHistory> parseHistory(String symbol, String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode dates = root.path("Dates");
            if (!dates.isArray() || dates.isEmpty()) return Optional.empty();
            JsonNode open = null, high = null, low = null, close = null, volume = null;
            String xid = null, currency = null;
            for (JsonNode el : root.path("Elements")) {
                String type = text(el, "Type");
                if ("price".equals(type)) {
                    xid = text(el, "Symbol");
                    currency = text(el, "Currency");
                    for (JsonNode s : el.path("ComponentSeries")) {
                        switch (String.valueOf(text(s, "Type"))) {
                            case "Open" -> open = s.path("Values");
                            case "High" -> high = s.path("Values");
                            case "Low" -> low = s.path("Values");
                            case "Close" -> close = s.path("Values");
                        }
                    }
                } else if ("volume".equals(type)) {
                    for (JsonNode s : el.path("ComponentSeries")) {
                        if ("Volume".equals(text(s, "Type"))) volume = s.path("Values");
                    }
                }
            }
            if (close == null || xid == null) return Optional.empty();
            List<AsxHistory.AsxBar> bars = new ArrayList<>(dates.size());
            for (int i = 0; i < dates.size(); i++) {
                LocalDate date = barDate(dates.get(i).asText(""));
                double c = at(close, i);
                if (date == null || !Double.isFinite(c) || c <= 0) continue;
                bars.add(new AsxHistory.AsxBar(date, at(open, i), at(high, i),
                        at(low, i), c, at(volume, i)));
            }
            if (bars.isEmpty()) return Optional.empty();
            bars.sort((a, b) -> a.date().compareTo(b.date()));
            return Optional.of(new AsxHistory(symbol, xid, currency, List.copyOf(bars)));
        } catch (Exception e) {
            LOG.warn("[ASX] unparseable chart payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static LocalDate barDate(String iso) {
        if (iso == null || iso.length() < 10) return null;
        try {
            return LocalDate.parse(iso.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static double at(JsonNode values, int i) {
        if (values == null || !values.isArray() || i >= values.size()) return Double.NaN;
        JsonNode v = values.get(i);
        return v == null || !v.isNumber() ? Double.NaN : v.asDouble();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    /** A string- or number-typed decimal, {@code NaN} when absent/unreadable. */
    private static double num(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return Double.NaN;
        if (v.isNumber()) return v.asDouble();
        try {
            String s = v.asText("").trim().replace(",", "");
            return s.isEmpty() ? Double.NaN : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** A string- or number-typed count, {@code -1} when absent/unreadable. */
    private static long count(JsonNode n, String field) {
        double v = num(n, field);
        return Double.isFinite(v) && v >= 0 ? Math.round(v) : -1;
    }
}
