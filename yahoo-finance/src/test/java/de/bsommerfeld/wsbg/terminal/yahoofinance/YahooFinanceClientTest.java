package de.bsommerfeld.wsbg.terminal.yahoofinance;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YahooFinanceClientTest {

    private final YahooFinanceClient client = new YahooFinanceClient(10, 300);

    // ---- 429 circuit breaker ----

    @Test
    void rateLimitStatusOnlyForBackoffCodes() {
        assertTrue(YahooFinanceClient.isRateLimitStatus(429));
        assertTrue(YahooFinanceClient.isRateLimitStatus(503));
        assertTrue(YahooFinanceClient.isRateLimitStatus(999));
        assertFalse(YahooFinanceClient.isRateLimitStatus(200));
        assertFalse(YahooFinanceClient.isRateLimitStatus(404));
        assertFalse(YahooFinanceClient.isRateLimitStatus(500));
    }

    @Test
    void breakerOpensAfterTripAndShortCircuitsSearch() {
        YahooFinanceClient c = new YahooFinanceClient(10, 300);
        assertFalse(c.breakerOpen(), "fresh client: breaker closed");
        c.tripBreaker("test", 429);
        assertTrue(c.breakerOpen(), "after a 429 the breaker is open");
        // While open, search short-circuits (no HTTP) and reports the rate-limit
        // signal so callers skip the subject instead of treating it as 'no result'.
        assertTrue(c.search("Nvidia", 5, 0).rateLimited(), "open breaker → search is throttled");
    }

    @Test
    void parsesQuoteAndNewsFromSearchResponse() {
        String body = """
                {
                  "quotes": [
                    {
                      "exchange": "NMS",
                      "shortname": "NVIDIA Corporation",
                      "longname": "NVIDIA Corporation",
                      "quoteType": "EQUITY",
                      "symbol": "NVDA",
                      "exchDisp": "NASDAQ",
                      "sector": "Technology",
                      "industry": "Semiconductors",
                      "regularMarketPrice": 145.30,
                      "regularMarketPercentChange": -1.23
                    },
                    {
                      "exchange": "GER",
                      "shortname": "RHEINMETALL AG                I",
                      "longname": "Rheinmetall AG",
                      "quoteType": "EQUITY",
                      "symbol": "RHM.DE",
                      "exchDisp": "XETRA",
                      "sector": "Industrials",
                      "industry": "Aerospace & Defense"
                    }
                  ],
                  "news": [
                    {
                      "uuid": "abc-123",
                      "title": "NVDA Drops 10% on Huawei AI News",
                      "publisher": "Stocktwits",
                      "link": "https://finance.yahoo.com/m/abc-123/nvda.html",
                      "providerPublishTime": 1779941029,
                      "type": "STORY",
                      "relatedTickers": ["NVDA", "AMD"]
                    }
                  ]
                }
                """;

        YahooFinanceClient.SearchResult result = client.parseSearch(body);

        assertEquals(2, result.quotes().size());
        YahooQuote nvda = result.quotes().get(0);
        assertEquals("NVDA", nvda.symbol());
        assertEquals("NVIDIA Corporation", nvda.shortName());
        assertEquals("NASDAQ", nvda.exchangeDisplay());
        assertEquals("Technology", nvda.sector());
        assertEquals(-1.23, nvda.regularMarketPercentChange(), 0.0001);

        YahooQuote rhm = result.quotes().get(1);
        assertEquals("RHM.DE", rhm.symbol());
        assertEquals("XETRA", rhm.exchangeDisplay());
        // Yahoo pads shortname; displayName should still render cleanly via longname
        assertEquals("Rheinmetall AG", rhm.displayName());
        // No price data on the .DE quote → NaN
        assertTrue(Double.isNaN(rhm.regularMarketPercentChange()));

        assertEquals(1, result.news().size());
        RawNewsItem n = result.news().get(0);
        assertEquals("abc-123", n.uuid());
        assertEquals("Stocktwits", n.publisher());
        assertEquals(List.of("NVDA", "AMD"), n.relatedTickers());
        assertNotNull(n.publishedAt());
    }

    @Test
    void skipsQuotesWithoutSymbol() {
        String body = """
                {"quotes": [{"shortname":"Nameless","quoteType":"EQUITY"}], "news": []}
                """;
        YahooFinanceClient.SearchResult result = client.parseSearch(body);
        assertTrue(result.quotes().isEmpty());
    }

    @Test
    void skipsNewsWithoutTitle() {
        String body = """
                {"quotes": [], "news": [{"uuid":"x","publisher":"y"}]}
                """;
        YahooFinanceClient.SearchResult result = client.parseSearch(body);
        assertTrue(result.news().isEmpty());
    }

    @Test
    void returnsEmptyResultOnMalformedJson() {
        YahooFinanceClient.SearchResult result = client.parseSearch("not json at all");
        assertTrue(result.quotes().isEmpty());
        assertTrue(result.news().isEmpty());
    }

    @Test
    void displayNameFallsBackToShortNameWhenLongNameMissing() {
        YahooQuote q = new YahooQuote("NVDL", "GraniteShares 2x Long NVDA Daily ETF", "",
                "ETF", "NGM", "NASDAQ", "", "", Double.NaN, Double.NaN, 0.0);
        assertEquals("GraniteShares 2x Long NVDA Daily ETF", q.displayName());
    }

    @Test
    void parsesChartSnapshotWithSparkAndComputedChange() {
        String body = """
                {
                  "chart": {
                    "result": [
                      {
                        "meta": {
                          "symbol": "NVDA",
                          "currency": "USD",
                          "exchangeName": "NMS",
                          "regularMarketPrice": 214.25,
                          "previousClose": 212.60,
                          "regularMarketDayHigh": 215.52,
                          "regularMarketDayLow": 211.22,
                          "regularMarketVolume": 141557394,
                          "fiftyTwoWeekHigh": 236.54,
                          "fiftyTwoWeekLow": 132.92,
                          "regularMarketTime": 1779998400
                        },
                        "timestamp": [1, 2, 3, 4],
                        "indicators": {
                          "quote": [
                            { "close": [212.0, null, 213.5, 214.25] }
                          ]
                        }
                      }
                    ],
                    "error": null
                  }
                }
                """;

        MarketSnapshot s = client.parseChart(body);

        assertNotNull(s);
        assertEquals("NVDA", s.symbol());
        assertEquals(214.25, s.price(), 1e-6);
        assertEquals(212.60, s.previousClose(), 1e-6);
        // (214.25 - 212.60) / 212.60 * 100 ≈ 0.776%
        assertEquals(0.776, s.dayChangePercent(), 0.01);
        assertEquals(141557394L, s.volume());
        assertEquals(236.54, s.fiftyTwoWeekHigh(), 1e-6);
        assertEquals("USD", s.currency());
        assertTrue(s.hasPrice());
        assertTrue(s.hasSpark());
        // null gap dropped → 3 finite points
        assertEquals(List.of(212.0, 213.5, 214.25), s.spark());
    }

    @Test
    void chartFallsBackToChartPreviousCloseForChange() {
        String body = """
                { "chart": { "result": [ {
                    "meta": { "symbol": "X", "regularMarketPrice": 110.0, "chartPreviousClose": 100.0 },
                    "indicators": { "quote": [ { "close": [100.0, 110.0] } ] }
                } ], "error": null } }
                """;
        MarketSnapshot s = client.parseChart(body);
        assertNotNull(s);
        assertEquals(10.0, s.dayChangePercent(), 1e-6);
        assertEquals(-1L, s.volume());
    }

    @Test
    void chartReturnsNullOnErrorBody() {
        String body = """
                { "chart": { "result": null, "error": { "code": "Not Found" } } }
                """;
        assertNull(client.parseChart(body));
        assertNull(client.parseChart("not json"));
    }
}
