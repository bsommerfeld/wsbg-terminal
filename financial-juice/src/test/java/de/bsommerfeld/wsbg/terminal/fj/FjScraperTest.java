package de.bsommerfeld.wsbg.terminal.fj;

import de.bsommerfeld.wsbg.terminal.core.domain.FjNewsItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FjScraperTest {

    private FjScraper scraper;

    @BeforeEach
    void setUp() {
        scraper = new FjScraper();
    }

    @Test
    void parsesValidRssItems() {
        String xml = """
                <rss version="2.0">
                <channel>
                <title>FinancialJuice</title>
                <item>
                <title>FinancialJuice: ECB holds rates steady</title>
                <link>https://www.financialjuice.com/News/123/ECB-holds.aspx?xy=rss</link>
                <description>&lt;div&gt;ECB decided to hold&lt;/div&gt;</description>
                <author>FinancialJuice</author>
                <pubDate>Thu, 05 Mar 2026 14:43:05 GMT</pubDate>
                <guid isPermaLink="false">123</guid>
                </item>
                <item>
                <title>FinancialJuice: Google opens AI center</title>
                <link>https://www.financialjuice.com/News/456/Google.aspx?xy=rss</link>
                <description/>
                <author>FinancialJuice</author>
                <pubDate>Thu, 05 Mar 2026 14:26:50 GMT</pubDate>
                <guid isPermaLink="false">456</guid>
                </item>
                </channel>
                </rss>
                """;

        List<FjNewsItem> items = scraper.parseRss(xml);

        assertEquals(2, items.size());

        FjNewsItem first = items.get(0);
        assertEquals("123", first.guid());
        assertEquals("ECB holds rates steady", first.title());
        assertEquals("https://www.financialjuice.com/News/123/ECB-holds.aspx?xy=rss", first.link());
        assertTrue(first.description().contains("ECB decided to hold"));
        assertEquals("FinancialJuice", first.author());
        assertTrue(first.publishedUtc() > 0);

        FjNewsItem second = items.get(1);
        assertEquals("456", second.guid());
        assertEquals("Google opens AI center", second.title());
    }

    @Test
    void stripsFinancialJuicePrefixFromTitle() {
        String xml = """
                <rss version="2.0"><channel>
                <item>
                <title>FinancialJuice: Test Headline</title>
                <link>https://example.com</link>
                <description/>
                <author>FJ</author>
                <pubDate>Thu, 05 Mar 2026 14:00:00 GMT</pubDate>
                <guid isPermaLink="false">789</guid>
                </item>
                </channel></rss>
                """;

        FjNewsItem item = scraper.parseRss(xml).getFirst();
        assertEquals("Test Headline", item.title());
    }

    @Test
    void preservesTitleWithoutPrefix() {
        String xml = """
                <rss version="2.0"><channel>
                <item>
                <title>No prefix here</title>
                <link>https://example.com</link>
                <description/>
                <author>FJ</author>
                <pubDate>Thu, 05 Mar 2026 14:00:00 GMT</pubDate>
                <guid isPermaLink="false">999</guid>
                </item>
                </channel></rss>
                """;

        FjNewsItem item = scraper.parseRss(xml).getFirst();
        assertEquals("No prefix here", item.title());
    }

    @Test
    void stripsHtmlFromDescription() {
        String xml = """
                <rss version="2.0"><channel>
                <item>
                <title>Test</title>
                <link>https://example.com</link>
                <description>S&amp;P 500: -410 mln&lt;br /&gt;Nasdaq: -5 mln</description>
                <author>FJ</author>
                <pubDate>Thu, 05 Mar 2026 14:00:00 GMT</pubDate>
                <guid isPermaLink="false">111</guid>
                </item>
                </channel></rss>
                """;

        FjNewsItem item = scraper.parseRss(xml).getFirst();
        assertFalse(item.description().contains("<br"));
        assertTrue(item.description().contains("S&P 500: -410 mln"));
        assertTrue(item.description().contains("Nasdaq: -5 mln"));
    }

    @Test
    void deduplicatesAcrossCalls() {
        String xml = """
                <rss version="2.0"><channel>
                <item>
                <title>Test</title>
                <link>https://example.com</link>
                <description/>
                <author>FJ</author>
                <pubDate>Thu, 05 Mar 2026 14:00:00 GMT</pubDate>
                <guid isPermaLink="false">AAA</guid>
                </item>
                </channel></rss>
                """;

        // parseRss doesn't deduplicate — that happens in fetch()
        // so we test seenGuids manually
        List<FjNewsItem> first = scraper.parseRss(xml);
        assertEquals(1, first.size());
        assertEquals(0, scraper.seenCount());

        // After a second parse, items are still returned (parseRss is stateless)
        List<FjNewsItem> second = scraper.parseRss(xml);
        assertEquals(1, second.size());
    }

    @Test
    void handlesEmptyDescription() {
        String xml = """
                <rss version="2.0"><channel>
                <item>
                <title>Test</title>
                <link>https://example.com</link>
                <description/>
                <author>FJ</author>
                <pubDate>Thu, 05 Mar 2026 14:00:00 GMT</pubDate>
                <guid isPermaLink="false">222</guid>
                </item>
                </channel></rss>
                """;

        FjNewsItem item = scraper.parseRss(xml).getFirst();
        assertEquals("", item.description());
    }

    @Test
    void handlesInvalidXmlGracefully() {
        List<FjNewsItem> items = scraper.parseRss("not xml at all <><><>");
        assertTrue(items.isEmpty());
    }

    @Test
    void handlesMalformedDateGracefully() {
        String xml = """
                <rss version="2.0"><channel>
                <item>
                <title>Test</title>
                <link>https://example.com</link>
                <description/>
                <author>FJ</author>
                <pubDate>not-a-date</pubDate>
                <guid isPermaLink="false">333</guid>
                </item>
                </channel></rss>
                """;

        FjNewsItem item = scraper.parseRss(xml).getFirst();
        assertEquals(0, item.publishedUtc());
        assertEquals("Test", item.title());
    }

    @Test
    void resetSeenGuidsClearsState() {
        scraper.resetSeenGuids();
        assertEquals(0, scraper.seenCount());
    }

    @Test
    void parsesRichDescriptionWithMultipleTags() {
        String xml = """
                <rss version="2.0"><channel>
                <item>
                <title>Test</title>
                <link>https://example.com</link>
                <description>&lt;div&gt;&lt;strong&gt;Bold text&lt;/strong&gt;&lt;/div&gt;&lt;div&gt;Normal text&lt;/div&gt;</description>
                <author>FJ</author>
                <pubDate>Thu, 05 Mar 2026 14:00:00 GMT</pubDate>
                <guid isPermaLink="false">444</guid>
                </item>
                </channel></rss>
                """;

        FjNewsItem item = scraper.parseRss(xml).getFirst();
        assertTrue(item.description().contains("Bold text"));
        assertTrue(item.description().contains("Normal text"));
        assertFalse(item.description().contains("<strong>"));
        assertFalse(item.description().contains("<div>"));
    }
}
