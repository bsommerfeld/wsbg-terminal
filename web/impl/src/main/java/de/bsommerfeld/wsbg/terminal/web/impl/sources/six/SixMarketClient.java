package de.bsommerfeld.wsbg.terminal.web.impl.sources.six;

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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The SIX Swiss Exchange website's own market data services (probed
 * 2026-08-05): two keyless calls cover the Swiss home tape. {@code
 * fqs/snap.json} answers the delayed quote as COLUMN-oriented JSON
 * ({@code colNames} + {@code rowData}), {@code itf/fqs/delayed/charts.json}
 * answers a DECADE of daily OHLCV bars as parallel arrays under {@code
 * valors[].data} - the charting endpoint exists ONLY under the {@code /itf}
 * prefix. Both are 15-minute delayed ({@code delayMinutes:15}) and carry
 * volume in SHARES, not turnover.
 *
 * <p>Dates arrive as {@code yyyyMMdd} integers, prices as real JSON numbers -
 * no string-number gymnastics needed, still parsed defensively.
 *
 * <p>CH ISINs only: anything not shaped {@code CH.........X} is a
 * network-free no-op, same gate the NYSE client keeps for its symbols.
 */
@Singleton
public class SixMarketClient {

    private static final Logger LOG = LoggerFactory.getLogger(SixMarketClient.class);

    static final String QUOTE_URL = "https://www.six-group.com/fqs/snap.json"
            + "?select=ShortName,ISIN,ValorSymbol,ClosingPrice,TotalVolume,"
            + "DailyHighPrice,DailyLowPrice,PreviousClosingPrice"
            + "&where=ISIN=%s&pagesize=5";
    static final String CHARTS_URL = "https://www.six-group.com/itf/fqs/delayed/charts.json"
            + "?select=ISIN&where=ISIN=%s"
            + "&columns=Date,Time,Close,Open,High,Low,TotalVolume"
            + "&netting=1440&fromdate=%s";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** A Swiss ISIN - the only shape this home-exchange client answers for. */
    private static final Pattern CH_ISIN = Pattern.compile("CH[0-9A-Z]{9}[0-9]");
    private static final DateTimeFormatter YYYYMMDD =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final FetchUtil[] MODES = {FetchUtil.BROWSER, FetchUtil.DIRECT};

    private final WebFetcher fetcher;
    /** A decade of bars in one reply - give it room. */
    private final Duration requestTimeout = Duration.ofSeconds(20);

    @Inject
    public SixMarketClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The home quote for one Swiss ISIN.
     *
     * @return the quote, or empty when the ISIN is not CH-shaped, unknown or
     *         the endpoint failed - never {@code null}
     */
    public Optional<SixQuote> quote(String isin) {
        String id = chIsin(isin);
        if (id == null) return Optional.empty();
        Optional<SixQuote> quote = get(String.format(QUOTE_URL, id), id)
                .flatMap(SixMarketClient::parseQuote);
        quote.ifPresent(q -> LOG.info(
                "[SIX] {} ({}/{}) → last={} vol={} day={}..{} prev={}",
                q.name(), q.isin(), q.symbol(), q.last(), q.volume(),
                q.dayLow(), q.dayHigh(), q.previousClose()));
        return quote;
    }

    /**
     * The home daily-bar history for one Swiss ISIN, from {@code from} to
     * today.
     *
     * @return the history, oldest bar first, or empty when the ISIN is not
     *         CH-shaped, unknown or the endpoint failed - never {@code null}
     */
    public Optional<SixHistory> history(String isin, LocalDate from) {
        String id = chIsin(isin);
        if (id == null || from == null) return Optional.empty();
        Optional<SixHistory> history =
                get(String.format(CHARTS_URL, id, YYYYMMDD.format(from)), id)
                        .flatMap(SixMarketClient::parseHistory);
        history.ifPresent(h -> LOG.info("[SIX] {} → {} daily bars ({}..{})",
                h.isin(), h.bars().size(),
                h.bars().get(0).date(),
                h.bars().get(h.bars().size() - 1).date()));
        return history;
    }

    /** The normalized CH ISIN, or {@code null} when the shape is foreign. */
    private static String chIsin(String isin) {
        if (isin == null) return null;
        String id = isin.trim().toUpperCase(Locale.ROOT);
        return CH_ISIN.matcher(id).matches() ? id : null;
    }

