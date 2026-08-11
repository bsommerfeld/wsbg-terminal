package de.bsommerfeld.wsbg.terminal.hkex;

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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Hong Kong Exchange website's own widget service
 * ({@code www1.hkex.com.hk/hkexwidget/data/...}, probed 2026-08-05): the
 * exchange's home tape for its listings - live quote (last, day range,
 * volume, turnover, market cap) and roughly TEN YEARS of daily OHLCV bars.
 * Keyless, but behind a small token dance: the quote PAGE embeds a session
 * token in its {@code LabCI.getToken} script, which every widget call must
 * carry. The token is cached per session and re-pulled ONCE when a call
 * comes back rejected.
 *
 * <p>The replies are JSONP ({@code cb({...})} - the {@code callback=}
 * parameter is mandatory, without it the service answers nothing), with
 * numbers as comma-grouped strings and volume/turnover/market cap as
 * unit-suffixed pairs ({@code "vo":"25.66"} + {@code "vo_u":"M"}) - all
 * parsed defensively. A rejected token arrives as an HTML error page, not
 * as JSON.
 *
 * <p>Board numbers only: {@code 700} or the Yahoo form {@code 0700.HK} -
 * anything else is a network-free no-op, same gate the NYSE client keeps.
 * The quote endpoint wants the bare number ({@code sym=700}), the chart
 * endpoint insists on the zero-padded RIC ({@code ric=0700.HK}) - unpadded
 * it answers politely with no bars at all.
 */
@Singleton
public class HkexClient {

    private static final Logger LOG = LoggerFactory.getLogger(HkexClient.class);

    static final String PAGE_URL =
            "https://www.hkex.com.hk/Market-Data/Securities-Prices/Equities/Equities-Quote?sym=%s&sc_lang=en";
    static final String QUOTE_URL =
            "https://www1.hkex.com.hk/hkexwidget/data/getequityquote?sym=%s&token=%s&lang=eng&qid=1&callback=cb";
    static final String CHART_URL =
            "https://www1.hkex.com.hk/hkexwidget/data/getchartdata2?hchart=1&span=6&int=8&ric=%s&token=%s&qid=1&callback=cb";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Bare HK board number, Yahoo suffix tolerated ({@code 0700.HK}). */
    private static final Pattern HK_SYMBOL = Pattern.compile("\\d{1,5}(\\.HK)?");
    /** The {@code LabCI.getToken} script block on the quote page. */
    private static final Pattern TOKEN_BLOCK = Pattern.compile(
            "getToken\\s*=\\s*function\\s*\\(\\)\\s*\\{(.*?)\\}", Pattern.DOTALL);
    /**
     * The LIVE return inside the block - anchored to the line start, because
     * the page keeps a commented-out sample return right above the real one.
     */
    private static final Pattern TOKEN_RETURN =
            Pattern.compile("(?m)^\\s*return\\s*\"([^\"]+)\"");
    /** Bar epochs are midnight at the exchange, never the JVM's zone. */
    private static final ZoneId HONG_KONG = ZoneId.of("Asia/Hong_Kong");

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    /** The token page is a full ~370 KB website - give it room. */
    private final Duration requestTimeout = Duration.ofSeconds(20);

    private volatile String token;

    /** Test/default: plain direct transport. */
    public HkexClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public HkexClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The exchange's live quote for one HK board number.
     *
     * @return the quote, or empty when the symbol is not HK-shaped, unknown
     *         or the service failed - never {@code null}
     */
    public Optional<HkexQuote> quote(String symbol) {
        String sym = bareNumber(symbol);
        if (sym == null) return Optional.empty();
        String body = withToken(t -> String.format(QUOTE_URL, sym, t));
        Optional<HkexQuote> quote = parseQuote(body);
        quote.ifPresent(q -> LOG.info(
                "[HKEX] {} ({}) → last={} {} hi={} lo={} vol={} turnover={} mktCap={}",
                q.symbol(), q.name(), q.last(), q.currency(), q.dayHigh(),
                q.dayLow(), q.volume(), q.turnover(), q.marketCap()));
        return quote;
    }

    /**
     * Roughly ten years of daily bars for one HK board number.
     *
     * @return the history, oldest bar first, or empty when the symbol is not
     *         HK-shaped, unknown or the service failed - never {@code null}
     */
    public Optional<HkexHistory> history(String symbol) {
        String sym = bareNumber(symbol);
        if (sym == null) return Optional.empty();
        String ric = paddedRic(sym);
        String body = withToken(t -> String.format(CHART_URL, ric, t));
        Optional<HkexHistory> history = parseHistory(ric, body);
        history.ifPresent(h -> LOG.info("[HKEX] {} → {} daily bars ({} … {})",
                h.ric(), h.bars().size(), h.bars().get(0).date(),
                h.bars().get(h.bars().size() - 1).date()));
        return history;
    }

    /**
     * One widget call under the cached session token; a rejected reply
     * (HTML error page or a non-{@code 000} response code) buys exactly ONE
     * fresh token and one retry.
     */
    private String withToken(java.util.function.UnaryOperator<String> url) {
        try {
            String t = token;
            if (t != null) {
                String body = get(url.apply(t));
                if (accepted(body)) return body;
                LOG.debug("[HKEX] cached token rejected - pulling a fresh one");
            }
            t = pullToken();
            if (t == null) return null;
            token = t;
            String body = get(url.apply(t));
            return accepted(body) ? body : null;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[HKEX] fetch failed: {}", e.getMessage());
            return null;
        }
    }

