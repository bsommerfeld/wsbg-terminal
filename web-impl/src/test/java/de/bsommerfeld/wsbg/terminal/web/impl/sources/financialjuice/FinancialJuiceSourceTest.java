package de.bsommerfeld.wsbg.terminal.web.impl.sources.financialjuice;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FinancialJuice RSS parsing — ported from the module world. The old
 * cross-call GUID dedup ({@code seenGuids}) is GONE by design: the pool
 * de-duplicates on {@link Article#uuid()}, so {@code collect()} returns the
 * feed's full current window every pass.
 */
class FinancialJuiceSourceTest {

    /** Fake transport answering a fixed body, counting fetches. */
    private static final class FakeFetcher implements WebFetcher {
        final AtomicInteger fetches = new AtomicInteger();
        private final WebResponse reply;

        FakeFetcher(WebResponse reply) {
            this.reply = reply;
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            assertEquals("https://www.financialjuice.com/feed.ashx?xy=rss", url);
            assertEquals(FetchUtil.DIRECT, modes[0], "the feed answers a plain client");
            fetches.incrementAndGet();
            return reply;
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
    }

    private FinancialJuiceSource source;

    @BeforeEach
    void setUp() {
        source = new FinancialJuiceSource(
                new FakeFetcher(WebResponse.text(500, "", Map.of())));
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

        List<Article> items = source.parseRss(xml);

        assertEquals(2, items.size());

        Article first = items.get(0);
        assertEquals("123", first.uuid());
        assertEquals("ECB holds rates steady", first.title());
        assertEquals("https://www.financialjuice.com/News/123/ECB-holds.aspx?xy=rss", first.link());
        assertTrue(first.summary().contains("ECB decided to hold"));
        assertEquals("FinancialJuice", first.publisher());
        assertNotNull(first.publishedAt());

        Article second = items.get(1);
        assertEquals("456", second.uuid());
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

        Article item = source.parseRss(xml).getFirst();
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

        Article item = source.parseRss(xml).getFirst();
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

        Article item = source.parseRss(xml).getFirst();
        assertFalse(item.summary().contains("<br"));
        assertTrue(item.summary().contains("S&P 500: -410 mln"));
        assertTrue(item.summary().contains("Nasdaq: -5 mln"));
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

        Article item = source.parseRss(xml).getFirst();
        assertEquals("", item.summary());
    }

    @Test
    void handlesInvalidXmlGracefully() {
        List<Article> items = source.parseRss("not xml at all <><><>");
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

        Article item = source.parseRss(xml).getFirst();
        assertNull(item.publishedAt());
        assertEquals("Test", item.title());
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

        Article item = source.parseRss(xml).getFirst();
        assertTrue(item.summary().contains("Bold text"));
        assertTrue(item.summary().contains("Normal text"));
        assertFalse(item.summary().contains("<strong>"));
        assertFalse(item.summary().contains("<div>"));
    }

    @Test
    void preservesLineStructureInMultiLineBody() {
        // FinancialJuice separates the lines of an earnings body with <br>;
        // those boundaries must survive as newlines so the UI can render
        // one entry per line in the expandable card.
        String xml = """
                <rss version="2.0"><channel>
                <item>
                <title>$COST Costco Wholesale Q3 Earnings</title>
                <link>https://example.com</link>
                <description>$COST Costco Wholesale Q3 Earnings&lt;br /&gt;EPS $4.93, est. $4.91&lt;br /&gt;Total Revenue $70.53B, est. $69.62B</description>
                <author>FJ</author>
                <pubDate>Thu, 05 Mar 2026 14:00:00 GMT</pubDate>
                <guid isPermaLink="false">555</guid>
                </item>
                </channel></rss>
                """;

        Article item = source.parseRss(xml).getFirst();
        String[] lines = item.summary().split("\n");
        assertEquals(3, lines.length);
        assertEquals("$COST Costco Wholesale Q3 Earnings", lines[0]);
        assertEquals("EPS $4.93, est. $4.91", lines[1]);
        assertEquals("Total Revenue $70.53B, est. $69.62B", lines[2]);
    }

    @Test
    void collectReturnsTheFullCurrentWindowEveryPass() {
        String xml = """
                <rss version="2.0"><channel>
                <item>
                <title>FinancialJuice: One</title>
                <link>https://example.com/1</link>
                <description/>
                <author>FJ</author>
                <pubDate>Thu, 05 Mar 2026 14:00:00 GMT</pubDate>
                <guid isPermaLink="false">AAA</guid>
                </item>
                </channel></rss>
                """;
        FakeFetcher fetcher = new FakeFetcher(WebResponse.text(200, xml, Map.of()));
        FinancialJuiceSource live = new FinancialJuiceSource(fetcher);

        assertEquals(1, live.collect().size());
        assertEquals(1, live.collect().size(),
                "NO cross-call dedup in the source — the pool folds repeats on uuid");
        assertEquals(2, fetcher.fetches.get());
    }

    @Test
    void collectAnswersEmptyOnHttpError() {
        FakeFetcher fetcher = new FakeFetcher(WebResponse.text(429, "slow down", Map.of()));
        FinancialJuiceSource live = new FinancialJuiceSource(fetcher);
        assertTrue(live.collect().isEmpty());
    }
}
