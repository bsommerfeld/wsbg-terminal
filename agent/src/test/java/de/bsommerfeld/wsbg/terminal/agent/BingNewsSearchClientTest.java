package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Bing news shelf: the RSS read and the click tracker it unwraps. */
class BingNewsSearchClientTest {

    /** Captured verbatim from a live run's book of sources. */
    private static final String APICLICK =
            "https://www.bing.com/news/apiclick.aspx?ref=FexRss&aid=&tid="
                    + "6a75d80840294ac99155a5d19d12b48b&url=https%3a%2f%2fwww.swr.de"
                    + "%2fswraktuell%2fbaden-wuerttemberg%2fsap-legt-halbjahreszahlen-"
                    + "2026-vor-100.html&c=7143983582071811268&mkt=de-de";

    @Test
    void bingsClickTrackerIsUnwrappedToThePublishersOwnUrl() {
        assertEquals("https://www.swr.de/swraktuell/baden-wuerttemberg/"
                + "sap-legt-halbjahreszahlen-2026-vor-100.html",
                BingNewsSearchClient.zielUrl(APICLICK));
        // No url= parameter, junk, and a non-http target stay untouched.
        String plain = "https://www.reuters.com/business/x-2026-08-07/";
        assertEquals(plain, BingNewsSearchClient.zielUrl(plain));
        assertEquals("", BingNewsSearchClient.zielUrl(""));
        assertEquals("https://x.example/a?url=javascript%3Aalert(1)",
                BingNewsSearchClient.zielUrl("https://x.example/a?url=javascript%3Aalert(1)"));
    }

    @Test
    void theRssShelfHandsOverTheRealArticleLink() {
        String rss = """
                <rss><channel>
                <item>
                  <title>SAP legt Halbjahreszahlen vor</title>
                  <link>%s</link>
                  <pubDate>Thu, 07 Aug 2026 09:12:00 GMT</pubDate>
                </item>
                </channel></rss>
                """.formatted(APICLICK.replace("&", "&amp;"));
        List<Article> items = BingNewsSearchClient.parseRss(rss, 5);
        assertEquals(1, items.size());
        assertEquals("https://www.swr.de/swraktuell/baden-wuerttemberg/"
                + "sap-legt-halbjahreszahlen-2026-vor-100.html", items.get(0).link());
        // The tracker never survives anywhere on the find - id and link alike.
        assertEquals(items.get(0).link(), items.get(0).uuid());
    }
}
