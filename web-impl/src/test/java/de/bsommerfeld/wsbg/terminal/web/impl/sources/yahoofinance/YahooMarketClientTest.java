package de.bsommerfeld.wsbg.terminal.web.impl.sources.yahoofinance;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.FakeFetchers;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class YahooMarketClientTest {

    private final YahooMarketClient client =
            new YahooMarketClient(FakeFetchers.noNetwork(), 10, 300);

    // ---- 429 politeness (the fetcher's host cooldown replaced the old breaker) ----

    @Test
    void rateLimitStatusOnlyForBackoffCodes() {
        assertTrue(YahooMarketClient.isRateLimitStatus(429));
        assertTrue(YahooMarketClient.isRateLimitStatus(503));
        assertTrue(YahooMarketClient.isRateLimitStatus(999));
        assertFalse(YahooMarketClient.isRateLimitStatus(200));
        assertFalse(YahooMarketClient.isRateLimitStatus(404));
        assertFalse(YahooMarketClient.isRateLimitStatus(500));
    }

    @Test
    void hostCooldownShortCircuitsSearchAsThrottled() {
        // While the house fetcher reports Yahoo in cooldown, search short-circuits
        // (no HTTP) and reports the rate-limit signal so callers skip the subject
        // instead of treating it as 'no result'.
        WebFetcher cooling = new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                    FetchUtil... modes) {
                throw new AssertionError("no HTTP expected while the host cools down: " + url);
            }

            @Override
            public WebResponse fetchBinary(String url, Map<String, String> headers,
                    Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WebResponse post(String url, Map<String, String> headers, String body,
                    String contentType, Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hostCoolingDown(String url) {
                return true;
            }
        };
        YahooMarketClient c = new YahooMarketClient(cooling, 10, 300);
        assertTrue(c.search("Nvidia", 5, 0).rateLimited(), "cooldown → search is throttled");
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

        YahooMarketClient.SearchResult result = client.parseSearch(body);

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
        Article n = result.news().get(0);
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
        YahooMarketClient.SearchResult result = client.parseSearch(body);
        assertTrue(result.quotes().isEmpty());
    }

    @Test
    void skipsNewsWithoutTitle() {
        String body = """
                {"quotes": [], "news": [{"uuid":"x","publisher":"y"}]}
                """;
        YahooMarketClient.SearchResult result = client.parseSearch(body);
        assertTrue(result.news().isEmpty());
    }

    @Test
    void returnsEmptyResultOnMalformedJson() {
        YahooMarketClient.SearchResult result = client.parseSearch("not json at all");
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
