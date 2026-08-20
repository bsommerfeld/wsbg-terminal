package de.bsommerfeld.wsbg.terminal.web.impl.sources.nyse;

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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The NYSE website's own quote service
 * ({@code nyse.com/api/nyseservice/v1/quotes?symbol=<T>}, probed 2026-08-05):
 * ONE keyless call answers the live quote (last, bid/ask WITH sizes, day
 * range, volume, average volume, 52-week range, dividend, beta, EPS) plus
 * roughly FIVE YEARS of daily OHLCV bars - the exchange's own tape, and it
 * answers for Nasdaq listings too ({@code exchg:"NASD"}). This is the first
 * exchange website this house taps directly rather than through an
 * aggregator (user mandate 2026-08-05).
 *
 * <p>The reply also carries the full options chain (~3,600 rows) which this
 * client deliberately never parses - the tree is read once and only the
 * quote and history branches are walked. Numbers arrive as STRINGS
 * ({@code "309.140000"}), parsed defensively.
 *
 * <p>Bare US tickers only: a suffixed ({@code RHM.DE}), index or crypto
 * symbol is a network-free no-op, same gate the NASDAQ company client keeps.
 */
@Singleton
public class NyseQuoteClient {

    private static final Logger LOG = LoggerFactory.getLogger(NyseQuoteClient.class);

    static final String QUOTE_URL = "https://www.nyse.com/api/nyseservice/v1/quotes?symbol=%s";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Bare US symbol, class shares included ({@code BRK.B}) - nothing suffixed. */
    private static final Pattern US_TICKER = Pattern.compile("[A-Z]{1,5}(\\.[A-Z])?");
    private static final DateTimeFormatter BAR_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd");
    /** The chain writes its expiries out in words, in the service's own locale. */
    private static final DateTimeFormatter EXPIRY_DATE =
            DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy", Locale.US);

    private static final FetchUtil[] MODES = {FetchUtil.BROWSER, FetchUtil.DIRECT};

    private final WebFetcher fetcher;
    /** The reply carries five years of bars and an options chain - give it room. */
    private final Duration requestTimeout = Duration.ofSeconds(20);

    @Inject
    public NyseQuoteClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The home tape for one bare US ticker.
     *
     * @return the tape, or empty when the symbol is not US-shaped, unknown or
     *         the endpoint failed - never {@code null}
     */
    public Optional<NyseTape> tape(String symbol) {
        if (symbol == null) return Optional.empty();
        String t = symbol.trim().toUpperCase(Locale.ROOT);
        if (!US_TICKER.matcher(t).matches()) return Optional.empty();
        try {
            WebResponse resp = fetcher.fetch(String.format(QUOTE_URL, t),
                    Map.of("Accept", "application/json"),
                    requestTimeout, MODES);
            if (resp == null || resp.status() != 200) {
                LOG.debug("[NYSE] status {} for {}",
                        resp == null ? "null" : resp.status(), t);
                return Optional.empty();
            }
            Optional<NyseTape> tape = parse(resp.body());
            tape.ifPresent(v -> LOG.info(
                    "[NYSE] {} ({}) → last={} bid={}x{} ask={}x{} vol={} avgVol={} bars={}",
                    v.symbol(), v.exchange(), v.last(), v.bid(), v.bidSize(),
                    v.ask(), v.askSize(), v.volume(), v.averageVolume(),
                    v.history().size()));
            return tape;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[NYSE] fetch for {} failed: {}", t, e.getMessage());
            return Optional.empty();
        }
    }

    /** Parses the service reply's quote + history branches. Package-private for tests. */
    static Optional<NyseTape> parse(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode q = root.path("quote");
            String symbol = text(q, "dispname");
            double last = num(q, "last");
            if (symbol == null || !Double.isFinite(last) || last <= 0) {
                return Optional.empty();
            }
            List<NyseTape.DailyBar> bars = new ArrayList<>();
            for (JsonNode b : root.path("quoteHistory").path("historyList")) {
                LocalDate date = barDate(text(b, "date"));
                double close = num(b, "close");
                if (date == null || !Double.isFinite(close) || close <= 0) continue;
                bars.add(new NyseTape.DailyBar(date, num(b, "open"), num(b, "high"),
                        num(b, "low"), close, count(b, "volume")));
            }
            bars.sort((a, b) -> a.date().compareTo(b.date()));
            List<NyseTape.OptionContract> chain = new ArrayList<>();
            chain(root.path("options").path("callList"), true, chain);
            chain(root.path("options").path("putList"), false, chain);
            return Optional.of(new NyseTape(symbol,
                    text(q, "desc"), text(q, "exchg"),
                    last, num(q, "pctchg"),
                    num(q, "bid"), num(q, "ask"),
                    count(q, "bidSize"), count(q, "askSize"),
                    num(q, "open"), num(q, "high"), num(q, "low"), num(q, "prev"),
                    count(q, "volume"), count(q, "aveVol"),
                    num(q, "annHigh"), num(q, "annLow"),
                    num(q, "dividend"), num(q, "divYield"), num(q, "beta"), num(q, "eps"),
                    List.copyOf(bars), List.copyOf(chain)));
        } catch (Exception e) {
            LOG.warn("[NYSE] unparseable payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Reads one side of the options chain. The service ships the whole book
     * (roughly 1,400 rows) inside the SAME reply as the quote, so the chain is
     * free once the tape is fetched - a contract row with neither volume nor
     * open interest carries no information and is dropped.
     */
    private static void chain(JsonNode list, boolean call,
            List<NyseTape.OptionContract> out) {
        for (JsonNode c : list) {
            long volume = count(c, "volume");
            long openInterest = count(c, "openInt");
            if (volume <= 0 && openInterest <= 0) continue;
            out.add(new NyseTape.OptionContract(call, expiry(text(c, "exp")),
                    num(c, "strikeString"), volume, openInterest));
        }
    }

    /** The chain's expiry format ({@code "Friday, August 07, 2026"}). */
    private static LocalDate expiry(String s) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s, EXPIRY_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate barDate(String s) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s, BAR_DATE);
        } catch (Exception e) {
            return null;
        }
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
