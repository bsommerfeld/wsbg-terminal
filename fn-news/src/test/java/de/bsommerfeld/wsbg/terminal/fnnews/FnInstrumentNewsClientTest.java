package de.bsommerfeld.wsbg.terminal.fnnews;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-ISIN feed parse, the ISIN gate, and the homepage-redirect miss gate. */
class FnInstrumentNewsClientTest {

    private static final String FEED = """
            <?xml version="1.0" encoding="utf-8"?>
            <rss version="2.0" xmlns:fn="http://www.finanznachrichten.de/service/rss">
            <channel>
              <title>Nachrichten zu SAP SE</title>
              <link>https://www.finanznachrichten.de/nachrichten-aktien/sap-se.htm</link>
              <language>de-de</language>
              <item>
                <title>SAP ordnet Konzernstruktur neu und bündelt KI-Einheiten</title>
                <description>Der Softwarekonzern fasst seine KI-Aktivitäten zusammen.</description>
                <link>https://www.finanznachrichten.de/nachrichten-2026-07/12345.htm</link>
                <pubDate>2026-07-10T14:27:00Z</pubDate>
                <fn:isin>DE0007164600</fn:isin>
              </item>
              <item>
                <title>EXKLUSIV zu SAP: Könnte die Aktie jetzt drehen?</title>
                <description></description>
                <link>https://www.finanznachrichten.de/nachrichten-2026-07/67890.htm</link>
                <pubDate>kein datum</pubDate>
                <fn:isin>DE0007164600</fn:isin>
              </item>
            </channel></rss>""";

    @Test
    void parsesTitleTeaserLinkIsoDateAndIsin() {
        List<RawNewsItem> items = FnInstrumentNewsClient.parse(FEED);
        assertEquals(2, items.size(), String.valueOf(items));
        RawNewsItem first = items.get(0);
        assertTrue(first.title().contains("Konzernstruktur"), first.title());
        assertEquals("finanznachrichten.de", first.publisher());
        assertEquals(Instant.parse("2026-07-10T14:27:00Z"), first.publishedAt());
        assertEquals("DE0007164600", first.isin());
        assertTrue(first.summary().contains("KI-Aktivitäten"), first.summary());
        // A bad pubDate costs the date, an empty teaser stays null.
        assertNull(items.get(1).publishedAt());
        assertNull(items.get(1).summary());
    }

    @Test
    void isinGateAcceptsOnlyIsinShapes() {
        assertEquals("de0007164600", FnInstrumentNewsClient.isinKey(" DE0007164600 "));
        assertEquals("us69012t3059", FnInstrumentNewsClient.isinKey("US69012T3059"));
        assertNull(FnInstrumentNewsClient.isinKey("SAP"));
        assertNull(FnInstrumentNewsClient.isinKey("1E0007164600"));
        assertNull(FnInstrumentNewsClient.isinKey(null));
    }

    @Test
    void homepageHtmlIsAMissNeverParsed() {
        // An unknown ISIN 301s to the homepage — followed, that is 200 HTML.
        assertFalse(FnInstrumentNewsClient.looksLikeRss(
                "<!DOCTYPE html><html><head><title>Aktien</title></head>…"));
        assertTrue(FnInstrumentNewsClient.looksLikeRss(FEED));
    }
}
