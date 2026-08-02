package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.tradingview.TradingViewScanner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Heatmap assembly tests. The important one is the multi-listing case: the
 * German screener answers one row PER VENUE for the same issuer, all carrying
 * the same market cap but different day changes (live 2026-08-02: NVIDIA at
 * FWB +3.91%, GETTEX +2.10%, XETR +1.38%). Drawn raw that is one company
 * occupying three cells and three times its true share of the map.
 */
class HeatmapServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> COLUMNS = List.of(
            TradingViewScanner.COL_NAME,
            TradingViewScanner.COL_DESCRIPTION,
            TradingViewScanner.COL_CLOSE,
            TradingViewScanner.COL_CURRENCY,
            TradingViewScanner.COL_MARKET_CAP,
            TradingViewScanner.COL_SECTOR,
            TradingViewScanner.COL_VOLUME,
            TradingViewScanner.COL_CHANGE);

    /** Builds a scan row the way the live scanner shapes one (positional values). */
    private static TradingViewScanner.ScanRow row(String tvSymbol, String name, String description,
                                                  double close, String currency, double marketCap,
                                                  String sector, double volume, double change) {
        List<com.fasterxml.jackson.databind.JsonNode> values = List.of(
                MAPPER.getNodeFactory().textNode(name),
                MAPPER.getNodeFactory().textNode(description),
                MAPPER.getNodeFactory().numberNode(close),
                MAPPER.getNodeFactory().textNode(currency),
                MAPPER.getNodeFactory().numberNode(marketCap),
                MAPPER.getNodeFactory().textNode(sector),
                MAPPER.getNodeFactory().numberNode(volume),
                MAPPER.getNodeFactory().numberNode(change));
        return new TradingViewScanner.ScanRow(tvSymbol, COLUMNS, values);
    }

    private static List<TradingViewScanner.ScanRow> nvidiaOnThreeVenues() {
        return List.of(
                row("FWB:NVD", "NVD", "NVIDIA Corporation", 175.0, "EUR",
                        4.2154974e12, "Electronic Technology", 1_000, 3.9068994),
                row("GETTEX:NVD", "NVD", "NVIDIA Corporation", 173.26, "EUR",
                        4.2154974e12, "Electronic Technology", 5_000, 2.0978196),
                row("XETR:NVD", "NVD", "NVIDIA Corporation", 170.42, "EUR",
                        4.2154974e12, "Electronic Technology", 900, 1.3801308));
    }

    @Test
    void oneIssuerBecomesExactlyOneCell() {
        List<HeatmapService.Cell> cells =
                HeatmapService.dedupe(nvidiaOnThreeVenues(), TradingViewScanner.COL_CHANGE);

        assertEquals(1, cells.size(), "three venue listings are one company");
    }

    @Test
    void theReferenceVenueWinsOverTheLivelierBook() {
        List<HeatmapService.Cell> cells =
                HeatmapService.dedupe(nvidiaOnThreeVenues(), TradingViewScanner.COL_CHANGE);

        HeatmapService.Cell kept = cells.get(0);
        assertEquals("XETR", kept.exchange(), "XETRA is the German price reference");
        assertEquals(170.42, kept.price(), "and its price must be the one shown");
        assertEquals(1.3801308, kept.performance(),
                "the venues disagree on the day move - the reference decides");
    }

    @Test
    void volumeBreaksTiesAmongEquivalentVenues() {
        List<TradingViewScanner.ScanRow> rows = List.of(
                row("NASDAQ:AAA", "AAA", "Acme Inc", 10, "USD", 1e10, "Tech", 100, 1.0),
                row("NASDAQ:AAA", "AAA", "Acme Inc", 10, "USD", 1e10, "Tech", 900, 1.0));

        List<HeatmapService.Cell> cells =
                HeatmapService.dedupe(rows, TradingViewScanner.COL_CHANGE);

        assertEquals(1, cells.size());
        assertEquals(900, cells.get(0).volume());
    }

    @Test
    void rowsWithoutSectorCapOrPerformanceAreDropped() {
        List<TradingViewScanner.ScanRow> rows = List.of(
                row("XETR:AAA", "AAA", "No Sector AG", 10, "EUR", 1e9, "", 100, 1.0),
                row("XETR:BBB", "BBB", "No Cap AG", 10, "EUR", 0, "Tech", 100, 1.0),
                row("XETR:CCC", "CCC", "Fine AG", 10, "EUR", 1e9, "Tech", 100, 1.0));

        List<HeatmapService.Cell> cells =
                HeatmapService.dedupe(rows, TradingViewScanner.COL_CHANGE);

        assertEquals(1, cells.size(), "a cell needs a sector, a size and a colour");
        assertEquals("Fine AG", cells.get(0).name());
    }

    @Test
    void everyPeriodMapsToAScreenerColumn() {
        assertEquals(HeatmapService.PERIODS.size(), HeatmapService.PERIOD_COLUMNS.size());
        for (String period : HeatmapService.PERIODS) {
            assertNotNull(HeatmapService.PERIOD_COLUMNS.get(period),
                    "no column for period " + period);
        }
    }

    @Test
    void selectIgnoresUnknownTokensInsteadOfBreaking() {
        HeatmapService service = new HeatmapService();

        // No scanner bound, so nothing is published; the point is that a stale
        // page sending a retired token must not throw.
        service.select("Atlantis", "42Y");
        service.select(null, null);

        assertTrue(service.getCurrent().isEmpty());
    }

    @Test
    void aSwitchAlwaysAnswersSoTheFieldNeverStaysDimmed() {
        HeatmapService service = new HeatmapService();
        service.setScanner(new StubScanner(List.of(
                row("XETR:AAA", "AAA", "Alpha AG", 10, "EUR", 1e9, "Tech", 100, 2.0))));
        List<HeatmapService.Heatmap> published = new ArrayList<>();
        service.addListener(published::add);

        service.select("Deutschland", "1W");
        int afterFirst = published.size();
        assertTrue(afterFirst > 0, "a successful switch publishes");

        // Now the source goes dark: the rebuild fails and the held picture is
        // already stale, so nothing new can be said - but the page is waiting.
        service.setScanner(new StubScanner(List.of()));
        service.select("Deutschland", "1M"); // marks stale, publishes
        int afterStale = published.size();
        service.select("Deutschland", "3M"); // nothing left to mark

        assertTrue(afterStale > afterFirst, "the first failure marks stale and publishes");
        assertTrue(published.size() > afterStale,
                "a switch with nothing new to say must STILL answer, "
                        + "otherwise the widget sits dimmed forever");
    }

    @Test
    void withoutAScannerNoPictureIsInvented() {
        HeatmapService service = new HeatmapService();
        assertNull(service.build("Deutschland", "1D"));
    }

    @Test
    void unknownUniverseOrPeriodYieldsNothing() {
        HeatmapService service = new HeatmapService();
        service.setScanner(new TradingViewScanner());

        assertNull(service.build("Atlantis", "1D"));
        assertNull(service.build("Deutschland", "42Y"));
    }

    // ---- tree shape ----

    @Test
    void sectorsBecomeContainersAndStocksBecomeLeaves() {
        HeatmapService service = new HeatmapService();
        service.setScanner(new StubScanner(List.of(
                row("XETR:AAA", "AAA", "Alpha AG", 10, "EUR", 3e9, "Tech", 100, 2.0),
                row("XETR:BBB", "BBB", "Beta AG", 20, "EUR", 1e9, "Tech", 100, -2.0),
                row("XETR:CCC", "CCC", "Gamma AG", 30, "EUR", 2e9, "Energy", 100, 5.0))));

        HeatmapService.Heatmap map = service.build("Deutschland", "1D");

        assertNotNull(map);
        List<HeatmapService.Node> sectors =
                map.nodes().stream().filter(n -> n.parent() == null).toList();
        List<HeatmapService.Node> leaves =
                map.nodes().stream().filter(n -> n.parent() != null).toList();
        assertEquals(2, sectors.size());
        assertEquals(3, leaves.size());
        assertTrue(sectors.stream().allMatch(n -> n.symbol() == null),
                "a container is not an instrument");
        assertFalse(map.stale());
        assertEquals("EUR", map.currency());
    }

    @Test
    void aSectorsColourIsCapWeightedNotAnAverage() {
        HeatmapService service = new HeatmapService();
        // Alpha is 3x the size of Beta: +2% on 3bn against -2% on 1bn.
        // Cap-weighted that is +1.0%, a plain mean would be 0.0%.
        service.setScanner(new StubScanner(List.of(
                row("XETR:AAA", "AAA", "Alpha AG", 10, "EUR", 3e9, "Tech", 100, 2.0),
                row("XETR:BBB", "BBB", "Beta AG", 20, "EUR", 1e9, "Tech", 100, -2.0))));

        HeatmapService.Heatmap map = service.build("Deutschland", "1D");

        HeatmapService.Node tech = map.nodes().stream()
                .filter(n -> n.parent() == null).findFirst().orElseThrow();
        assertEquals(1.0, tech.performance(), 0.001,
                "a sector's colour is what its money did, not what its tickers did");
        assertEquals(4e9, tech.weight());
    }

    @Test
    void negativePerformanceIsNotClamped() {
        HeatmapService service = new HeatmapService();
        service.setScanner(new StubScanner(List.of(
                row("XETR:AAA", "AAA", "Alpha AG", 10, "EUR", 1e9, "Tech", 100, -7.35))));

        HeatmapService.Heatmap map = service.build("Deutschland", "1D");

        HeatmapService.Node leaf = map.nodes().stream()
                .filter(n -> n.parent() != null).findFirst().orElseThrow();
        assertEquals(-7.35, leaf.performance(), "a red cell must stay red");
    }

    @Test
    void anEmptyScanYieldsNoMapRatherThanAnEmptyOne() {
        HeatmapService service = new HeatmapService();
        service.setScanner(new StubScanner(List.of()));

        assertNull(service.build("Deutschland", "1D"));
    }

    /** A scanner that answers from a fixed row list without touching the network. */
    private static final class StubScanner extends TradingViewScanner {
        private final List<ScanRow> rows;

        StubScanner(List<ScanRow> rows) {
            this.rows = rows;
        }

        @Override
        public ScanResult scan(String market, List<String> columns, List<Filter> filters,
                               Sort sort, int from, int to) {
            List<ScanRow> mapped = rows.stream()
                    .map(r -> new ScanRow(r.tvSymbol(), columns, r.values()))
                    .toList();
            return new ScanResult(mapped.size(), columns, mapped);
        }
    }
}
