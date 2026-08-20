package de.bsommerfeld.wsbg.terminal.web.impl.sources.fnnews;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.feed.FeedParser;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport.FakeWebFetcher;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-ISIN feed leg, the ISIN gate, and the homepage-redirect miss gate.
 * Ported from the module world; parsing now rides the house {@link FeedParser}
 * (which reads FN's ISO-8601 pubDates natively) and the queried ISIN is
 * stamped onto every item.
 */
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

    private static ResolvedInstrument sap() {
        return new ResolvedInstrument(Isin.parse("DE0007164600"), Optional.empty(), "SAP SE");
    }

    @Test
    void fetchesParsesAndStampsTheQueriedIsin() {
        FakeWebFetcher fetcher = new FakeWebFetcher().on("rss-x-aktien-de0007164600", FEED);
        FnInstrumentNewsClient client = new FnInstrumentNewsClient(fetcher);

        List<Article> items = client.newsFor(sap(), 10);

        assertEquals(2, items.size(), String.valueOf(items));
        Article first = items.get(0);
        assertTrue(first.title().contains("Konzernstruktur"), first.title());
        assertEquals("finanznachrichten.de", first.publisher());
        assertEquals(Instant.parse("2026-07-10T14:27:00Z"), first.publishedAt());
        assertEquals("DE0007164600", first.isin(), "the queried ISIN is stamped on every item");
        assertTrue(first.summary().contains("KI-Aktivitäten"), first.summary());
        // A bad pubDate costs the date, an empty teaser stays null.
        assertNull(items.get(1).publishedAt());
        assertNull(items.get(1).summary());
        assertTrue(fetcher.calls.get(0).endsWith("rss-x-aktien-de0007164600"),
                "the URL keys on the lowercased ISIN alone");
    }

    @Test
    void aBurstOnTheSameIsinCostsOneFetch() {
        FakeWebFetcher fetcher = new FakeWebFetcher().on("rss-x-aktien-de0007164600", FEED);
        FnInstrumentNewsClient client = new FnInstrumentNewsClient(fetcher);

        client.newsFor(sap(), 5);
        client.newsFor(sap(), 5);

        assertEquals(1, fetcher.total(), "the per-ISIN politeness cache answers the burst");
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
        FakeWebFetcher fetcher = new FakeWebFetcher()
                .on("rss-x-aktien-", "<!DOCTYPE html><html><head><title>Aktien</title></head>…");
        FnInstrumentNewsClient client = new FnInstrumentNewsClient(fetcher);

        assertTrue(client.newsFor(sap(), 10).isEmpty(),
                "a 200-shaped HTML page must never be parsed as a feed");
        assertFalse(FeedParser.looksLikeFeed("<!DOCTYPE html><html>…"));
        assertTrue(FeedParser.looksLikeFeed(FEED));
    }

    @Test
    void withoutAnIsinTheFanStaysSilent() {
        FakeWebFetcher fetcher = new FakeWebFetcher();
        FnInstrumentNewsClient client = new FnInstrumentNewsClient(fetcher);

        assertTrue(client.newsFor(ResolvedInstrument.ofName("SAP SE"), 10).isEmpty(),
                "symbol and name keys are no-ops - the feed is ISIN-addressed");
        assertEquals(0, fetcher.total());
    }
}