    private String get(String url) throws Exception {
        WebResponse resp = fetcher.fetch(url,
                Map.of("User-Agent", userAgent, "Accept", "*/*"),
                requestTimeout);
        if (resp == null || resp.status() != 200) {
            LOG.debug("[HKEX] status {} for {}",
                    resp == null ? "null" : resp.status(), url);
            return null;
        }
        return resp.body();
    }

    /** Pulls the session token off the quote page's widget bootstrap. */
    private String pullToken() throws Exception {
        String html = get(String.format(PAGE_URL, "700"));
        String t = extractToken(html);
        if (t == null) LOG.warn("[HKEX] no token on the quote page - layout changed?");
        return t;
    }

    /**
     * The {@code LabCI.getToken} value - already URL-encoded by the page,
     * taken verbatim. Package-private for tests.
     */
    static String extractToken(String html) {
        if (html == null) return null;
        Matcher block = TOKEN_BLOCK.matcher(html);
        if (!block.find()) return null;
        Matcher ret = TOKEN_RETURN.matcher(block.group(1));
        return ret.find() ? ret.group(1) : null;
    }

    /** A widget reply the token was good for - anything else buys a retry. */
    static boolean accepted(String body) {
        return body != null && body.contains("\"responsecode\":\"000\"");
    }

    /** {@code cb({...})} → {@code {...}}, {@code null} when not JSONP-shaped. */
    static String stripJsonp(String body) {
        if (body == null) return null;
        int open = body.indexOf('(');
        int close = body.lastIndexOf(')');
        if (open < 0 || close <= open) return null;
        return body.substring(open + 1, close);
    }

    /**
     * {@code 700}, {@code 0700} or {@code 0700.HK} → {@code "700"};
     * anything not HK-shaped → {@code null}. Package-private for tests.
     */
    static String bareNumber(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (!HK_SYMBOL.matcher(s).matches()) return null;
        if (s.endsWith(".HK")) s = s.substring(0, s.length() - 3);
        s = s.replaceFirst("^0+", "");
        return s.isEmpty() ? null : s;
    }

    /** {@code 700} → {@code 0700.HK} - the chart endpoint's only dialect. */
    static String paddedRic(String bareNumber) {
        StringBuilder s = new StringBuilder(bareNumber);
        while (s.length() < 4) s.insert(0, '0');
        return s + ".HK";
    }

    /** Parses the quote reply's JSONP body. Package-private for tests. */
    static Optional<HkexQuote> parseQuote(String body) {
        String json = stripJsonp(body);
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode q = MAPPER.readTree(json).path("data").path("quote");
            String symbol = text(q, "sym");
            double last = num(q, "ls");
            if (symbol == null || !Double.isFinite(last) || last <= 0) {
                return Optional.empty();
            }
            double volume = scaled(q, "vo", "vo_u");
            return Optional.of(new HkexQuote(symbol,
                    text(q, "nm"), text(q, "ccy"),
                    last, num(q, "pc"),
                    num(q, "hi"), num(q, "lo"),
                    Double.isFinite(volume) && volume >= 0 ? Math.round(volume) : -1,
                    scaled(q, "am", "am_u"),
                    scaled(q, "mkt_cap", "mkt_cap_u")));
        } catch (Exception e) {
            LOG.warn("[HKEX] unparseable quote payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Parses the chart reply's JSONP body. Package-private for tests. */
    static Optional<HkexHistory> parseHistory(String ric, String body) {
        String json = stripJsonp(body);
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            List<HkexHistory.HkexBar> bars = new ArrayList<>();
            for (JsonNode row : MAPPER.readTree(json).path("data").path("datalist")) {
                // [epochMs, open, high, low, close, volume, turnover] - the
                // series is padded with all-null placeholder rows at both ends.
                if (!row.isArray() || row.size() < 6) continue;
                double close = row.path(4).isNumber() ? row.path(4).asDouble() : Double.NaN;
                if (!row.path(0).isNumber() || !Double.isFinite(close) || close <= 0) continue;
                LocalDate date = Instant.ofEpochMilli(row.path(0).asLong())
                        .atZone(HONG_KONG).toLocalDate();
                bars.add(new HkexHistory.HkexBar(date,
                        barNum(row, 1), barNum(row, 2), barNum(row, 3), close,
                        barNum(row, 5)));
            }
            if (bars.isEmpty()) return Optional.empty();
            bars.sort((a, b) -> a.date().compareTo(b.date()));
            return Optional.of(new HkexHistory(ric, List.copyOf(bars)));
        } catch (Exception e) {
            LOG.warn("[HKEX] unparseable chart payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static double barNum(JsonNode row, int index) {
        JsonNode v = row.path(index);
        return v.isNumber() ? v.asDouble() : Double.NaN;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    /** A comma-grouped string decimal, {@code NaN} when absent/unreadable. */
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

    /**
     * A value + unit-suffix pair as the widget quotes big numbers
     * ({@code "vo":"25.66"} + {@code "vo_u":"M"}), fully scaled out.
     */
    private static double scaled(JsonNode n, String field, String unitField) {
        double v = num(n, field);
        if (!Double.isFinite(v)) return Double.NaN;
        String u = text(n, unitField);
        if (u == null) return v;
        return switch (u.toUpperCase(Locale.ROOT)) {
            case "K" -> v * 1e3;
            case "M" -> v * 1e6;
            case "B" -> v * 1e9;
            default -> v;
        };
    }
}