    private Optional<String> get(String url, String isin) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("Accept", "application/json"),
                    requestTimeout, MODES);
            if (resp == null || resp.status() != 200) {
                LOG.debug("[SIX] status {} for {}",
                        resp == null ? "null" : resp.status(), isin);
                return Optional.empty();
            }
            return Optional.ofNullable(resp.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[SIX] fetch for {} failed: {}", isin, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses the column-oriented snap reply: {@code colNames} names the
     * columns, {@code rowData[0]} carries the one matched row. Package-private
     * for tests.
     */
    static Optional<SixQuote> parseQuote(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode cols = root.path("colNames");
            JsonNode rows = root.path("rowData");
            if (!cols.isArray() || !rows.isArray() || rows.isEmpty()) {
                return Optional.empty();
            }
            Map<String, JsonNode> row = new HashMap<>();
            JsonNode first = rows.get(0);
            for (int i = 0; i < cols.size() && i < first.size(); i++) {
                row.put(cols.get(i).asText(), first.get(i));
            }
            String isin = text(row.get("ISIN"));
            double last = num(row.get("ClosingPrice"));
            if (isin == null || !Double.isFinite(last) || last <= 0) {
                return Optional.empty();
            }
            return Optional.of(new SixQuote(
                    text(row.get("ShortName")), isin, text(row.get("ValorSymbol")),
                    last, count(row.get("TotalVolume")),
                    num(row.get("DailyHighPrice")), num(row.get("DailyLowPrice")),
                    num(row.get("PreviousClosingPrice"))));
        } catch (Exception e) {
            LOG.warn("[SIX] unparseable quote payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses the charting reply's parallel arrays ({@code valors[0].data}:
     * {@code Date/Open/High/Low/Close/TotalVolume}, index-aligned). Broken
     * rows are skipped, the result is sorted oldest first. Package-private
     * for tests.
     */
    static Optional<SixHistory> parseHistory(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode valor = root.path("valors").path(0);
            String isin = text(valor.get("ISIN"));
            JsonNode data = valor.path("data");
            JsonNode dates = data.path("Date");
            if (isin == null || !dates.isArray() || dates.isEmpty()) {
                return Optional.empty();
            }
            List<SixHistory.SixBar> bars = new ArrayList<>(dates.size());
            for (int i = 0; i < dates.size(); i++) {
                LocalDate date = barDate(dates.get(i));
                double close = at(data, "Close", i);
                if (date == null || !Double.isFinite(close) || close <= 0) continue;
                bars.add(new SixHistory.SixBar(date,
                        at(data, "Open", i), at(data, "High", i),
                        at(data, "Low", i), close, at(data, "TotalVolume", i)));
            }
            if (bars.isEmpty()) return Optional.empty();
            bars.sort((a, b) -> a.date().compareTo(b.date()));
            return Optional.of(new SixHistory(isin, List.copyOf(bars)));
        } catch (Exception e) {
            LOG.warn("[SIX] unparseable charts payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** One {@code yyyyMMdd} integer as the service writes its dates. */
    private static LocalDate barDate(JsonNode n) {
        if (n == null || !n.isValueNode()) return null;
        try {
            return LocalDate.parse(n.asText(""), YYYYMMDD);
        } catch (Exception e) {
            return null;
        }
    }

    /** Column {@code name} at row {@code i}, {@code NaN} when absent. */
    private static double at(JsonNode data, String name, int i) {
        return num(data.path(name).get(i));
    }

    private static String text(JsonNode v) {
        if (v == null || v.isNull() || !v.isValueNode()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    /** A number- or string-typed decimal, {@code NaN} when absent/unreadable. */
    private static double num(JsonNode v) {
        if (v == null || v.isNull()) return Double.NaN;
        if (v.isNumber()) return v.asDouble();
        try {
            String s = v.asText("").trim().replace(",", "");
            return s.isEmpty() ? Double.NaN : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** A number- or string-typed count, {@code -1} when absent/unreadable. */
    private static long count(JsonNode v) {
        double d = num(v);
        return Double.isFinite(d) && d >= 0 ? Math.round(d) : -1;
    }
}
