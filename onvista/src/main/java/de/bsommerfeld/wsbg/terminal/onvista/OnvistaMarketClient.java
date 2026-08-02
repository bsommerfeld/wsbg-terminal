package de.bsommerfeld.wsbg.terminal.onvista;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * onvista's PRICE side: every venue an instrument trades on, the full end-of-day
 * history, single ticks, the ready-made performance/volatility panel, historical
 * FX days, index membership and the stock screener. Keyless JSON, probed live
 * 2026-08-02.
 *
 * <p>Three of these are unusually strong for a keyless source:
 * <ul>
 *   <li>{@link #quotes(OnvistaEntity)} lists ~18 venues for a DAX name and
 *       marks each one {@code RLT} (realtime) or {@code DLY} (delayed) — that
 *       flag answers "which venue may I quote as live?" without guessing.
 *       Realtime for SAP 2026-08-02: Tradegate, gettex, Lang und Schwarz,
 *       LS Exchange, Quotrix, Stuttgart, Baader. Delayed: Xetra, Frankfurt,
 *       SIX, Nasdaq.</li>
 *   <li>{@link #eodHistory(OnvistaEntity, long)} returns THIRTY YEARS in one
 *       call — 7,501 daily bars for SAP back to 1996-12-13 — but only when the
 *       request carries a {@code startDate}; without it the API answers ~22
 *       days. The start date is the reply's own
 *       {@code datetimeStartAvailableHistory}, so the full history is a
 *       deliberate TWO-STAGE read: probe, then refetch from the probed start.</li>
 *   <li>{@link #bulkQuotes(List)} takes a whole index in one POST — 40 DAX
 *       members answered as one request.</li>
 * </ul>
 *
 * <p>Sparse by construction: prefer the bulk and MAX-range calls over loops.
 *
 * @see OnvistaApi for transport, the {@code Crawl-delay: 20} discipline and the
 *      terms-of-service note (automated querying is disallowed by the terms;
 *      the project owner cleared this source knowingly). {@code chart_history}
 *      and {@code order_book} answer 403 and are deliberately NOT wired.
 */
@Singleton
public class OnvistaMarketClient extends OnvistaApi {

    private static final Logger LOG = LoggerFactory.getLogger(OnvistaMarketClient.class);

    /** Index entity values probed live 2026-08-02 — onvista's own ids, not curation. */
    public static final String IDX_DAX = "20735";
    public static final String IDX_MDAX = "323547";
    public static final String IDX_SDAX = "324724";
    public static final String IDX_TECDAX = "6623216";
    public static final String IDX_SP500 = "4359526";
    public static final String IDX_NASDAQ100 = "325104";
    public static final String IDX_STOXX600 = "193741";
    public static final String IDX_SMI = "1555183";

    /** Test/default: plain direct transport. */
    public OnvistaMarketClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam. */
    @Inject
    public OnvistaMarketClient(@DirectFirst WebFetcher fetcher) {
        super(fetcher);
    }

    // ---------------------------------------------------------------- quotes

    /**
     * One venue's live line. {@code quality} is onvista's {@code codeQualityPrice}:
     * {@code RLT} = realtime, {@code DLY} = delayed (and {@code qualityBidAsk}
     * says the same for the book side — a venue can be realtime on the trade
     * and delayed on the quote).
     */
    public record VenueQuote(String marketName, String codeMarket, String codeExchange,
            long idNotation, String isoCountry, String isoCurrency,
            double last, double bid, double ask, double volumeBid, double volumeAsk,
            double open, double high, double low, double previousLast,
            double performancePct, double totalVolume, double volume4Weeks,
            double high1Year, double low1Year, double performance1YearPct,
            String quality, String qualityBidAsk, Instant datetimeLast) {

        /** True when this venue's LAST price is realtime rather than delayed. */
        public boolean realtime() {
            return "RLT".equalsIgnoreCase(quality);
        }

        /** True when bid/ask are realtime too. */
        public boolean realtimeBook() {
            return "RLT".equalsIgnoreCase(qualityBidAsk);
        }

        /** Bid-ask spread in percent of the mid, NaN when the book is empty. */
        public double spreadPct() {
            if (!Double.isFinite(bid) || !Double.isFinite(ask) || bid <= 0 || ask <= 0) {
                return Double.NaN;
            }
            return (ask - bid) / ((ask + bid) / 2.0) * 100.0;
        }
    }

    /**
     * Every venue an instrument trades on, with the RLT/DLY quality flag.
     * ~18 rows for a DAX name (probed 2026-08-02). Empty on any failure.
     */
    public List<VenueQuote> quotes(OnvistaEntity entity) {
        if (entity == null) return List.of();
        JsonNode root = getJson("v1/instruments/" + entity.pathPair() + "/quotes");
        List<VenueQuote> out = parseQuotes(root);
        if (!out.isEmpty()) {
            LOG.debug("[onvista] {} → {} venue(s), {} realtime",
                    entity.pathPair(), out.size(),
                    out.stream().filter(VenueQuote::realtime).count());
        }
        return out;
    }

    /** The realtime venues only, best (highest) volume first. */
    public List<VenueQuote> realtimeVenues(OnvistaEntity entity) {
        List<VenueQuote> all = new ArrayList<>(quotes(entity));
        all.removeIf(q -> !q.realtime());
        all.sort((a, b) -> Double.compare(
                Double.isFinite(b.totalVolume()) ? b.totalVolume() : -1,
                Double.isFinite(a.totalVolume()) ? a.totalVolume() : -1));
        return List.copyOf(all);
    }

    /**
     * BULK quotes: one POST carries a whole index (40 DAX members answered as a
     * single request, probed 2026-08-02). The reply keeps onvista's own default
     * venue per instrument. Empty on any failure.
     */
    public List<VenueQuote> bulkQuotes(List<OnvistaEntity> entities) {
        if (entities == null || entities.isEmpty()) return List.of();
        JsonNode root = postJson("v1/instruments/quote_list", quoteListBody(entities));
        List<VenueQuote> out = parseQuotes(root);
        LOG.debug("[onvista] bulk quotes: {} asked → {} answered", entities.size(), out.size());
        return out;
    }

    /**
     * Package-private, network-free: the POST body the bulk endpoint expects —
     * {@code {"list":[{"entityType":…,"entityValue":…}, …]}}.
     */
    static String quoteListBody(List<OnvistaEntity> entities) {
        StringBuilder body = new StringBuilder("{\"list\":[");
        for (int i = 0; i < entities.size(); i++) {
            OnvistaEntity e = entities.get(i);
            if (i > 0) body.append(',');
            body.append("{\"entityType\":\"").append(e.entityType())
                    .append("\",\"entityValue\":\"").append(e.entityValue()).append("\"}");
        }
        return body.append("]}").toString();
    }

    /** Package-private, network-free: a {@code list} of quote rows → venues. */
    static List<VenueQuote> parseQuotes(JsonNode root) {
        List<VenueQuote> out = new ArrayList<>();
        for (JsonNode q : listOf(root, "list")) {
            JsonNode m = q.path("market");
            String name = text(m, "name");
            if (name == null) continue;
            out.add(new VenueQuote(name, text(m, "codeMarket"), text(m, "codeExchange"),
                    m.path("idNotation").asLong(0), text(m, "isoCountry"),
                    text(q, "isoCurrency"),
                    num(q, "last"), num(q, "bid"), num(q, "ask"),
                    num(q, "volumeBid"), num(q, "volumeAsk"),
                    num(q, "open"), num(q, "high"), num(q, "low"), num(q, "previousLast"),
                    num(q, "performancePct"), num(q, "totalVolume"), num(q, "volume4Weeks"),
                    num(q, "highPrice1Year"), num(q, "lowPrice1Year"),
                    num(q, "performance1YearPct"),
                    text(q, "codeQualityPrice"), text(q, "codeQualityPriceBidAsk"),
                    instant(q, "datetimeLast")));
        }
        return List.copyOf(out);
    }

    // ----------------------------------------------------------- EOD history

    /** One daily bar. {@code open} is onvista's {@code first}, {@code close} its {@code last}. */
    public record EodBar(LocalDate date, double open, double high, double low,
            double close, double volume) {}

    /**
     * A venue's daily history plus the window onvista itself declares available.
     * {@code startAvailable} is the value the two-stage read feeds back as
     * {@code startDate}.
     */
    public record EodHistory(String marketName, long idNotation, String isoCurrency,
            LocalDate startAvailable, LocalDate endAvailable, List<EodBar> bars) {

        public boolean isEmpty() {
            return bars.isEmpty();
        }
    }

    /**
     * The FULL end-of-day history for one venue — a two-stage read, because a
     * bare {@code range=MAX} answers only ~22 days. Stage one asks for MAX and
     * reads {@code datetimeStartAvailableHistory} out of the reply; stage two
     * repeats the call with that date as {@code startDate} and
     * {@code perPage=10000} and gets everything (SAP/Xetra: 7,501 bars from
     * 1996-12-13, one request, probed 2026-08-02).
     *
     * @param idNotation the venue, from {@link #quotes(OnvistaEntity)}
     */
    public Optional<EodHistory> eodHistory(OnvistaEntity entity, long idNotation) {
        if (entity == null) return Optional.empty();
        String base = "v1/instruments/" + entity.pathPair() + "/eod_history?idNotation="
                + idNotation + "&range=MAX";
        JsonNode probe = getJson(base);
        Optional<EodHistory> shallow = parseEodHistory(probe);
        if (shallow.isEmpty()) return Optional.empty();
        LocalDate start = shallow.get().startAvailable();
        if (start == null) return shallow;
        JsonNode full = getJson(base + "&startDate=" + isoDate(start) + "&perPage=10000");
        Optional<EodHistory> deep = parseEodHistory(full);
        // The deep read must never LOSE bars — if it somehow answers thinner
        // than the probe, the probe stays the better answer.
        if (deep.isPresent() && deep.get().bars().size() >= shallow.get().bars().size()) {
            LOG.debug("[onvista] {} eod {} → {} bar(s) from {}", entity.pathPair(),
                    idNotation, deep.get().bars().size(), start);
            return deep;
        }
        return shallow;
    }

    /**
     * A single {@code eod_history} read with an explicit window — for callers
     * that want a slice rather than the whole three decades.
     *
     * <p>Currently unused, stands ready.
     */
    public Optional<EodHistory> eodHistory(OnvistaEntity entity, long idNotation,
            LocalDate from, LocalDate to) {
        if (entity == null || from == null) return Optional.empty();
        String path = "v1/instruments/" + entity.pathPair() + "/eod_history?idNotation="
                + idNotation + "&range=MAX&startDate=" + isoDate(from) + "&perPage=10000"
                + (to != null ? "&endDate=" + isoDate(to) : "");
        return parseEodHistory(getJson(path));
    }

    /**
     * Package-private, network-free: the columnar EOD reply → bars. onvista
     * serves parallel arrays ({@code datetimeLast}/{@code first}/{@code high}/
     * {@code low}/{@code last}/{@code volume}), so a short column truncates the
     * result rather than corrupting it.
     */
    static Optional<EodHistory> parseEodHistory(JsonNode root) {
        if (root == null) return Optional.empty();
        long[] days = longs(root, "datetimeLast");
        if (days.length == 0) return Optional.empty();
        double[] first = doubles(root, "first");
        double[] high = doubles(root, "high");
        double[] low = doubles(root, "low");
        double[] last = doubles(root, "last");
        double[] volume = doubles(root, "volume");
        int n = days.length;
        for (double[] col : new double[][]{first, high, low, last, volume}) {
            if (col.length > 0) n = Math.min(n, col.length);
        }
        List<EodBar> bars = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            bars.add(new EodBar(dayOf(Instant.ofEpochSecond(days[i])),
                    at(first, i), at(high, i), at(low, i), at(last, i), at(volume, i)));
        }
        JsonNode m = root.path("market");
        Instant start = instant(root, "datetimeStartAvailableHistory");
        Instant end = instant(root, "datetimeEndAvailableHistory");
        return Optional.of(new EodHistory(text(m, "name"),
                root.path("idNotation").asLong(0), text(root, "isoCurrency"),
                dayOf(start), dayOf(end), List.copyOf(bars)));
    }

    private static double at(double[] col, int i) {
        return i < col.length ? col[i] : Double.NaN;
    }

    /**
     * Package-private, network-free: the ROW-shaped EOD block the page bundle
     * carries ({@code {history:[{datetime,first,last,high,low,volume}…]}}),
     * as opposed to the API's columnar arrays. Same record out, so a caller
     * cannot tell which road the bars came down.
     */
    static Optional<EodHistory> parseEodRows(JsonNode node) {
        if (node == null) return Optional.empty();
        JsonNode rows = listOf(node, "history");
        if (rows.isEmpty()) return Optional.empty();
        List<EodBar> bars = new ArrayList<>(rows.size());
        for (JsonNode r : rows) {
            Instant at = instant(r, "datetime");
            if (at == null) continue;
            bars.add(new EodBar(dayOf(at), num(r, "first"), num(r, "high"),
                    num(r, "low"), num(r, "last"), num(r, "volume")));
        }
        if (bars.isEmpty()) return Optional.empty();
        return Optional.of(new EodHistory(text(node.path("market"), "name"),
                node.path("idNotation").asLong(0), text(node, "isoCurrency"),
                dayOf(instant(node, "datetimeStartAvailableHistory")),
                dayOf(instant(node, "datetimeEndAvailableHistory")), List.copyOf(bars)));
    }

    // -------------------------------------------------------- times & sales

    /** One executed tick on one venue. */
    public record Tick(Instant at, double price, double volume, String addendum) {}

    /**
     * Single ticks for one venue. {@code startDate} and {@code endDate} are
     * MANDATORY — the endpoint answers nothing without them (probed
     * 2026-08-02); roughly the last 30 days are retained.
     *
     * <p>Currently unused, stands ready.
     */
    public List<Tick> timesAndSales(OnvistaEntity entity, long idNotation,
            LocalDate from, LocalDate to, int perPage) {
        if (entity == null || from == null || to == null) return List.of();
        JsonNode root = getJson("v1/instruments/" + entity.pathPair()
                + "/times_and_sales?idNotation=" + idNotation
                + "&startDate=" + isoDate(from) + "&endDate=" + isoDate(to)
                + "&perPage=" + Math.max(1, perPage));
        return parseTicks(root);
    }

    /** Package-private, network-free: the columnar tick reply → ticks. */
    static List<Tick> parseTicks(JsonNode root) {
        if (root == null) return List.of();
        long[] times = longs(root, "datetimePrice");
        double[] price = doubles(root, "price");
        double[] volume = doubles(root, "volume");
        List<String> addendum = strings(root, "addendum");
        int n = Math.min(times.length, price.length);
        List<Tick> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Tick(Instant.ofEpochSecond(times[i]), price[i], at(volume, i),
                    i < addendum.size() ? addendum.get(i) : null));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------ performance panel

    /**
     * onvista's ready-made performance/volatility panel for one venue: ~120
     * precomputed figures. The named accessors carry the ones a desk actually
     * quotes; {@link #raw()} keeps every field so nothing is lost.
     */
    public record PerformanceValues(long idNotation,
            double avgVolumeD30, double avgVolumeD250,
            double vola30, double vola250,
            double perfRelW1, double perfRelW4, double perfRelM3, double perfRelM6,
            double perfRelW52, double perfRelY3, double perfRelY5, double perfRelY10,
            double highPriceW52, double lowPriceW52,
            Map<String, Double> raw) {

        /** Any numeric field of the panel by its onvista name, NaN when absent. */
        public double figure(String name) {
            Double v = raw.get(name);
            return v == null ? Double.NaN : v;
        }
    }

    /** The performance panel for one venue. Empty on any failure. */
    public Optional<PerformanceValues> performanceValues(OnvistaEntity entity, long idNotation) {
        if (entity == null) return Optional.empty();
        return parsePerformanceValues(getJson("v1/instruments/" + entity.pathPair()
                + "/performance_values?idNotation=" + idNotation));
    }

    /** Package-private, network-free. */
    static Optional<PerformanceValues> parsePerformanceValues(JsonNode root) {
        if (root == null || !root.isObject()) return Optional.empty();
        Map<String, Double> raw = new LinkedHashMap<>();
        root.fields().forEachRemaining(e -> {
            if (e.getValue().isNumber() && !"expires".equals(e.getKey())) {
                raw.put(e.getKey(), e.getValue().asDouble());
            }
        });
        if (raw.isEmpty()) return Optional.empty();
        return Optional.of(new PerformanceValues(root.path("idNotation").asLong(0),
                num(root, "averageVolumeD30"), num(root, "averageVolumeD250"),
                num(root, "vola30"), num(root, "vola250"),
                num(root, "performanceRelW1"), num(root, "performanceRelW4"),
                num(root, "performanceRelM3"), num(root, "performanceRelM6"),
                num(root, "performanceRelW52"), num(root, "performanceRelY3"),
                num(root, "performanceRelY5"), num(root, "performanceRelY10"),
                num(root, "highPriceW52"), num(root, "lowPriceW52"),
                Map.copyOf(raw)));
    }

    /** One calendar year of an instrument: open, close and the year's move. */
    public record YearPerformance(int year, double firstPrice, double lastPrice,
            double performanceAbs, double performanceRel) {}

    /**
     * Calendar-year performance, one row per year back to the venue's history
     * start — the cheap way to ask "how did this name do in 2008?".
     *
     * <p>Currently unused, stands ready.
     */
    public List<YearPerformance> performanceYears(OnvistaEntity entity, long idNotation) {
        if (entity == null) return List.of();
        return parsePerformanceYears(getJson("v1/instruments/" + entity.pathPair()
                + "/performance_values_year?idNotation=" + idNotation));
    }

    /** Package-private, network-free. */
    static List<YearPerformance> parsePerformanceYears(JsonNode root) {
        List<YearPerformance> out = new ArrayList<>();
        for (JsonNode y : listOf(root, "list")) {
            int year = intOr(y, "year", -1);
            if (year < 0) continue;
            out.add(new YearPerformance(year, num(y, "firstPrice"), num(y, "lastPrice"),
                    num(y, "performanceAbs"), num(y, "performanceRel")));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------- FX

    /** One historical FX day (the pair's daily OHLC). */
    public record CrossRate(LocalDate date, double open, double close,
            double high, double low) {}

    /**
     * The daily OHLC of a currency pair on a given date — a historical FX fix
     * without an API key.
     *
     * <p>Currently unused, stands ready.
     *
     * @param base  e.g. {@code "EUR"}
     * @param quote e.g. {@code "USD"}
     */
    public Optional<CrossRate> crossRate(String base, String quote, LocalDate date) {
        if (base == null || quote == null || date == null) return Optional.empty();
        JsonNode root = getJson("v1/currencies/crossrates/"
                + base.toUpperCase(Locale.ROOT) + "/" + quote.toUpperCase(Locale.ROOT)
                + "/date?date=" + isoDate(date));
        return parseCrossRate(root);
    }

    /** Package-private, network-free. */
    static Optional<CrossRate> parseCrossRate(JsonNode root) {
        if (root == null) return Optional.empty();
        String d = text(root, "date");
        double close = num(root, "close");
        if (d == null || !Double.isFinite(close)) return Optional.empty();
        try {
            return Optional.of(new CrossRate(LocalDate.parse(d), num(root, "open"), close,
                    num(root, "high"), num(root, "low")));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- indices

    /** One index member with its day's line. */
    public record IndexMember(OnvistaEntity instrument, long idNotation, String isoCurrency,
            double last, double performance, double performancePct, double volume,
            double money, double per, double divYield, double peg) {}

    /** An index's winners and losers of the day, with the tally onvista computes. */
    public record TopFlop(int total, int numberTop, int numberFlop, int numberEqual,
            List<IndexMember> top, List<IndexMember> flop) {}

    /**
     * The members of an index (40 for the DAX, probed 2026-08-02) — each with
     * ISIN, WKN, symbol and its current line, so this doubles as a keyless
     * index-membership resolver.
     */
    public List<IndexMember> indexConstituents(String indexEntityValue) {
        if (indexEntityValue == null || indexEntityValue.isBlank()) return List.of();
        return parseIndexMembers(getJson("v2/indices/" + indexEntityValue + "/constituents"),
                "list");
    }

    /**
     * The day's gainers and losers inside an index.
     *
     * <p>Currently unused, stands ready.
     */
    public Optional<TopFlop> indexTopFlop(String indexEntityValue) {
        if (indexEntityValue == null || indexEntityValue.isBlank()) return Optional.empty();
        JsonNode root = getJson("v2/indices/" + indexEntityValue + "/top_flop");
        return parseTopFlop(root);
    }

    /** Package-private, network-free. */
    static Optional<TopFlop> parseTopFlop(JsonNode root) {
        if (root == null) return Optional.empty();
        List<IndexMember> top = parseIndexMembers(root, "topList");
        List<IndexMember> flop = parseIndexMembers(root, "flopList");
        if (top.isEmpty() && flop.isEmpty()) return Optional.empty();
        return Optional.of(new TopFlop(intOr(root, "total", top.size() + flop.size()),
                intOr(root, "numberTop", top.size()), intOr(root, "numberFlop", flop.size()),
                intOr(root, "numberEqual", 0), top, flop));
    }

    /** Package-private, network-free: an index member array under {@code field}. */
    static List<IndexMember> parseIndexMembers(JsonNode root, String field) {
        List<IndexMember> out = new ArrayList<>();
        for (JsonNode n : listOf(root, field)) {
            OnvistaEntity e = entityOf(n.path("instrument"));
            if (e == null) continue;
            out.add(new IndexMember(e, n.path("idNotation").asLong(0),
                    text(n, "isoCurrency"), num(n, "last"), num(n, "performance"),
                    num(n, "performancePct"), num(n, "volume"), num(n, "money"),
                    num(n, "cnPer"), num(n, "cnDivYield"), num(n, "cnPeg")));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------- screener

    /** One screener hit: the instrument, its company frame and the day's line. */
    public record ScreenerHit(OnvistaEntity instrument, String companyName,
            String branch, String sector, String officialName,
            double marketCap, String isoCurrency,
            int theScreenerChance, int theScreenerRisk,
            double last, double performancePct) {}

    /**
     * The stock screener — 32,563 shares behind 66 filter groups (probed
     * 2026-08-02). Filters ride in {@code extra} exactly as onvista names them
     * (e.g. {@code "idIndex=20735"} for the DAX, chained with {@code &}), so no
     * filter vocabulary is curated here.
     *
     * <p>Currently unused, stands ready.
     *
     * @param extra    raw onvista filter expression, may be null
     * @param page     1-based page
     * @param perPage  hits per page
     */
    public List<ScreenerHit> screenStocks(String extra, int page, int perPage) {
        String path = "v1/stocks/finder/configuration_query?application=WEBSITE&device=DESKTOP"
                + "&page=" + Math.max(1, page) + "&perPage=" + Math.max(1, perPage)
                + (extra == null || extra.isBlank() ? "" : "&" + extra);
        return parseScreener(getJson(path));
    }

    /** The members of one index via the screener, which carries fundamentals too. */
    public List<ScreenerHit> screenIndex(String indexEntityValue, int perPage) {
        return screenStocks("idIndex=" + indexEntityValue, 1, perPage);
    }

    /** Package-private, network-free. */
    static List<ScreenerHit> parseScreener(JsonNode root) {
        List<ScreenerHit> out = new ArrayList<>();
        for (JsonNode n : listOf(root, "list")) {
            OnvistaEntity e = entityOf(n.path("instrument"));
            if (e == null) continue;
            JsonNode company = n.path("company");
            JsonNode branch = company.path("branch");
            JsonNode details = n.path("stocksDetails");
            JsonNode figure = n.path("stocksFigure");
            JsonNode quote = n.path("quote");
            out.add(new ScreenerHit(e, text(company, "name"), text(branch, "name"),
                    text(branch.path("sector"), "name"), text(details, "officialName"),
                    num(figure, "marketCapCompany"), text(figure, "isoCurrency"),
                    intOr(details, "theScreenerChance", -1),
                    intOr(details, "theScreenerRisk", -1),
                    num(quote, "last"), num(quote, "performancePct")));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------- calendar events

    /**
     * One dated corporate event. {@code categoryType} is a closed onvista enum:
     * {@code RESULT_REPORTS}, {@code DIVIDENDS}, {@code GENERAL_MEETING}.
     * {@code instrument} may be null for events onvista files without one.
     */
    public record CalendarEvent(String eventId, String name, String categoryType,
            String categoryName, String title, Instant start, Instant end,
            OnvistaEntity instrument) {}

    /**
     * The WORLDWIDE corporate calendar for a date window — 254 events for a
     * three-day window (probed 2026-08-02), instrument-linked where onvista has
     * a match. Purely corporate: earnings, dividends, general meetings. NO
     * macro dates live here.
     */
    public List<CalendarEvent> calendarEvents(LocalDate from, LocalDate to, int perPage) {
        if (from == null || to == null) return List.of();
        JsonNode root = getJson("v1/calendar_events/events?dateTimeStart=" + isoDate(from)
                + "&dateTimeEnd=" + isoDate(to) + "&perPage=" + Math.max(1, perPage));
        List<CalendarEvent> out = parseCalendarEvents(root);
        if (!out.isEmpty()) {
            LOG.debug("[onvista] calendar {}..{} → {} event(s)", from, to, out.size());
        }
        return out;
    }

    /** Package-private, network-free. */
    static List<CalendarEvent> parseCalendarEvents(JsonNode root) {
        List<CalendarEvent> out = new ArrayList<>();
        for (JsonNode n : listOf(root, "list")) {
            String name = text(n, "name");
            if (name == null) continue;
            JsonNode companyEvent = n.path("stocksCompanyEvent").path("companyEvent");
            out.add(new CalendarEvent(text(n, "entityValue"), name,
                    text(n, "eventCategoryType"), text(n, "nameEventCategoryType"),
                    text(companyEvent, "title"),
                    instant(n, "datetimeStart"), instant(n, "datetimeEnd"),
                    entityOf(n.path("stocksCompanyEvent").path("instrument"))));
        }
        return List.copyOf(out);
    }
}
