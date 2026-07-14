package de.bsommerfeld.wsbg.terminal.nasdaq;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the live feed shape (2026-07-14): the namespaced {@code nasdaq:tickers}
 * element with duplicates, {@code dc:creator} as the publisher, and the
 * symbol-shape gate that keeps suffixed/caret symbols off the network.
 */
class NasdaqNewsRssClientTest {

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

    @Test
    void parsesItemsWithTickersAndCreator() {
        List<RawNewsItem> items = NasdaqNewsRssClient.parse(FEED_XML);
        assertEquals(2, items.size());
        RawNewsItem first = items.get(0);
        assertTrue(first.title().startsWith("Buying TSMC Stock"));
        assertEquals("The Motley Fool", first.publisher());
        assertEquals(List.of("TSM", "AAPL", "NVDA", "AMD"), first.relatedTickers());
        assertEquals(Instant.parse("2026-07-14T15:06:00Z"), first.publishedAt());
        assertTrue(first.summary().contains("largest increase"));
        assertEquals("https://www.nasdaq.com/articles/buying-tsmc?time=1784041560", first.uuid());
    }

    @Test
    void garbageYieldsEmpty() {
        assertTrue(NasdaqNewsRssClient.parse(null).isEmpty());
        assertTrue(NasdaqNewsRssClient.parse("<html>wall</html>").isEmpty());
        assertFalse(NasdaqNewsRssClient.looksLikeRss("<html>wall</html>"));
        assertTrue(NasdaqNewsRssClient.looksLikeRss(FEED_XML));
    }

    @Test
    void symbolGateKeepsNonUsShapesOffTheNetwork() {
        NasdaqNewsRssClient client = new NasdaqNewsRssClient(
                new de.bsommerfeld.wsbg.terminal.source.net.WebFetcher() {
                    @Override
                    public String name() {
                        return "test";
                    }

                    @Override
                    public de.bsommerfeld.wsbg.terminal.source.net.WebResponse fetch(
                            String url, java.util.Map<String, String> headers,
                            java.time.Duration timeout) {
                        throw new AssertionError("network touched for gated symbol: " + url);
                    }
                });
        assertTrue(client.newsFor("RHM.DE", 5).isEmpty());
        assertTrue(client.newsFor("^GDAXI", 5).isEmpty());
        assertTrue(client.newsFor("BTC-USD", 5).isEmpty());
        assertTrue(client.newsFor(null, 5).isEmpty());
    }

    @Test
    void splitTickersDeduplicatesKeepingOrder() {
        assertEquals(List.of("TSM", "AAPL"), NasdaqNewsRssClient.splitTickers("TSM, TSM ,AAPL"));
        assertTrue(NasdaqNewsRssClient.splitTickers(null).isEmpty());
    }
}
