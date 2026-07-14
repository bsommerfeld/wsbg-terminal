package de.bsommerfeld.wsbg.terminal.websearch;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Bing search-RSS parse: direct links, host as publisher, no lying dates. */
class BingWebSearchClientTest {

    private static final String FEED = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0"><channel>
              <title>SAP News - Bing</title>
              <item>
                <title>EU verh&#228;ngt Kartellstrafe gegen &lt;b&gt;SAP&lt;/b&gt;</title>
                <link>https://www.heise.de/news/eu-sap-strafe-123.html</link>
                <description>Die EU-Kommission hat gegen SAP eine Strafe verh&#228;ngt &#8230;</description>
                <pubDate>Mo, 13 Juli 2026 11:23:00 GMT</pubDate>
              </item>
              <item>
                <title>SAP Aktie aktuell</title>
                <link>https://finanzen.net/aktien/sap-aktie</link>
                <description></description>
              </item>
              <item><title>ohne Link</title></item>
            </channel></rss>""";

    @Test
    void parsesDirectLinksWithHostAsPublisher() {
        List<RawNewsItem> items = BingWebSearchClient.parse(FEED);
        assertEquals(2, items.size(), String.valueOf(items));
        RawNewsItem first = items.get(0);
        assertTrue(first.title().contains("Kartellstrafe"), first.title());
        assertFalse(first.title().contains("<b>"), "tags stripped from titles");
        assertEquals("heise.de", first.publisher());
        assertEquals("https://www.heise.de/news/eu-sap-strafe-123.html", first.link());
        assertNull(first.publishedAt(), "Bing's pubDate is the crawl date — never trusted");
        assertTrue(first.summary().contains("EU-Kommission"), first.summary());
        assertEquals("finanzen.net", items.get(1).publisher());
    }

    @Test
    void wallsAndGarbageYieldEmpty() {
        assertFalse(BingWebSearchClient.looksLikeRss(
                "<!DOCTYPE html><html>Turnstile challenge…"));
        assertTrue(BingWebSearchClient.parse("<html>wall</html>").isEmpty());
        assertTrue(BingWebSearchClient.parse(null).isEmpty());
    }
}
