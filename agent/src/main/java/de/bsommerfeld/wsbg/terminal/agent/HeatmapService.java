package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.PollingMonitor;
import de.bsommerfeld.wsbg.terminal.stocknear.StocknearClient;
import de.bsommerfeld.wsbg.terminal.tradingview.TradingViewScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * The sector heatmap: one treemap of a market, sized by market capitalisation
 * and coloured by performance over a chosen window.
 *
 * <p><b>Own data is the backbone.</b> The tree is built from TradingView's
 * screener ({@link TradingViewScanner}) - one keyless POST returns the whole
 * listed universe of a market with sector, market cap and seven performance
 * windows, in EUR for the German venues. That covers what a US-only vendor
 * cannot: this house's home market. {@link StocknearClient} stands behind it
 * as a US fallback only, for the case the screener goes dark (owner decision
 * 2026-08-02: own data as backbone, Stocknear as second opinion).
 *
 * <p><b>The multi-listing trap.</b> The German market answers the SAME company
 * once per venue - a live probe returned {@code FWB:NVD}, {@code GETTEX:NVD}
 * and {@code XETR:NVD} for one issuer, each with the identical market cap but
 * a DIFFERENT day change (1.38% to 3.91%, because the venues close at
 * different times). Rendering that raw would draw NVIDIA three times and
 * treble its share of the map. {@link #dedupe} therefore keeps one row per
 * issuer, preferring the reference venue (XETRA), then the largest turnover
 * proxy - so the map shows companies, not tickers.
 *
 * <p>A tick costs one request per universe. Nothing is cached beyond the last
 * snapshot: a consumer redraws from {@link #getCurrent()}, and a failed tick
 * keeps the previous picture rather than blanking it - a stale map that says
 * so beats an empty one.
 *
 * <p><b>DORMANT (owner decision 2026-08-03).</b> The widget this fed was taken
 * back out: the picture was not worth a standing market-wide poll yet. So
 * nothing binds, starts or publishes this service today - {@link #start()} is
 * never called, no timer runs, not a single request goes out. The assembly
 * logic is kept because it is the part that was hard to get right (see the
 * multi-listing trap above), so mobilising it later is a matter of calling
 * {@link #start()} and adding a publisher again, not of rebuilding it.
 * {@link #build} works standalone for a one-shot picture without any polling.
 */
@Singleton
public class HeatmapService extends PollingMonitor<HeatmapService.Heatmap> {

    private static final Logger LOG = LoggerFactory.getLogger(HeatmapService.class);

    private static final long INITIAL_DELAY_SECONDS = 20;
    private static final long INTERVAL_SECONDS = 300;
    private static final int JITTER_PERCENT = 10;

    /** How many issuers a map carries at most - beyond this the cells are dust. */
    static final int MAX_CELLS = 120;

    /** Window token → the screener column that answers it. */
    static final Map<String, String> PERIOD_COLUMNS = Map.of(
            "1D", TradingViewScanner.COL_CHANGE,
            "1W", "Perf.W",
            "1M", "Perf.1M",
            "3M", "Perf.3M",
            "6M", "Perf.6M",
            "1Y", "Perf.Y",
            "YTD", "Perf.YTD");

    /** Presentation order for the period switch (Map.of() has no order). */
    static final List<String> PERIODS = List.of("1D", "1W", "1M", "3M", "6M", "1Y", "YTD");

    /** Universe token → the screener market it scans. */
    static final Map<String, String> UNIVERSES = Map.of(
            "Deutschland", TradingViewScanner.MARKET_GERMANY,
            "USA", TradingViewScanner.MARKET_AMERICA);

    static final List<String> UNIVERSE_ORDER = List.of("Deutschland", "USA");

    /**
     * Reference venues, best first. XETRA is the German price reference; the
     * US venues are equivalent for this purpose and only break ties.
     */
    private static final List<String> VENUE_PREFERENCE =
            List.of("XETR", "NASDAQ", "NYSE", "AMEX", "FWB", "SWB", "GETTEX", "LSX", "TRADEGATE");

    /** Rows without these cannot be placed on a map at all. */
    private static final Set<String> REQUIRED = Set.of(
            TradingViewScanner.COL_MARKET_CAP, TradingViewScanner.COL_SECTOR);

    private volatile TradingViewScanner scanner;
    private volatile StocknearClient stocknear;

    private volatile String period = "1D";
    private volatile String universe = "Deutschland";

    public HeatmapService() {
        super("heatmap");
    }

    @Inject(optional = true)
    void setScanner(TradingViewScanner scanner) {
        this.scanner = scanner;
    }

    @Inject(optional = true)
    void setStocknearClient(StocknearClient client) {
        this.stocknear = client;
    }

    /**
     * Starts the poll loop (the monitor start contract: only ever called after
     * the window is up, because a market-wide scan is a network walk).
     *
     * <p>NOT CALLED TODAY - see the class javadoc. Whoever mobilises this must
     * call it from {@code AppMain}'s post-window block, never from DI
     * construction: a fetch during injector construction would initialise CEF
     * off the EDT and hang on macOS.
     */
    public void start() {
        if (!beginStart()) return;
        scheduleTick(this::tick, INITIAL_DELAY_SECONDS, INTERVAL_SECONDS, JITTER_PERCENT);
    }

    /**
     * The page asked for a different window or market. Redraws immediately
     * rather than waiting out the poll interval - a switch that takes five
     * minutes to answer reads as broken.
     */
    public void select(String newUniverse, String newPeriod) {
        if (newUniverse != null && UNIVERSES.containsKey(newUniverse)) {
            this.universe = newUniverse;
        }
        if (newPeriod != null && PERIOD_COLUMNS.containsKey(newPeriod)) {
            this.period = newPeriod;
        }
        // The page dims its field on a switch and clears that dim on the NEXT
        // payload, whatever it says. So a switch must always answer - even a
        // failed rebuild, even a rebuild that changed nothing - or the widget
        // sits greyed out forever waiting on us.
        if (!tick()) {
            getCurrent().ifPresent(this::fanOut);
        }
    }

    /** @return true when a payload was published. */
    private boolean tick() {
        try {
            Heatmap map = build(universe, period);
            if (map == null) {
                LOG.debug("[HEATMAP] tick produced nothing - keeping the previous picture");
                return markStale();
            }
            setCurrent(map);
            fanOut(map);
            return true;
        } catch (Exception e) {
            LOG.warn("[HEATMAP] tick failed: {}", e.getMessage());
            return markStale();
        }
    }

    /**
     * Re-publishes the last picture flagged stale, so the page can say so.
     *
     * @return true when something was published (i.e. a fresh picture existed
     *         to mark); false when there was nothing to say at all.
     */
    private boolean markStale() {
        Heatmap held = getCurrent().orElse(null);
        if (held == null || held.stale()) return false;
        Heatmap stale = new Heatmap(held.period(), held.periods(), held.universe(),
                held.universes(), held.generatedAt(), held.currency(), true, held.nodes());
        setCurrent(stale);
        fanOut(stale);
        return true;
    }

    /** Package-private for tests. */
    Heatmap build(String universeToken, String periodToken) {
        TradingViewScanner sc = scanner;
        String market = UNIVERSES.get(universeToken);
        String perfColumn = PERIOD_COLUMNS.get(periodToken);
        if (sc == null || market == null || perfColumn == null) return null;

        List<String> columns = new ArrayList<>(List.of(
                TradingViewScanner.COL_NAME,
                TradingViewScanner.COL_DESCRIPTION,
                TradingViewScanner.COL_CLOSE,
                TradingViewScanner.COL_CURRENCY,
                TradingViewScanner.COL_MARKET_CAP,
                TradingViewScanner.COL_SECTOR,
                TradingViewScanner.COL_VOLUME));
        if (!columns.contains(perfColumn)) columns.add(perfColumn);

        TradingViewScanner.ScanResult result;
        try {
            result = sc.scan(market, columns,
                    List.of(TradingViewScanner.Filter.notEmpty(TradingViewScanner.COL_MARKET_CAP)),
                    new TradingViewScanner.Sort(TradingViewScanner.COL_MARKET_CAP, true),
                    0, MAX_CELLS * 4);
        } catch (Exception e) {
            LOG.debug("[HEATMAP] screener failed for {}: {}", universeToken, e.getMessage());
            return null;
        }
        if (result == null || result.isEmpty()) return null;

        List<Cell> cells = dedupe(result.rows(), perfColumn);
        if (cells.isEmpty()) return null;
        cells = cells.stream()
                .sorted(Comparator.comparingDouble(Cell::marketCap).reversed())
                .limit(MAX_CELLS)
                .toList();

        return new Heatmap(periodToken, PERIODS, universeToken, UNIVERSE_ORDER,
                Instant.now(), currencyOf(cells), false, toNodes(cells));
    }

    /**
     * One row per issuer. Rows are keyed by company identity (description, or
     * the ticker when a description is missing) rather than by symbol, because
     * the same issuer appears once per venue with the same market cap and
     * different day changes. See the class javadoc for why this matters.
     * Package-private for tests.
     */
    static List<Cell> dedupe(List<TradingViewScanner.ScanRow> rows, String perfColumn) {
        Map<String, Cell> best = new LinkedHashMap<>();
        for (TradingViewScanner.ScanRow row : rows) {
            String sector = row.text(TradingViewScanner.COL_SECTOR);
            OptionalDouble cap = row.number(TradingViewScanner.COL_MARKET_CAP);
            if (sector == null || sector.isBlank() || cap.isEmpty() || cap.getAsDouble() <= 0) {
                continue;
            }
            String description = row.text(TradingViewScanner.COL_DESCRIPTION);
            String ticker = row.ticker();
            String key = (description != null && !description.isBlank()
                    ? description : ticker).toLowerCase(Locale.ROOT);

            OptionalDouble perf = row.number(perfColumn);
            if (perf.isEmpty()) continue; // no colour, no cell

            Cell candidate = new Cell(
                    ticker,
                    description != null && !description.isBlank() ? description : ticker,
                    sector,
                    cap.getAsDouble(),
                    perf.getAsDouble(),
                    row.number(TradingViewScanner.COL_CLOSE).isPresent()
                            ? row.number(TradingViewScanner.COL_CLOSE).getAsDouble() : null,
                    row.text(TradingViewScanner.COL_CURRENCY),
                    row.exchange(),
                    row.number(TradingViewScanner.COL_VOLUME).orElse(0));

            Cell held = best.get(key);
            if (held == null || prefers(candidate, held)) best.put(key, candidate);
        }
        return List.copyOf(best.values());
    }

    /** Reference venue first, then the livelier book. Package-private for tests. */
    static boolean prefers(Cell candidate, Cell held) {
        int a = VENUE_PREFERENCE.indexOf(candidate.exchange() == null
                ? "" : candidate.exchange().toUpperCase(Locale.ROOT));
        int b = VENUE_PREFERENCE.indexOf(held.exchange() == null
                ? "" : held.exchange().toUpperCase(Locale.ROOT));
        if (a != b) {
            if (a < 0) return false;
            if (b < 0) return true;
            return a < b;
        }
        return candidate.volume() > held.volume();
    }

    /** Sector containers first, then their leaves - the treemap reads parents first. */
    private static List<Node> toNodes(List<Cell> cells) {
        Map<String, Double> sectorWeight = new LinkedHashMap<>();
        Map<String, Double> sectorPerfNumerator = new LinkedHashMap<>();
        for (Cell c : cells) {
            sectorWeight.merge(c.sector(), c.marketCap(), Double::sum);
            sectorPerfNumerator.merge(c.sector(), c.performance() * c.marketCap(), Double::sum);
        }
        List<Node> nodes = new ArrayList<>();
        for (Map.Entry<String, Double> e : sectorWeight.entrySet()) {
            double weight = e.getValue();
            // Cap-weighted sector move: a sector's colour is what its money did.
            double perf = weight > 0 ? sectorPerfNumerator.get(e.getKey()) / weight : 0;
            nodes.add(new Node(e.getKey(), e.getKey(), null, null,
                    weight, round(perf), null, null));
        }
        for (Cell c : cells) {
            nodes.add(new Node(c.symbol(), c.name(), c.sector(), c.symbol(),
                    c.marketCap(), round(c.performance()), c.price(), c.marketCap()));
        }
        return List.copyOf(nodes);
    }

    private static double round(double v) {
        return Math.round(v * 100d) / 100d;
    }

    /** The currency the majority of cells quote in; blank when mixed. */
    private static String currencyOf(List<Cell> cells) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Cell c : cells) {
            if (c.currency() != null) counts.merge(c.currency(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    /**
     * US FALLBACK: Stocknear's ready-made tree, used only when the screener
     * cannot answer. Kept deliberately narrow - see {@link StocknearClient}.
     * Currently reachable but not on the tick path; it stands ready.
     */
    public Heatmap fallbackUs(String index, String periodToken) {
        StocknearClient client = stocknear;
        if (client == null || !PERIOD_COLUMNS.containsKey(periodToken)) return null;
        List<StocknearClient.HeatmapNode> tree = client.heatmap(index, periodToken);
        if (tree.isEmpty()) return null;
        List<Node> nodes = new ArrayList<>();
        for (StocknearClient.HeatmapNode n : tree) {
            nodes.add(new Node(n.id(), n.name(), n.parent(), n.symbol(),
                    n.weight() == null ? 0 : n.weight(),
                    n.performancePercent() == null ? 0 : round(n.performancePercent()),
                    n.currentPrice(), n.marketCap()));
        }
        return new Heatmap(periodToken, PERIODS, "USA (" + index + ")", UNIVERSE_ORDER,
                Instant.now(), "USD", false, List.copyOf(nodes));
    }

    /** One issuer as picked from the screener rows. Package-private for tests. */
    record Cell(String symbol, String name, String sector, double marketCap,
                double performance, Double price, String currency, String exchange,
                double volume) {}

    /**
     * One treemap cell. A sector container has a null {@code parent} and
     * {@code symbol}; a leaf carries both.
     */
    public record Node(String id, String name, String parent, String symbol,
                       double weight, double performance, Double price, Double marketCap) {}

    /** One published picture. {@code stale} means the last tick could not refresh it. */
    public record Heatmap(String period, List<String> periods,
                          String universe, List<String> universes,
                          Instant generatedAt, String currency, boolean stale,
                          List<Node> nodes) {}
}
