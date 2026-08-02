package de.bsommerfeld.wsbg.terminal.finanzennet;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The MARKET-DATA leg of finanzen.net: everything the site renders server-side
 * per instrument, addressed by the slug {@link FinanzenNetResolver} hands out.
 * No JS, no API key, no cookie - plain SSR HTML behind the Akamai header set
 * ({@link FinanzenNetHttp}). All shapes below verified live 2026-08-02 against
 * SAP and re-checked on small caps.
 *
 * <p>This is deliberately NOT a {@code NewsSource}: it carries prices, analyst
 * targets, consensus estimates and calendar dates, not articles, and emits its
 * own records rather than pretending to be a headline.
 *
 * <h3>The venue table is the point ({@code /boersenplaetze/<slug>})</h3>
 * TWELVE German trading venues in one document - Stuttgart, Düsseldorf,
 * Hamburg, Hannover, München, XETRA, Frankfurt, gettex, Quotrix, Tradegate,
 * Baader and <b>Lang und Schwarz</b> - each with last, previous close, absolute
 * and percentage change, the day's range, volume, time and date. That makes
 * this the SECOND EUR price leg beside the direct Lang &amp; Schwarz feed: one
 * fetch cross-checks the house price against eleven other venues, and when L&amp;S
 * is silent the same row still arrives from here. The page splits the venues
 * across three tables (German exchanges / off-exchange / foreign exchanges),
 * which {@link VenueQuote#segment()} preserves.
 *
 * <h3>German number formatting</h3>
 * ⚠️ Every figure on these pages is German: {@code 22.631} is twenty-two
 * thousand shares, {@code 159,00} is a price. See {@link HtmlTables#germanNumber}.
 *
 * <h3>ToS</h3>
 * finanzen.net's terms forbid archiving the content in a database. This client
 * reads on demand and hands the records to the caller; persisting them is a
 * decision that must not be made here.
 */
@Singleton
public class FinanzenNetMarketClient {

    private static final Logger LOG = LoggerFactory.getLogger(FinanzenNetMarketClient.class);

    static final String VENUES_URL = "https://www.finanzen.net/boersenplaetze/%s";
    static final String TARGETS_URL = "https://www.finanzen.net/kursziele/%s";
    static final String ESTIMATES_URL = "https://www.finanzen.net/schaetzungen/%s";
    static final String DATES_URL = "https://www.finanzen.net/termine/%s";
    static final String FINANCIALS_URL = "https://www.finanzen.net/bilanz_guv/%s";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final FinanzenNetHttp.Identity identity = FinanzenNetHttp.randomIdentity();
    private final WebFetcher fetcher;

    // ---------------------------------------------------------------- records

    /**
     * One venue's quote line.
     *
     * @param venue         the venue name as printed ({@code Lang und Schwarz})
     * @param venueUrl      the per-venue page link, or null
     * @param last          last price
     * @param previousClose previous day's close
     * @param change        absolute change vs. previous close
     * @param changePercent percentage change
     * @param dayLow        low of the printed range, or null
     * @param dayHigh       high of the printed range, or null
     * @param volume        shares traded, or null
     * @param time          time of the last print, or null
     * @param date          trading day of the last print, or null
     * @param currency      currency of the quote ({@code EUR}, {@code USD}, …)
     * @param segment       which of the three tables the row came from
     */
    public record VenueQuote(String venue, String venueUrl, Double last, Double previousClose,
                             Double change, Double changePercent, Double dayLow, Double dayHigh,
                             Long volume, LocalTime time, LocalDate date, String currency,
                             String segment) {}

    /**
     * One analyst house's standing price target.
     *
     * @param analyst       the house as finanzen.net prints it
     * @param target        the target price
     * @param currency      the target's currency
     * @param upsidePercent distance from the current price, in percent (signed)
     * @param date          the date the target was published
     */
    public record PriceTarget(String analyst, Double target, String currency,
                              Double upsidePercent, LocalDate date) {}

    /**
     * The buy/hold/sell consensus at one point in time. The page prints three of
     * these - {@code Aktuell}, {@code vor 1/2 Jahr}, {@code vor 1 Jahr} - which
     * together are a free DRIFT signal: the direction the street moved in.
     *
     * @param label the period label as printed
     * @param buy   number of buy-side ratings
     * @param hold  number of hold ratings
     * @param sell  number of sell ratings
     */
    public record ConsensusTrend(String label, int buy, int hold, int sell) {}

    /**
     * One peer's consensus, from the {@code Kursziele im Branchenvergleich}
     * table - the sector yardstick a single target means nothing without.
     */
    public record PeerConsensus(String name, int buy, int hold, int sell,
                                Double averageTarget, String currency, Double upsidePercent) {}

    /**
     * One estimate period. The page carries the forward consensus AND the
     * estimate-vs-actual history, so {@code actual}/{@code surpriseAbsolute}/
     * {@code surprisePercent} are filled for the periods that already reported -
     * a ready-made surprise track record.
     *
     * @param metric           {@code eps} or {@code revenue}
     * @param periodLabel      the column label as printed
     * @param periodEnd        the period's end date, or null
     * @param analysts         number of contributing analysts, or null
     * @param meanEstimate     the consensus estimate
     * @param priorYear        the same period one year earlier
     * @param actual           the reported figure, or null when not yet reported
     * @param surpriseAbsolute {@code actual - estimate}, as printed
     * @param surprisePercent  the deviation in percent, as printed
     * @param publication      the reporting date, or null
     * @param currency         currency of the figures
     */
    public record EstimateRow(String metric, String periodLabel, LocalDate periodEnd,
                              Integer analysts, Double meanEstimate, Double priorYear,
                              Double actual, Double surpriseAbsolute, Double surprisePercent,
                              LocalDate publication, String currency) {}

    /**
     * One calendar entry.
     *
     * @param kind        {@code Quartalszahlen}, {@code Hauptversammlung}, …
     * @param info        the period the entry belongs to ({@code Q3 2026})
     * @param date        the date, or null
     * @param dateEstimated true when finanzen.net marked the date {@code (e)*}
     * @param eps         the EPS figure on the row
     * @param epsIsActual true on the past-dates table (reported EPS), false on
     *                    the forward table (estimated EPS)
     * @param currency    currency of {@code eps}
     */
    public record CompanyDate(String kind, String info, LocalDate date, boolean dateEstimated,
                              Double eps, boolean epsIsActual, String currency) {}

    /** One metric line of a multi-year financial statement, period → value. */
    public record FinancialRow(String metric, Map<String, Double> byPeriod) {
        public FinancialRow {
            byPeriod = byPeriod == null ? Map.of() : Map.copyOf(byPeriod);
        }
    }

    /**
     * One statement block of {@code /bilanz_guv/} - the page prints five
     * ({@code Die Aktie}, {@code Unternehmenskennzahlen}, {@code GuV},
     * {@code Bilanz}, {@code sonstige Angaben}), each over seven fiscal years.
     */
    public record FinancialStatement(String title, List<String> periods, List<FinancialRow> rows) {
        public FinancialStatement {
            periods = periods == null ? List.of() : List.copyOf(periods);
            rows = rows == null ? List.of() : List.copyOf(rows);
        }

        /** The named metric line, or empty. */
        public Optional<FinancialRow> row(String metric) {
            return rows.stream()
                    .filter(r -> FinanzenNetHttp.lower(r.metric())
                            .equals(FinanzenNetHttp.lower(metric)))
                    .findFirst();
        }
    }

    /** The three consensus snapshots plus the per-house targets and the peers. */
    public record TargetBoard(List<PriceTarget> targets, List<ConsensusTrend> trend,
                              List<PeerConsensus> peers) {
        public TargetBoard {
            targets = targets == null ? List.of() : List.copyOf(targets);
            trend = trend == null ? List.of() : List.copyOf(trend);
            peers = peers == null ? List.of() : List.copyOf(peers);
        }
    }

    // ------------------------------------------------------------------ ctors

    /** Test/default: plain direct transport. */
    public FinanzenNetMarketClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the shared standard {@link WebFetcher} chain (browser →
     * direct). Browser-first is the safe default in front of an Akamai wall
     * even though the header set alone answers 200 today.
     */
    @Inject
    public FinanzenNetMarketClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    // ------------------------------------------------------------------- API

    /**
     * All venue quotes for a slug, in page order (German exchanges first, then
     * off-exchange, then foreign). The EUR price spine: twelve German venues,
     * Lang und Schwarz among them, in a single fetch.
     */
    public List<VenueQuote> venueQuotes(String slug) {
        return parseVenues(fetchPage(VENUES_URL, slug));
    }

    /**
     * The quote of ONE venue, matched case-insensitively on a substring
     * ({@code "lang und schwarz"}, {@code "xetra"}, {@code "tradegate"}).
     *
     * <p>Currently unused by the terminal - it stands ready as the direct
     * cross-check for the Lang &amp; Schwarz price leg (house mandate 2026-08-02:
     * every source is connected per instrument AND generally).
     */
    public Optional<VenueQuote> venueQuote(String slug, String venueNeedle) {
        if (venueNeedle == null || venueNeedle.isBlank()) return Optional.empty();
        String needle = FinanzenNetHttp.lower(venueNeedle);
        return venueQuotes(slug).stream()
                .filter(q -> FinanzenNetHttp.lower(q.venue()).contains(needle))
                .findFirst();
    }

    /** Analyst targets, the consensus drift and the peer-group consensus. */
    public TargetBoard targetBoard(String slug) {
        return parseTargets(fetchPage(TARGETS_URL, slug));
    }

    /** Just the per-house price targets, newest first as the page prints them. */
    public List<PriceTarget> priceTargets(String slug) {
        return targetBoard(slug).targets();
    }

    /**
     * The consensus now, half a year ago and a year ago.
     *
     * <p>Currently unused - see {@link #venueQuote}.
     */
    public List<ConsensusTrend> consensusTrend(String slug) {
        return targetBoard(slug).trend();
    }

    /**
     * The peer-group consensus table.
     *
     * <p>Currently unused - see {@link #venueQuote}.
     */
    public List<PeerConsensus> peerConsensus(String slug) {
        return targetBoard(slug).peers();
    }

    /**
     * EPS and revenue consensus per quarter / fiscal year, merged with the
     * estimate-vs-actual history so a reported period carries its surprise.
     */
    public List<EstimateRow> estimates(String slug) {
        return parseEstimates(fetchPage(ESTIMATES_URL, slug));
    }

    /** Forward calendar entries (estimated EPS), earliest first. */
    public List<CompanyDate> upcomingDates(String slug) {
        return parseDates(fetchPage(DATES_URL, slug), false);
    }

    /**
     * Past calendar entries with the REPORTED EPS, back to 2022.
     *
     * <p>Currently unused - see {@link #venueQuote}.
     */
    public List<CompanyDate> pastDates(String slug) {
        return parseDates(fetchPage(DATES_URL, slug), true);
    }

    /**
     * The multi-year statements of {@code /bilanz_guv/} - P&amp;L, balance
     * sheet, per-share figures and ratios, seven fiscal years each.
     *
     * <p>Currently unused - see {@link #venueQuote}.
     */
    public List<FinancialStatement> financials(String slug) {
        return parseFinancials(fetchPage(FINANCIALS_URL, slug));
    }

    // --------------------------------------------------------------- parsers

    /** Package-private for tests. */
    static List<VenueQuote> parseVenues(String html) {
        List<HtmlTables.Table> tables = HtmlTables.parse(html);
        List<VenueQuote> out = new ArrayList<>();
        for (HtmlTables.Table t : tables) {
            String heading = t.heading();
            if (!FinanzenNetHttp.lower(heading).contains("börse")
                    && !FinanzenNetHttp.lower(heading).contains("handelsplätze")) {
                continue;
            }
            String segment = segmentOf(heading);
            for (int i = 0; i < t.rows().size(); i++) {
                List<String> r = t.rows().get(i);
                // Header row, promo rows and spacers all fail one of these two:
                // nine cells and a parseable price in the second one.
                if (r.size() < 9) continue;
                Double last = HtmlTables.germanNumber(r.get(1));
                if (last == null) continue;
                Double[] range = parseRange(r.get(5));
                out.add(new VenueQuote(
                        r.get(0),
                        t.links().size() > i ? t.links().get(i) : null,
                        last,
                        HtmlTables.germanNumber(r.get(2)),
                        HtmlTables.germanNumber(r.get(3)),
                        HtmlTables.germanNumber(r.get(4)),
                        range[0], range[1],
                        HtmlTables.germanLong(r.get(6)),
                        HtmlTables.clockTime(r.get(7)),
                        HtmlTables.germanDate(r.get(8)),
                        HtmlTables.currency(r.get(1)),
                        segment));
            }
        }
        return List.copyOf(out);
    }

    /** Package-private for tests. */
    static TargetBoard parseTargets(String html) {
        List<HtmlTables.Table> tables = HtmlTables.parse(html);

        List<PriceTarget> targets = new ArrayList<>();
        HtmlTables.Table analysts = HtmlTables.find(tables, "bewerten die");
        if (analysts != null) {
            for (List<String> r : analysts.rows()) {
                if (r.size() < 5 || "Analyst".equalsIgnoreCase(r.get(0))) continue;
                Double target = HtmlTables.germanNumber(r.get(2));
                if (target == null) continue;
                targets.add(new PriceTarget(r.get(0), target, HtmlTables.currency(r.get(2)),
                        HtmlTables.germanNumber(r.get(3)), HtmlTables.germanDate(r.get(4))));
            }
        }

        List<PeerConsensus> peers = new ArrayList<>();
        HtmlTables.Table peerTable = HtmlTables.find(tables, "branchenvergleich");
        if (peerTable != null) {
            for (List<String> r : peerTable.rows()) {
                if (r.size() < 6 || "Aktie".equalsIgnoreCase(r.get(0))) continue;
                peers.add(new PeerConsensus(r.get(0),
                        HtmlTables.germanInt(r.get(1)),
                        HtmlTables.germanInt(r.get(2)),
                        HtmlTables.germanInt(r.get(3)),
                        HtmlTables.germanNumber(r.get(4)),
                        HtmlTables.currency(r.get(4)),
                        HtmlTables.germanNumber(r.get(5))));
            }
        }

        // The rating-drift block is three tiny tables: a counts row, a
        // "Buy | Hold | Sell" legend row and a label row ("Aktuell", "vor 1/2 Jahr").
        List<ConsensusTrend> trend = new ArrayList<>();
        for (HtmlTables.Table t : tables) {
            List<List<String>> rows = t.rows();
            if (rows.size() < 3) continue;
            List<String> counts = rows.get(0);
            List<String> legend = rows.get(1);
            List<String> label = rows.get(2);
            if (counts.size() != 3 || legend.size() != 3 || label.isEmpty()) continue;
            if (!"Buy".equalsIgnoreCase(legend.get(0)) || !"Sell".equalsIgnoreCase(legend.get(2))) {
                continue;
            }
            trend.add(new ConsensusTrend(label.get(0),
                    HtmlTables.germanInt(counts.get(0)),
                    HtmlTables.germanInt(counts.get(1)),
                    HtmlTables.germanInt(counts.get(2))));
        }
        return new TargetBoard(targets, trend, peers);
    }

    /**
     * Package-private for tests. The estimate tables are COLUMN-oriented -
     * metrics run down the first column, periods across the header - so each
     * column becomes one {@link EstimateRow}. The forward consensus block and
     * the history block are merged on {@code (metric, periodEnd)} so a reported
     * period keeps its estimate beside its actual.
     */
    static List<EstimateRow> parseEstimates(String html) {
        List<HtmlTables.Table> tables = HtmlTables.parse(html);
        Map<String, EstimateRow> merged = new LinkedHashMap<>();
        collectEstimates(HtmlTables.find(tables, "quartalsschätzungen", "gewinn"), "eps", merged);
        collectEstimates(HtmlTables.find(tables, "quartalsschätzungen", "umsatz"), "revenue", merged);
        collectHistory(HtmlTables.find(tables, "historie gewinn"), "eps", merged);
        collectHistory(HtmlTables.find(tables, "historie umsatz"), "revenue", merged);
        return List.copyOf(merged.values());
    }

    private static void collectEstimates(HtmlTables.Table t, String metric,
                                         Map<String, EstimateRow> into) {
        if (t == null || t.rows().isEmpty()) return;
        List<String> header = t.rows().get(0);
        List<String> analysts = t.row("Anzahl Analysten");
        List<String> mean = t.rowStartingWith("Mittlere Schätzung");
        List<String> prior = t.row("Vorjahr");
        List<String> published = t.rowStartingWith("Veröffentlichung");
        for (int c = 1; c < header.size(); c++) {
            String label = header.get(c);
            LocalDate end = HtmlTables.germanDate(label);
            String key = estimateKey(metric, label, end);
            Double meanValue = HtmlTables.germanNumber(HtmlTables.Table.cell(mean, c));
            Double analystCount = HtmlTables.germanNumber(HtmlTables.Table.cell(analysts, c));
            EstimateRow prev = into.get(key);
            into.put(key, new EstimateRow(metric, label, end,
                    analystCount == null ? null : analystCount.intValue(),
                    meanValue,
                    HtmlTables.germanNumber(HtmlTables.Table.cell(prior, c)),
                    prev == null ? null : prev.actual(),
                    prev == null ? null : prev.surpriseAbsolute(),
                    prev == null ? null : prev.surprisePercent(),
                    HtmlTables.germanDate(HtmlTables.Table.cell(published, c)),
                    HtmlTables.currency(HtmlTables.Table.cell(mean, c))));
        }
    }

    private static void collectHistory(HtmlTables.Table t, String metric,
                                       Map<String, EstimateRow> into) {
        if (t == null || t.rows().isEmpty()) return;
        List<String> header = t.rows().get(0);
        List<String> mean = t.rowStartingWith("Mittlere Schätzung");
        List<String> actual = t.rowStartingWith("Tatsächlicher Wert");
        List<String> delta = t.row("+/-");
        List<String> deviation = t.rowStartingWith("Abweichung");
        for (int c = 1; c < header.size(); c++) {
            String label = header.get(c);
            LocalDate end = HtmlTables.germanDate(label);
            String key = estimateKey(metric, label, end);
            EstimateRow prev = into.get(key);
            Double meanValue = HtmlTables.germanNumber(HtmlTables.Table.cell(mean, c));
            Double actualValue = HtmlTables.germanNumber(HtmlTables.Table.cell(actual, c));
            into.put(key, new EstimateRow(metric, prev != null ? prev.periodLabel() : label, end,
                    prev == null ? null : prev.analysts(),
                    prev != null && prev.meanEstimate() != null ? prev.meanEstimate() : meanValue,
                    prev == null ? null : prev.priorYear(),
                    actualValue,
                    HtmlTables.germanNumber(HtmlTables.Table.cell(delta, c)),
                    HtmlTables.germanNumber(HtmlTables.Table.cell(deviation, c)),
                    prev == null ? null : prev.publication(),
                    HtmlTables.currency(HtmlTables.Table.cell(actual, c)) != null
                            ? HtmlTables.currency(HtmlTables.Table.cell(actual, c))
                            : HtmlTables.currency(HtmlTables.Table.cell(mean, c))));
        }
    }

    /**
     * Package-private for tests. {@code past=false} reads the forward table
     * (estimated EPS), {@code past=true} the history table (reported EPS).
     */
    static List<CompanyDate> parseDates(String html, boolean past) {
        List<HtmlTables.Table> tables = HtmlTables.parse(html);
        HtmlTables.Table t = past
                ? HtmlTables.find(tables, "vergangene termine")
                : firstFutureDatesTable(tables);
        if (t == null) return List.of();
        List<CompanyDate> out = new ArrayList<>();
        for (List<String> r : t.rows()) {
            if (r.size() < 4 || "Terminart".equalsIgnoreCase(r.get(0))) continue;
            String dateCell = r.get(3);
            out.add(new CompanyDate(r.get(0), r.get(2), HtmlTables.germanDate(dateCell),
                    dateCell.contains("(e)"),
                    HtmlTables.germanNumber(r.get(1)), past, HtmlTables.currency(r.get(1))));
        }
        return List.copyOf(out);
    }

    /** Package-private for tests. */
    static List<FinancialStatement> parseFinancials(String html) {
        List<FinancialStatement> out = new ArrayList<>();
        for (HtmlTables.Table t : HtmlTables.parse(html)) {
            if (t.rows().size() < 2) continue;
            List<String> header = t.rows().get(0);
            if (header.size() < 2 || !header.get(0).isEmpty()) continue;
            List<String> periods = List.copyOf(header.subList(1, header.size()));
            List<FinancialRow> rows = new ArrayList<>();
            for (int i = 1; i < t.rows().size(); i++) {
                List<String> r = t.rows().get(i);
                if (r.size() < 2 || r.get(0).isEmpty()) continue;
                Map<String, Double> byPeriod = new LinkedHashMap<>();
                for (int c = 1; c < r.size() && c - 1 < periods.size(); c++) {
                    Double v = HtmlTables.germanNumber(r.get(c));
                    if (v != null) byPeriod.put(periods.get(c - 1), v);
                }
                if (!byPeriod.isEmpty()) rows.add(new FinancialRow(r.get(0), byPeriod));
            }
            if (!rows.isEmpty()) out.add(new FinancialStatement(t.heading(), periods, rows));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------- internals

    private String fetchPage(String urlTemplate, String slug) {
        if (slug == null || slug.isBlank()) return "";
        String url = String.format(urlTemplate, slug.trim());
        try {
            WebResponse resp = fetcher.fetch(url, FinanzenNetHttp.headers(identity, false),
                    REQUEST_TIMEOUT);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("finanzen.net {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("finanzen.net {} failed: {}", url, e.getMessage());
        }
        return "";
    }

    private static HtmlTables.Table firstFutureDatesTable(List<HtmlTables.Table> tables) {
        for (HtmlTables.Table t : tables) {
            String h = FinanzenNetHttp.lower(t.heading());
            if (h.contains("termine") && !h.contains("vergangene")) return t;
        }
        return null;
    }

    /**
     * The merge key for an estimate column. ⚠️ The period END is NOT unique on
     * its own: a December-quarter column and the full-year column share
     * {@code 31.12.<year>}, so keying on the date alone silently drops one of
     * them. The PERIOD KIND (quarter vs fiscal year) is therefore part of the
     * key, which still lets the history table merge onto the consensus table -
     * its columns are quarters and so are their counterparts.
     */
    static String estimateKey(String metric, String label, LocalDate end) {
        String kind = FinanzenNetHttp.lower(label).contains("geschäftsjahr") ? "fy" : "q";
        return metric + "@" + kind + "@" + (end != null ? end.toString() : label);
    }

    /** {@code 152,80 - 159,00} → {low, high}; unparseable halves stay null. */
    static Double[] parseRange(String cell) {
        if (cell == null) return new Double[]{null, null};
        String[] parts = cell.split("-");
        if (parts.length != 2) return new Double[]{null, null};
        return new Double[]{HtmlTables.germanNumber(parts[0]), HtmlTables.germanNumber(parts[1])};
    }

    private static String segmentOf(String heading) {
        String h = FinanzenNetHttp.lower(heading);
        if (h.contains("außerbörsl") || h.contains("ausserbörsl")) return "off-exchange";
        if (h.contains("ausländ")) return "foreign";
        return "german";
    }

    /** The venue names finanzen.net prints for the German block - for tests/docs. */
    static final List<String> GERMAN_VENUES = List.of(
            "Stuttgart", "Düsseldorf", "Hamburg", "Hannover", "München", "XETRA",
            "Frankfurt", "gettex", "Quotrix", "Tradegate", "Baader Bank", "Lang und Schwarz");
}
