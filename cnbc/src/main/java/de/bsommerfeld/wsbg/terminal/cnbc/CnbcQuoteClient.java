package de.bsommerfeld.wsbg.terminal.cnbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CNBC's quote and earnings web services - the keyless MARKET-DATA leg beside
 * the news client (all probed 2026-08-02, no key, no cookie, no crumb).
 *
 * <p>This closes the gap Yahoo left: {@code v10/quoteSummary} is crumb-locked,
 * but CNBC's {@code restQuote} answers a bare client with the same class of
 * fundamentals - and it does so for EVERY asset class in ONE pipe-separated
 * call. Verified live: {@code AAPL} (NASDAQ), {@code SAP-DE} (XETRA, quoted in
 * EUR), {@code .SPX} (index), {@code @CL.1} (WTI future), {@code EUR=} (FX),
 * {@code US10Y} (bond yield), {@code BTC.CM=} (crypto index).
 *
 * <p>Two services:
 * <ul>
 *   <li>{@link #quotes(List)} - price, fundamentals, pre/post-market and the
 *       next earnings / ex-dividend dates per symbol.</li>
 *   <li>{@link #earnings(String, int)} - up to eight quarters with the ESTIMATE
 *       <em>and</em> the ACTUAL for EPS, revenue and income, so a surprise is a
 *       subtraction rather than a second source.</li>
 * </ul>
 *
 * <p><b>Everything here is formatted for display, not for maths.</b> CNBC ships
 * {@code "7,489.72"}, {@code "4.514T"}, {@code "0.35%"} and {@code "4.718%"} as
 * strings; {@link #number(String)} normalises them (thousands separators,
 * percent signs, K/M/B/T magnitude suffixes) and yields {@code null} rather
 * than a wrong zero when a field is absent or unparseable - a missing P/E must
 * never read as a P/E of nothing.
 *
 * <p>Nothing in the terminal calls this yet. It is wired as a ready surface so
 * the market-data leg can be mobilised without reopening the source (house
 * mandate 2026-08-02: every source is connected both per instrument and
 * generally).
 */
@Singleton
public class CnbcQuoteClient {

    private static final Logger LOG = LoggerFactory.getLogger(CnbcQuoteClient.class);

    static final String QUOTE_URL =
            "https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol"
                    + "?symbols=%s&requestMethod=itv&noform=1&partnerId=2"
                    + "&fund=1&exthrs=1&output=json&events=1";

    static final String EARNINGS_URL =
            "https://gds-earnings.cnbc.com/earnings-service/symbol/%s/lastQtr/%d"
                    + "/output/json?fiscalYear=true";

    /** Movers: {@code rank} is e.g. VOLUME or PCT_10_VOL, {@code direction} TOP/BOTTOM. */
    static final String MOVERS_URL =
            "https://gdsapi.cnbc.com/market-mover/groupMover/EXCH_US/%s/%s/5.json"
                    + "?delayed=false&partnerId=2";

    /** CNBC caps a single restQuote call; batches larger than this are split. */
    static final int MAX_SYMBOLS_PER_CALL = 25;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter US_DATE =
            DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US);

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public CnbcQuoteClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: {@code @DirectFirst}. These are keyless JSON services with no
     * wall - routing them through the browser joker first would pay a 15-25 s
     * warmup for nothing (the {@code NetModule} carve-out rationale).
     */
    @Inject
    public CnbcQuoteClient(@DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    // ------------------------------------------------------------------
    // quotes
    // ------------------------------------------------------------------

    /**
     * Quotes for any mix of asset classes, keyed by the symbol as CNBC spells
     * it. Symbol shapes: plain {@code AAPL} for US equity, {@code SAP-DE} for
     * XETRA, {@code .SPX} for an index, {@code @CL.1} for a front-month future,
     * {@code EUR=} for FX, {@code US10Y} for a benchmark yield.
     *
     * <p>Batches over {@link #MAX_SYMBOLS_PER_CALL} are split across calls and
     * merged; a failing batch drops out instead of failing the whole request.
     */
    public Map<String, Quote> quotes(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return Map.of();
        Map<String, Quote> out = new LinkedHashMap<>();
        for (int i = 0; i < symbols.size(); i += MAX_SYMBOLS_PER_CALL) {
            List<String> batch = symbols.subList(i,
                    Math.min(i + MAX_SYMBOLS_PER_CALL, symbols.size()));
            String joined = URLEncoder.encode(String.join("|", batch), StandardCharsets.UTF_8);
            String body = get(String.format(QUOTE_URL, joined));
            if (body != null) {
                for (Quote q : parseQuotes(body)) {
                    out.put(q.symbol(), q);
                }
            }
        }
        return Map.copyOf(out);
    }

    /** Convenience for the single-symbol case; {@code null} when unknown. */
    public Quote quote(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        return quotes(List.of(symbol.trim())).get(symbol.trim());
    }

    /** Parses {@code FormattedQuoteResult.FormattedQuote[]}. Package-private for tests. */
    static List<Quote> parseQuotes(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Quote> out = new ArrayList<>();
        try {
            JsonNode arr = MAPPER.readTree(json)
                    .path("FormattedQuoteResult").path("FormattedQuote");
            if (arr.isObject()) arr = MAPPER.createArrayNode().add(arr); // single-symbol shape
            for (JsonNode n : arr) {
                String symbol = str(n, "symbol");
                if (symbol == null) continue;
                JsonNode ext = n.path("ExtendedMktQuote");
                JsonNode ev = n.path("EventData");
                out.add(new Quote(
                        symbol,
                        str(n, "name"),
                        str(n, "shortName"),
                        number(str(n, "last")),
                        str(n, "currencyCode"),
                        number(str(n, "change")),
                        number(str(n, "change_pct")),
                        number(str(n, "previous_day_closing")),
                        number(str(n, "open")),
                        number(str(n, "high")),
                        number(str(n, "low")),
                        number(str(n, "volume")),
                        str(n, "exchange"),
                        str(n, "type"),
                        str(n, "curmktstatus"),
                        number(str(n, "mktcapView")),
                        number(str(n, "pe")),
                        number(str(n, "fpe")),
                        number(str(n, "eps")),
                        number(str(n, "feps")),
                        number(str(n, "beta")),
                        number(str(n, "dividendyield")),
                        number(str(n, "dividend")),
                        number(str(n, "sharesout")),
                        number(str(n, "revenuettm")),
                        number(str(n, "yrhiprice")),
                        number(str(n, "yrloprice")),
                        number(str(n, "ROETTM")),
                        number(str(n, "NETPROFTTM")),
                        number(str(n, "GROSMGNTTM")),
                        number(str(n, "DEBTEQTYQ")),
                        ext.isMissingNode() || ext.isNull() ? null : new ExtendedQuote(
                                str(ext, "type"),
                                number(str(ext, "last")),
                                number(str(ext, "change")),
                                number(str(ext, "change_pct")),
                                number(str(ext, "volume"))),
                        usDate(str(ev, "next_earnings_date")),
                        usDate(str(ev, "div_ex_date")),
                        "Y".equalsIgnoreCase(str(ev, "is_halted"))));
            }
        } catch (Exception e) {
            LOG.warn("Unparseable CNBC quote payload: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    // ------------------------------------------------------------------
    // earnings
    // ------------------------------------------------------------------

    /**
     * The last {@code quarters} reported quarters (max 8 in practice) with
     * estimate AND actual for EPS, revenue and income - CNBC's own
     * {@code surprise} verdict rides along.
     */
    public List<EarningsQuarter> earnings(String symbol, int quarters) {
        if (symbol == null || symbol.isBlank() || quarters <= 0) return List.of();
        String body = get(String.format(EARNINGS_URL,
                URLEncoder.encode(symbol.trim().toUpperCase(Locale.ROOT), StandardCharsets.UTF_8),
                quarters));
        return body == null ? List.of() : parseEarnings(body);
    }

    /** Parses {@code earnings.lstEarningsBean[]}. Package-private for tests. */
    static List<EarningsQuarter> parseEarnings(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<EarningsQuarter> out = new ArrayList<>();
        try {
            for (JsonNode n : MAPPER.readTree(json).path("earnings").path("lstEarningsBean")) {
                out.add(new EarningsQuarter(
                        str(n, "cnbcTicker"),
                        n.path("fiscalYear").isMissingNode() ? 0 : n.path("fiscalYear").asInt(),
                        n.path("qtrId").isMissingNode() ? 0 : n.path("qtrId").asInt(),
                        epochMillis(n.path("announcedDate")),
                        dbl(n, "epsEstimatedValue"),
                        dbl(n, "epsAdjActualValue"),
                        dbl(n, "epsGaapActualValue"),
                        dbl(n, "revenueEstimatedValue"),
                        dbl(n, "revenueActualValue"),
                        dbl(n, "incomeEstimatedValue"),
                        dbl(n, "incomeActualValue"),
                        str(n, "surprise"),
                        dbl(n, "perChgEps"),
                        "B".equalsIgnoreCase(str(n, "beforeAfterMarket"))));
            }
        } catch (Exception e) {
            LOG.warn("Unparseable CNBC earnings payload: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    // ------------------------------------------------------------------
    // movers (general stream - no instrument needed)
    // ------------------------------------------------------------------

    /**
     * GENERAL stream: US market movers, ranked by {@code rankBy} (e.g.
     * {@code VOLUME}, {@code PCT_10_VOL}); {@code direction} is {@code TOP} or
     * {@code BOTTOM}. Carries pre- and post-market change per row, so it also
     * answers "what moved before the bell".
     *
     * <p>Currently unused - stands ready (see class javadoc).
     */
    public List<Mover> movers(String rankBy, String direction) {
        if (rankBy == null || rankBy.isBlank()) return List.of();
        String body = get(String.format(MOVERS_URL,
                rankBy.trim().toUpperCase(Locale.ROOT),
                direction == null || direction.isBlank()
                        ? "TOP" : direction.trim().toUpperCase(Locale.ROOT)));
        return body == null ? List.of() : parseMovers(body);
    }

    /**
     * Parses {@code rankedSymbolList[].rankedSymbols[]}. Unlike restQuote this
     * service ships REAL numbers rather than display strings, so the values are
     * read straight off the node. Package-private for tests.
     */
    static List<Mover> parseMovers(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Mover> out = new ArrayList<>();
        try {
            for (JsonNode group : MAPPER.readTree(json).path("rankedSymbolList")) {
                for (JsonNode n : group.path("rankedSymbols")) {
                    String symbol = str(n, "cnbcSymbol");
                    if (symbol == null) continue;
                    JsonNode d = n.path("symbolData");
                    out.add(new Mover(
                            symbol,
                            str(n, "symbolDesc"),
                            dbl(d, "lastPrice"),
                            dbl(d, "changePct"),
                            dbl(d, "volume"),
                            dbl(d, "changePre"),
                            dbl(d, "changeAfter"),
                            dbl(d, "peRatio"),
                            dbl(d, "impliedVolatality"),
                            dbl(d, "pctTenDayVol")));
                }
            }
        } catch (Exception e) {
            LOG.warn("Unparseable CNBC movers payload: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private String get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("CNBC service {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("CNBC service {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /**
     * CNBC's display numbers → a real double. Handles thousands separators
     * ({@code "7,489.72"}), percent signs ({@code "4.718%"}) and magnitude
     * suffixes ({@code "4.514T"}, {@code "5.2M"}). Returns {@code null} for
     * anything absent, dashed or unparseable - never a substitute zero.
     * Package-private for tests.
     */
    static Double number(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.equals("-") || s.equals("--") || s.equalsIgnoreCase("n/a")
                || s.equalsIgnoreCase("unch")) {
            return null;
        }
        s = s.replace(",", "").replace("%", "").replace("+", "").trim();
        double factor = 1;
        if (!s.isEmpty()) {
            char suffix = s.charAt(s.length() - 1);
            switch (Character.toUpperCase(suffix)) {
                case 'K' -> factor = 1_000d;
                case 'M' -> factor = 1_000_000d;
                case 'B' -> factor = 1_000_000_000d;
                case 'T' -> factor = 1_000_000_000_000d;
                default -> { /* no magnitude suffix */ }
            }
            if (factor != 1) s = s.substring(0, s.length() - 1);
        }
        try {
            return Double.parseDouble(s.trim()) * factor;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code "10/28/2026(est)"} → the date; estimate marker and junk tolerated. */
    static LocalDate usDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        int paren = s.indexOf('(');
        if (paren > 0) s = s.substring(0, paren).trim();
        try {
            return LocalDate.parse(s, US_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant epochMillis(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        long v = n.asLong(0);
        return v > 0 ? Instant.ofEpochMilli(v) : null;
    }

    private static String str(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static Double dbl(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isNumber()) return null;
        return v.asDouble();
    }

    // ------------------------------------------------------------------
    // records
    // ------------------------------------------------------------------

    /**
     * One quoted instrument. Every numeric field is nullable on purpose: an
     * index carries no P/E, an FX pair no market cap, and a null says exactly
     * that instead of pretending a zero.
     */
    public record Quote(
            String symbol,
            String name,
            String shortName,
            Double last,
            String currency,
            Double change,
            Double changePercent,
            Double previousClose,
            Double open,
            Double dayHigh,
            Double dayLow,
            Double volume,
            String exchange,
            String type,
            String marketStatus,
            Double marketCap,
            Double peTrailing,
            Double peForward,
            Double epsTrailing,
            Double epsForward,
            Double beta,
            Double dividendYieldPercent,
            Double dividendPerShare,
            Double sharesOutstanding,
            Double revenueTtm,
            Double yearHigh,
            Double yearLow,
            Double returnOnEquityPercent,
            Double netProfitMarginPercent,
            Double grossMarginPercent,
            Double debtToEquity,
            ExtendedQuote extended,
            LocalDate nextEarningsDate,
            LocalDate exDividendDate,
            boolean halted) {}

    /** Pre- or post-market print; {@code type} is e.g. {@code POST_MKT}. */
    public record ExtendedQuote(
            String type, Double last, Double change, Double changePercent, Double volume) {}

    /**
     * One reported quarter with both sides of the comparison. {@code surprise}
     * is CNBC's own verdict ({@code UP}/{@code DOWN}); the numbers are there to
     * recompute it rather than trust it.
     */
    public record EarningsQuarter(
            String symbol,
            int fiscalYear,
            int quarter,
            Instant announcedAt,
            Double epsEstimate,
            Double epsActualAdjusted,
            Double epsActualGaap,
            Double revenueEstimate,
            Double revenueActual,
            Double incomeEstimate,
            Double incomeActual,
            String surprise,
            Double epsChangePercent,
            boolean beforeMarket) {}

    /**
     * One row of the US movers board. Carries the pre- and after-hours move
     * beside the session move, plus the two ratios that make a mover readable
     * without a second lookup: implied volatility and today's volume against
     * the ten-day average.
     */
    public record Mover(
            String symbol,
            String name,
            Double lastPrice,
            Double changePercent,
            Double volume,
            Double changePreMarket,
            Double changeAfterHours,
            Double peRatio,
            Double impliedVolatility,
            Double volumeVsTenDayAverage) {}
}
