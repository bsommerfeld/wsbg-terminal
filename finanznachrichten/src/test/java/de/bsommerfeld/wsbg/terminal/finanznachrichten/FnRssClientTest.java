package de.bsommerfeld.wsbg.terminal.finanznachrichten;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FnRssClientTest {

    private final FnRssClient client = new FnRssClient();

    /** Real-shaped finanznachrichten.de feed: fn: namespace, ISO pubDate, fn:isin, no guid. */
    private static final String FEED = """
            <?xml version="1.0" encoding="utf-8" standalone="yes"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:fn="http://www.finanznachrichten.de/service/rss">
            <channel>
            <title>FinanzNachrichten.de: Aktuelle Nachrichten</title>
            <link>https://www.finanznachrichten.de/nachrichten/news.htm</link>
            <item>
            <title>VORSICHT bei Siemens Energy! CTS Eventim KAUFEN?</title>
            <description>Bei Strategic Resources entsteht etwas Gro&#223;es und Anleger ...</description>
            <link>https://www.finanznachrichten.de/nachrichten-2026-06/68687080-vorsicht.htm</link>
            <pubDate>2026-06-05T03:46:00Z</pubDate>
            <fn:isin>DE000ENER6Y0</fn:isin>
            </item>
            <item>
            <title>Nevada im Fokus: Sienna Resources bereitet Bohrstart vor</title>
            <description>Anzeige / Werbung Nevada z&#228;hlt seit Jahrzehnten zu den wichtigsten Goldregionen.</description>
            <link>https://www.finanznachrichten.de/nachrichten-2026-06/68687081-nevada.htm</link>
            <pubDate>2026-06-05T03:46:00Z</pubDate>
            <fn:isin>CA06849F1080</fn:isin>
            </item>
            <item>
            <title>Marktbericht ohne ISIN</title>
            <description>Allgemeiner Marktkommentar.</description>
            <link>https://www.finanznachrichten.de/nachrichten-2026-06/68687090-markt.htm</link>
            <pubDate>2026-06-05T04:00:00Z</pubDate>
            </item>
            </channel>
            </rss>
            """;

    @Test
    void parsesItemsWithIsinAndIsoDate() {
        List<FnNewsItem> items = client.parse(FEED, FnFeed.AKTIEN_NACHRICHTEN);

        assertEquals(3, items.size());

        FnNewsItem first = items.get(0);
        assertEquals("VORSICHT bei Siemens Energy! CTS Eventim KAUFEN?", first.title());
        assertEquals("https://www.finanznachrichten.de/nachrichten-2026-06/68687080-vorsicht.htm", first.link());
        assertEquals("DE000ENER6Y0", first.isin());
        assertEquals("rss-aktien-nachrichten", first.feedSlug());
        assertFalse(first.sponsored());
        // 2026-06-05T03:46:00Z
        assertEquals(1780631160L, first.publishedUtc());
        assertTrue(first.fetchedUtc() > 0);
    }

    @Test
    void detectsAndStripsSponsoredMarker() {
        FnNewsItem ad = client.parse(FEED, FnFeed.AKTIEN_NACHRICHTEN).get(1);
        assertTrue(ad.sponsored(), "item with 'Anzeige / Werbung' must be flagged sponsored");
        assertFalse(ad.description().startsWith("Anzeige"), "marker must be stripped from description");
        assertTrue(ad.description().startsWith("Nevada"));
    }

    @Test
    void itemWithoutIsinHasNullIsin() {
        FnNewsItem noIsin = client.parse(FEED, FnFeed.AKTIEN_NACHRICHTEN).get(2);
        assertNull(noIsin.isin());
        assertEquals("Marktbericht ohne ISIN", noIsin.title());
    }

    @Test
    void malformedDateYieldsZero() {
        String xml = """
                <rss version="2.0"><channel><item>
                <title>Bad date</title>
                <link>https://example.com/x</link>
                <description>x</description>
                <pubDate>not-a-date</pubDate>
                </item></channel></rss>
                """;
        FnNewsItem item = client.parse(xml, FnFeed.NEWS).getFirst();
        assertEquals(0, item.publishedUtc());
        assertEquals("Bad date", item.title());
    }

    @Test
    void offsetDateTimeFallbackIsParsed() {
        String xml = """
                <rss version="2.0"><channel><item>
                <title>Offset date</title>
                <link>https://example.com/o</link>
                <description>x</description>
                <pubDate>2026-06-05T05:46:00+02:00</pubDate>
                </item></channel></rss>
                """;
        FnNewsItem item = client.parse(xml, FnFeed.NEWS).getFirst();
        // same instant as 03:46:00Z
        assertEquals(1780631160L, item.publishedUtc());
    }

    @Test
    void invalidXmlReturnsEmptyList() {
        assertTrue(client.parse("not xml at all <><>", FnFeed.NEWS).isEmpty());
    }

    @Test
    void doctypeIsRejectedForXxeSafety() {
        // A DOCTYPE with an external entity must not be expanded; the parser is
        // configured to reject any DOCTYPE, so this yields an empty list, never
        // a file read or an exception bubbling out.
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <rss version="2.0"><channel><item>
                <title>&xxe;</title>
                <link>https://example.com/e</link>
                <description>x</description>
                <pubDate>2026-06-05T03:46:00Z</pubDate>
                </item></channel></rss>
                """;
        assertTrue(client.parse(xxe, FnFeed.NEWS).isEmpty());
    }

    @Test
    void emptyFeedYieldsEmptyList() {
        String xml = """
                <rss version="2.0"><channel><title>Empty</title></channel></rss>
                """;
        assertTrue(client.parse(xml, FnFeed.NEWS).isEmpty());
    }
}
