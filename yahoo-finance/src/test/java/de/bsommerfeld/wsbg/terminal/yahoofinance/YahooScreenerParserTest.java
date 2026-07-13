package de.bsommerfeld.wsbg.terminal.yahoofinance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the predefined-screener and trending reply shapes (verified keyless
 * 2026-07-13): {@code finance.result[0].quotes[]} with v7-style fields — raw
 * numbers or {@code {"raw":…}} wrappers — plus the uncapped {@code total}.
 */
class YahooScreenerParserTest {

    @Test
    void screenerParsesRawAndWrappedNumbers() {
        String body = """
                {"finance":{"result":[{"total":111,"quotes":[
                  {"symbol":"OPEN","longName":"Opendoor Technologies Inc.",
                   "regularMarketPrice":2.61,"regularMarketChangePercent":42.62,
                   "regularMarketVolume":1046539214,"marketCap":1900000000},
                  {"symbol":"WRAP","shortName":"Wrapped Corp",
                   "regularMarketPrice":{"raw":10.5,"fmt":"10.50"},
                   "regularMarketChangePercent":{"raw":-3.2,"fmt":"-3.20%"},
                   "marketCap":{"raw":5000000,"fmt":"5M"}}
                ]}],"error":null}}""";
        YahooFinanceClient.ScreenerResult result = YahooResponseParser.parseScreener(body);
        assertEquals(111, result.total());
        assertEquals(2, result.quotes().size());
        assertEquals("Opendoor Technologies Inc.", result.quotes().get(0).name());
        assertEquals(42.62, result.quotes().get(0).changePercent(), 1e-9);
        assertEquals(1046539214L, result.quotes().get(0).volume());
        assertEquals(-3.2, result.quotes().get(1).changePercent(), 1e-9);
        assertEquals(5000000L, result.quotes().get(1).marketCap());
    }

    @Test
    void trendingYieldsBareSymbols() {
        String body = """
                {"finance":{"result":[{"quotes":[
                  {"symbol":"NQ=F"},{"symbol":"MU"},{"symbol":"^N225"}]}]}}""";
        List<String> symbols = YahooResponseParser.parseTrending(body);
        assertEquals(List.of("NQ=F", "MU", "^N225"), symbols);
    }

    @Test
    void garbageAnswersYieldEmptyResults() {
        assertTrue(YahooResponseParser.parseScreener("Too Many Requests").quotes().isEmpty());
        assertTrue(YahooResponseParser.parseTrending("<html/>").isEmpty());
    }
}
