package de.bsommerfeld.wsbg.terminal.yahoofinance;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YahooFinanceClientTest {

    private final YahooFinanceClient client = new YahooFinanceClient(10, 300);

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
        YahooNewsItem n = result.news().get(0);
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
                "ETF", "NGM", "NASDAQ", "", "", Double.NaN, Double.NaN);
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

    @Test
    void extractsParagraphTextAndDropsBoilerplate() {
        String html = """
                <html><head><title>x</title>
                  <style>.a{color:red}</style>
                  <script>var tracker = 1; document.write('junk');</script>
                </head><body>
                  <nav><a href="/">Home</a><a href="/markets">Markets</a></nav>
                  <p>Menu</p>
                  <article>
                    <p>Exxon Mobil shares slipped 1.2% as oil retreated on easing Strait of Hormuz tensions, with traders unwinding the geopolitical risk premium that had built up over the prior week.</p>
                    <p>Analysts at Barclays still see meaningful upside for the integrated majors if the Iran conflict re-escalates this summer, citing tight global inventories and constrained spare capacity.</p>
                  </article>
                  <footer><p>© 2026 Publisher</p></footer>
                </body></html>
                """;
        String text = YahooFinanceClient.extractReadableText(html);
        assertTrue(text.contains("Exxon Mobil shares slipped 1.2%"), text);
        assertTrue(text.contains("Barclays"), text);
        assertFalse(text.contains("tracker"), "script body must be stripped");
        assertFalse(text.contains("color:red"), "style body must be stripped");
        assertFalse(text.contains("Home"), "short nav <p>/links should be dropped");
    }

    @Test
    void extractFallsBackAndUnescapesAndCaps() {
        // No <p> at all → falls back to whole-body strip; entities decoded.
        String html = "<div>AT&amp;T &mdash; Q1 rose 3% &#39;solid&#39; quarter</div>";
        String text = YahooFinanceClient.extractReadableText(html);
        assertEquals("AT&T — Q1 rose 3% 'solid' quarter", text);

        assertEquals("", YahooFinanceClient.extractReadableText(null));
        assertEquals("", YahooFinanceClient.extractReadableText("   "));

        String big = "<div>" + "word ".repeat(4000) + "</div>";
        String capped = YahooFinanceClient.extractReadableText(big);
        assertTrue(capped.length() <= 6002, "must cap near ARTICLE_MAX_CHARS, was " + capped.length());
        assertTrue(capped.endsWith("…"), "capped text marks truncation");
    }
}
