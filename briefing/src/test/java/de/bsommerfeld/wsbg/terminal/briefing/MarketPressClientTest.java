package de.bsommerfeld.wsbg.terminal.briefing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the live-probed feed quirks (2026-07-14): CNBC's CDATA descriptions and
 * second-less pubDates, Investing.com's zone-less pubDate (read as GMT), the
 * cross-feed title dedupe key, and garbage tolerance.
 */
class MarketPressClientTest {

    private static final MarketPressClient.Feed CNBC =
            new MarketPressClient.Feed("CNBC", "US_ECONOMY", "http://test");
    private static final MarketPressClient.Feed INVESTING =
            new MarketPressClient.Feed("Investing.com", "US_MARKETS", "http://test");

    private static final String CNBC_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:metadata="http://search.cnbc.com/rss/2.0/modules/siteContentMetadata">
              <channel>
                <title>Economy</title>
                <item>
                  <link>https://www.cnbc.com/2026/07/14/cpi.html</link>
                  <guid isPermaLink="false">108334569</guid>
                  <title>Consumer prices rose 3.5% annually in June, less than expected</title>
                  <description><![CDATA[The consumer price index in June was expected to increase 3.8%.]]></description>
                  <pubDate>Tue, 14 Jul 2026 15:45:07 GMT</pubDate>
                </item>
                <item>
                  <link>https://www.cnbc.com/2026/07/14/other.html</link>
                  <title>Second story</title>
                  <pubDate>Tue, 14 Jul 2026 14:20 GMT</pubDate>
                </item>
              </channel>
            </rss>
            """;

    private static final String INVESTING_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <item>
                  <enclosure url="https://x/img.jpg" length="1" type="image/jpeg" />
                  <title>New York becomes the first state to impose a data center moratorium</title>
                  <pubDate>2026-07-14 17:54:56</pubDate>
                  <author>Reuters</author>
                  <link>https://www.investing.com/news/stock-market-news/ny-4789994</link>
                </item>
              </channel>
            </rss>
            """;

    @Test
    void parsesCnbcItemsWithCdataAndTag() {
        List<MarketPressClient.PressHeadline> items =
                MarketPressClient.parseFeed(CNBC, CNBC_XML);
        assertEquals(2, items.size());
        MarketPressClient.PressHeadline first = items.get(0);
        assertTrue(first.title().startsWith("Consumer prices rose 3.5%"));
        assertTrue(first.teaser().contains("expected to increase 3.8%"));
        assertEquals("CNBC", first.source());
        assertEquals("US_ECONOMY", first.category());
        assertEquals(Instant.parse("2026-07-14T15:45:07Z"), first.publishedAt());
    }

    @Test
    void parsesInvestingZonelessPubDateAsUtc() {
        List<MarketPressClient.PressHeadline> items =
                MarketPressClient.parseFeed(INVESTING, INVESTING_XML);
        assertEquals(1, items.size());
        assertEquals(Instant.parse("2026-07-14T17:54:56Z"), items.get(0).publishedAt());
        assertNull(items.get(0).teaser());
    }

    private static final MarketPressClient.Feed REUTERS =
            new MarketPressClient.Feed("Reuters", "WORLD", "http://test");

    private static final String REUTERS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:news="http://www.google.com/schemas/sitemap-news/0.9" xmlns:image="http://www.google.com/schemas/sitemap-image/1.1">
              <url>
                <loc>https://www.reuters.com/business/csquare-nyse-debut-2026-07-16/</loc>
                <lastmod>2026-07-16T16:54:43.616Z</lastmod>
                <news:news>
                  <news:publication><news:name>Reuters</news:name><news:language>en</news:language></news:publication>
                  <news:publication_date>2026-07-16T16:54:42.616Z</news:publication_date>
                  <news:title><![CDATA[Brookfield-backed Csquare valued at $3.24 billion in NYSE debut]]></news:title>
                </news:news>
                <image:image><image:loc>https://www.reuters.com/resizer/x.jpg</image:loc></image:image>
              </url>
              <url>
                <loc>https://www.reuters.com/world/booms-heard-dubai-2026-07-16/</loc>
                <news:news>
                  <news:publication_date>2026-07-16T16:40:00Z</news:publication_date>
                  <news:title><![CDATA[Booms heard in UAE's downtown Dubai, witnesses say]]></news:title>
                </news:news>
              </url>
            </urlset>
            """;

    @Test
    void parsesReutersNewsSitemapWithPerItemDesk() {
        List<MarketPressClient.PressHeadline> items =
                MarketPressClient.parseFeed(REUTERS, REUTERS_XML);
        assertEquals(2, items.size());

        MarketPressClient.PressHeadline csquare = items.get(0);
        assertTrue(csquare.title().startsWith("Brookfield-backed Csquare"));
        assertEquals("Reuters", csquare.source());
        assertEquals("US_MARKETS", csquare.category(),
                "a /business/ URL reports the tape, not the WORLD fallback");
        assertEquals(Instant.parse("2026-07-16T16:54:42.616Z"), csquare.publishedAt());
        assertEquals("https://www.reuters.com/business/csquare-nyse-debut-2026-07-16/",
                csquare.link(), "the article loc, never the image:loc of the same entry");
        assertNull(csquare.teaser(), "a sitemap carries no teaser");

        assertEquals("WORLD", items.get(1).category(),
                "a non-business desk keeps the feed's fallback category");
    }

    @Test
    void sitemapCategoryReadsTheDeskFromTheUrlPath() {
        assertEquals("US_MARKETS", MarketPressClient.sitemapCategory(
                "https://www.reuters.com/markets/gold-falls-2026-07-16/", "WORLD"));
        assertEquals("US_TECH", MarketPressClient.sitemapCategory(
                "https://www.reuters.com/technology/chip-story/", "WORLD"));
        assertEquals("WORLD", MarketPressClient.sitemapCategory(
                "https://www.reuters.com/sports/world-cup/", "WORLD"));
        assertEquals("WORLD", MarketPressClient.sitemapCategory(null, "WORLD"));
    }

    @Test
    void garbageYieldsEmpty() {
        assertTrue(MarketPressClient.parseFeed(CNBC, null).isEmpty());
        assertTrue(MarketPressClient.parseFeed(CNBC, "<html>bot wall</html>").isEmpty());
    }

    @Test
    void titleDedupeKeyIgnoresPunctuationAndCase() {
        assertEquals(
                MarketPressClient.normalizeTitle("CPI rose 3.5% — less than expected!"),
                MarketPressClient.normalizeTitle("cpi rose 3 5 less than expected"));
    }
}
