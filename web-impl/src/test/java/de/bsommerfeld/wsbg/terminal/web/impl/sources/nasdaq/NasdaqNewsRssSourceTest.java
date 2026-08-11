package de.bsommerfeld.wsbg.terminal.web.impl.sources.nasdaq;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the live feed shape (2026-07-14): the namespaced {@code nasdaq:tickers}
 * element with duplicates, {@code dc:creator} as the publisher, and the
 * symbol-shape gate that keeps suffixed/caret symbols off the network.
 * Ported from the module world.
 */
class NasdaqNewsRssSourceTest {

    private static final String FEED_XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss xmlns:dc="http://purl.org/dc/elements/1.1/"
                 xmlns:nasdaq="http://nasdaq.com/reference/feeds/1.0" version="2.0">
             <channel>
              <title>AAPL Feed</title>
              <item>
               <title>Buying TSMC Stock Before July 16 Just Became a No-Brainer</title>
               <link>https://www.nasdaq.com/articles/buying-tsmc</link>
               <description>
                    Key PointsTSMC has just reported its largest increase.
                </description>
               <pubDate>Tue, 14 Jul 2026 15:06:00 +0000</pubDate>
               <guid isPermaLink="true">https://www.nasdaq.com/articles/buying-tsmc?time=1784041560</guid>
               <dc:creator>The Motley Fool</dc:creator>
               <category>Markets</category>
               <nasdaq:tickers>TSM,TSM,AAPL,NVDA,AMD</nasdaq:tickers>
              </item>
              <item>
               <title>Trending Stock Facts</title>
               <link>https://www.nasdaq.com/articles/trending</link>
               <pubDate>Tue, 14 Jul 2026 13:00:02 +0000</pubDate>
               <dc:creator>Zacks</dc:creator>
               <nasdaq:tickers>AAPL</nasdaq:tickers>
              </item>
             </channel>
            </rss>
            """;

    private static ResolvedInstrument byTicker(String symbol) {
        return new ResolvedInstrument(Optional.empty(), Ticker.parse(symbol), "");
    }

    @Test
    void parsesItemsWithTickersAndCreator() {
        List<Article> items = NasdaqNewsRssSource.parse(FEED_XML);
        assertEquals(2, items.size());
        Article first = items.get(0);
        assertTrue(first.title().startsWith("Buying TSMC Stock"));
        assertEquals("The Motley Fool", first.publisher());
        assertEquals(List.of("TSM", "AAPL", "NVDA", "AMD"), first.relatedTickers());
        assertEquals(Instant.parse("2026-07-14T15:06:00Z"), first.publishedAt());
        assertTrue(first.summary().contains("largest increase"));
        assertEquals("https://www.nasdaq.com/articles/buying-tsmc?time=1784041560", first.uuid());
    }

    @Test
    void garbageYieldsEmpty() {
        assertTrue(NasdaqNewsRssSource.parse(null).isEmpty());
        assertTrue(NasdaqNewsRssSource.parse("<html>wall</html>").isEmpty());
        assertFalse(NasdaqNewsRssSource.looksLikeRss("<html>wall</html>"));
        assertTrue(NasdaqNewsRssSource.looksLikeRss(FEED_XML));
    }

    @Test
    void symbolGateKeepsNonUsShapesOffTheNetwork() throws Exception {
        NasdaqNewsRssSource source = new NasdaqNewsRssSource(new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers,
                    Duration timeout, FetchUtil... modes) {
                throw new AssertionError("network touched for gated symbol: " + url);
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
                return false;
            }
        });
        assertTrue(source.newsFor(byTicker("RHM.DE"), 5).isEmpty());
        assertTrue(source.newsFor(byTicker("BTC-USD"), 5).isEmpty());
        assertTrue(source.newsFor(ResolvedInstrument.ofName("Rheinmetall"), 5).isEmpty(),
                "the feed is symbol-addressed — a bare name never fetches");
        assertTrue(source.newsFor(null, 5).isEmpty());
    }

    @Test
    void instrumentFanFetchesOnceAndCaps() throws Exception {
        int[] fetches = {0};
        NasdaqNewsRssSource source = new NasdaqNewsRssSource(new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers,
                    Duration timeout, FetchUtil... modes) {
                fetches[0]++;
                assertEquals(FetchUtil.DIRECT, modes[0], "declared mode order rides the fetch");
                assertTrue(url.endsWith("symbol=AAPL"));
                return WebResponse.text(200, FEED_XML, Map.of());
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
                return false;
            }
        });
        assertEquals(1, source.newsFor(byTicker("AAPL"), 1).size(), "limit caps the answer");
        assertEquals(2, source.newsFor(byTicker("AAPL"), 10).size());
        assertEquals(1, fetches[0], "the per-symbol politeness cache answers the burst");
    }

    @Test
    void splitTickersDeduplicatesKeepingOrder() {
        assertEquals(List.of("TSM", "AAPL"), NasdaqNewsRssSource.splitTickers("TSM, TSM ,AAPL"));
        assertTrue(NasdaqNewsRssSource.splitTickers(null).isEmpty());
    }
}
