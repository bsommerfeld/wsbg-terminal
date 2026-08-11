package de.bsommerfeld.wsbg.terminal.web.impl.sources.tradingview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradingView screener - the fixture is the live {@code germany/scan} answer
 * (2026-08-02) for the eight columns this test requests, trimmed to five rows.
 * It pins the two things a caller must not get wrong: {@code totalCount} counts
 * the whole matching universe (33.678) and not the page, and {@code d} is
 * POSITIONAL against the REQUESTED columns - which is why the column list
 * travels with the result.
 */
class TradingViewScannerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<String> COLUMNS = List.of(
            TradingViewScanner.COL_NAME,
            TradingViewScanner.COL_DESCRIPTION,
            TradingViewScanner.COL_CLOSE,
            TradingViewScanner.COL_CURRENCY,
            TradingViewScanner.COL_MARKET_CAP,
            TradingViewScanner.COL_SECTOR,
            TradingViewScanner.COL_RECOMMENDATION,
            TradingViewScanner.COL_EARNINGS_NEXT);

    @Test
    void requestBodyCarriesColumnsFiltersSortRangeAndMarket() throws Exception {
        String body = TradingViewScanner.buildRequestBody(
                TradingViewScanner.MARKET_GERMANY,
                COLUMNS,
                List.of(TradingViewScanner.Filter.notEmpty(TradingViewScanner.COL_MARKET_CAP),
                        TradingViewScanner.Filter.greater(TradingViewScanner.COL_CLOSE, 1)),
                new TradingViewScanner.Sort(TradingViewScanner.COL_MARKET_CAP, true),
                0, 5);

        JsonNode root = JSON.readTree(body);
        assertEquals(8, root.path("columns").size());
        assertEquals("name", root.path("columns").get(0).asText());
        assertEquals(2, root.path("filter").size());
        assertEquals("nempty", root.path("filter").get(0).path("operation").asText());
        assertTrue(root.path("filter").get(0).path("right").isMissingNode(),
                "an argument-less operation must not send a right operand");
        assertEquals(1, root.path("filter").get(1).path("right").asInt());
        assertEquals("market_cap_basic", root.path("sort").path("sortBy").asText());
        assertEquals("desc", root.path("sort").path("sortOrder").asText());
        assertEquals(0, root.path("range").get(0).asInt());
        assertEquals(5, root.path("range").get(1).asInt());
        assertEquals("germany", root.path("markets").get(0).asText());
    }

    @Test
    void rangeFilterSendsATwoElementRightOperand() throws Exception {
        String body = TradingViewScanner.buildRequestBody(
                TradingViewScanner.MARKET_AMERICA, List.of("close"),
                List.of(TradingViewScanner.Filter.inRange("close", 10, 20)), null, 0, 50);

        JsonNode right = JSON.readTree(body).path("filter").get(0).path("right");
        assertTrue(right.isArray());
        assertEquals(10, right.get(0).asInt());
        assertEquals(20, right.get(1).asInt());
        assertTrue(JSON.readTree(body).path("sort").isMissingNode(), "no sort means no sort node");
    }

    @Test
    void parseMapsPositionalValuesBackOntoTheRequestedColumns() {
        TradingViewScanner.ScanResult result = TradingViewScanner.parse(
                FakeTradingViewFetcher.load("tradingview-scanner-germany.json"), COLUMNS);

        assertEquals(33678, result.totalCount(), "totalCount is the universe, not the page");
        assertEquals(5, result.rows().size());

        TradingViewScanner.ScanRow row = result.rows().get(0);
        assertEquals("XETR:NVD", row.tvSymbol());
        assertEquals("XETR", row.exchange());
        assertEquals("NVD", row.ticker());
        assertEquals("NVIDIA Corporation", row.text(TradingViewScanner.COL_DESCRIPTION));
        assertEquals("EUR", row.text(TradingViewScanner.COL_CURRENCY));
        assertEquals(170.42, row.number(TradingViewScanner.COL_CLOSE).getAsDouble(), 0.001);
        assertEquals("Electronic Technology", row.text(TradingViewScanner.COL_SECTOR));
        assertTrue(row.number(TradingViewScanner.COL_RECOMMENDATION).isPresent());
        assertTrue(row.number("does_not_exist").isEmpty(), "an unrequested column is simply empty");
    }

    @Test
    void earningsDateComesBackAsAUnixSecondsInstant() {
        TradingViewScanner.ScanRow row = TradingViewScanner.parse(
                        FakeTradingViewFetcher.load("tradingview-scanner-germany.json"), COLUMNS)
                .rows().get(0);

        assertEquals(Instant.ofEpochSecond(1787774400),
                row.instant(TradingViewScanner.COL_EARNINGS_NEXT));
        assertNull(row.instant(TradingViewScanner.COL_SECTOR), "a text column is no timestamp");
    }

    @Test
    void scanPostsToTheMarketUrlWithTheMandatoryHeaders() {
        FakeTradingViewFetcher fetcher = new FakeTradingViewFetcher()
                .onFixture("scanner.tradingview.com", "tradingview-scanner-germany.json");
        TradingViewScanner scanner = new TradingViewScanner(fetcher);

        TradingViewScanner.ScanResult result = scanner.scan(
                TradingViewScanner.MARKET_GERMANY, COLUMNS,
                List.of(TradingViewScanner.Filter.notEmpty(TradingViewScanner.COL_MARKET_CAP)),
                new TradingViewScanner.Sort(TradingViewScanner.COL_MARKET_CAP, true), 0, 5);

        assertEquals("https://scanner.tradingview.com/germany/scan",
                fetcher.calls.get(0).url());
        Map<String, String> headers = fetcher.calls.get(0).headers();
        assertEquals("https://www.tradingview.com", headers.get("Origin"),
                "without Origin the scanner host answers 403");
        assertEquals("https://www.tradingview.com/", headers.get("Referer"));
        assertEquals(5, result.rows().size());
        assertEquals(COLUMNS, result.columns());
    }

    @Test
    void anyMarketAndAnyColumnSetGoesThroughUnfiltered() {
        FakeTradingViewFetcher fetcher = new FakeTradingViewFetcher()
                .on("scanner.tradingview.com",
                        FakeTradingViewFetcher.ok("{\"totalCount\":0,\"data\":[]}"));
        new TradingViewScanner(fetcher).scan("america", List.of("close", "volume"), 25);

        assertEquals("https://scanner.tradingview.com/america/scan",
                fetcher.calls.get(0).url());
        assertTrue(fetcher.postBodies.get(0).contains("\"volume\""),
                "the caller names the columns - this client keeps no canned queries");
        assertTrue(fetcher.postBodies.get(0).contains("[0,25]"));
    }

    @Test
    void garbageAndTransportFailuresYieldAnEmptyResultNeverAnException() {
        assertNull(TradingViewScanner.parse("<html>403</html>", COLUMNS));
        assertNull(TradingViewScanner.parse("{\"totalCount\":3}", COLUMNS), "no data array = no answer");
        assertNull(TradingViewScanner.parse(null, COLUMNS));

        FakeTradingViewFetcher dead = new FakeTradingViewFetcher()
                .on("scanner.tradingview.com",
                        de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse.failure());
        assertTrue(new TradingViewScanner(dead).scan("germany", COLUMNS, 10).isEmpty());

        FakeTradingViewFetcher empty = new FakeTradingViewFetcher()
                .on("scanner.tradingview.com", FakeTradingViewFetcher.ok("{}"));
        assertTrue(new TradingViewScanner(empty).scan("germany", COLUMNS, 10).isEmpty());

        FakeTradingViewFetcher unused = new FakeTradingViewFetcher();
        assertTrue(new TradingViewScanner(unused).scan("germany", List.of(), 10).isEmpty());
        assertTrue(new TradingViewScanner(unused).scan("", COLUMNS, 10).isEmpty());
        assertTrue(unused.calls.isEmpty(), "an invalid query never hits the network");
    }
}
