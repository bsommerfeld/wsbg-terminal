package de.bsommerfeld.wsbg.terminal.bloomberg;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bloomberg vertical feeds — fixture is a live markets-feed excerpt
 * (2026-07-16) trimmed to 4 items: an instrument-less macro story (TACO
 * hedge), Citi/CMA CGM (the legal name only in the TEASER), Chevron and
 * Entain (colloquial headline handle "Ladbrokes Owner", legal name in the
 * teaser). The fake serves the SAME body for every vertical, which doubles
 * as the cross-vertical guid dedupe check.
 */
class BloombergNewsClientTest {

    private static String fixture() {
        try (InputStream in = BloombergNewsClientTest.class
                .getResourceAsStream("/bloomberg-markets.rss")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing", e);
        }
    }

    /** Fake transport answering a fixed sequence of bodies, counting fetches. */
    private static final class FakeFetcher implements WebFetcher {
        final AtomicInteger fetches = new AtomicInteger();
        private final List<WebResponse> replies;

        FakeFetcher(WebResponse... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            assertTrue(url.startsWith("https://feeds.bloomberg.com/")
                            && url.endsWith("/news.rss"),
                    "only the catalogued vertical feeds are fetched: " + url);
            int n = fetches.getAndIncrement();
            return replies.get(Math.min(n, replies.size() - 1));
        }
    }

    private static WebResponse rss() {
        return new WebResponse(200, fixture(), Map.of());
    }

    @Test
    void parseMapsTheLiveFeedFields() {
        List<BloombergNewsClient.WireItem> items = BloombergNewsClient.parse(fixture());
        assertEquals(4, items.size(), "the pool is unfiltered — all feed items parse");

        RawNewsItem taco = items.get(0).item();
        assertEquals("Oil Traders Crowd Around Cheap TACO Hedge as Iran War Escalates",
                taco.title());
        assertEquals("https://www.bloomberg.com/news/articles/2026-07-16/"
                + "oil-traders-crowd-around-cheap-taco-hedge-as-iran-war-escalates", taco.link());
        assertEquals("TI87UTKIUPTU00", taco.uuid(),
                "uuid = the wire's own story id (the cross-vertical dedupe key)");
        assertEquals(Instant.parse("2026-07-16T16:40:16Z"), taco.publishedAt());
        assertEquals("Bloomberg", taco.publisher());
        assertNull(taco.isin(), "the feed tags no ISINs");
        assertTrue(taco.relatedTickers().isEmpty(), "the feed tags no tickers");
        assertTrue(taco.summary().startsWith("An unusual options trade"),
                "the CDATA description is the plain-text teaser");
        assertFalse(taco.summary().contains("<"));
    }

    @Test
    void parseToleratesGarbageAndHtmlAnswers() {
        assertTrue(BloombergNewsClient.parse("<html><body>wall</body></html>").isEmpty());
        assertTrue(BloombergNewsClient.parse("not xml { }").isEmpty());
        assertTrue(BloombergNewsClient.parse("").isEmpty());
        assertTrue(BloombergNewsClient.parse(null).isEmpty());
        // Truncated mid-item (a torn response) — parses what it can, never throws.
        String torn = fixture().substring(0, fixture().indexOf("<pubDate>") + 20);
        assertTrue(BloombergNewsClient.parse(torn).isEmpty());
    }

    @Test
    void relevanceMatchesTitleAndTeaser() {
        BloombergNewsClient client = new BloombergNewsClient(new FakeFetcher(rss()));

        assertEquals(1, client.newsForName("Chevron Corp.", 10).size());
        assertEquals(1, client.newsForName("Entain Plc", 10).size(),
                "the headline says 'Ladbrokes Owner' — 'Entain' carries the match");
        assertEquals(1, client.newsForName("CMA CGM", 10).size());
        assertEquals(1, client.newsForName("Citigroup Inc", 10).size(),
                "the headline abbreviates to 'Citi' — the TEASER prints the legal name");

        assertTrue(client.newsForName("Rheinmetall AG", 10).isEmpty(),
                "a company the wire doesn't carry yields nothing");
        assertTrue(client.newsForName("AG", 10).isEmpty(),
                "a name with ONLY generic words must never flood the pool");
    }

    @Test
    void unionDeduplicatesByWireIdAcrossVerticals() {
        // Every vertical answers the identical body — the union must still be
        // the 4 unique stories, not 5×4.
        BloombergNewsClient client = new BloombergNewsClient(new FakeFetcher(rss()));
        assertEquals(1, client.newsForName("Chevron Corp.", 10).size());
        assertEquals(1, client.newsForName("Entain Plc", 10).size());
    }

    @Test
    void poolCacheAnswersABurstWithOneFetchPerVertical() {
        FakeFetcher fetcher = new FakeFetcher(rss());
        BloombergNewsClient client = new BloombergNewsClient(fetcher);

        assertEquals(1, client.newsForName("Chevron Corp.", 10).size());
        assertEquals(1, client.newsForName("Citigroup Inc", 10).size());
        assertEquals(1, client.newsForName("Chevron Corp.", 10).size());
        assertEquals(5, fetcher.fetches.get(),
                "the POOLS are cached — a burst makes ONE request per vertical");

        assertTrue(client.newsFor("CVX", 10).isEmpty(), "symbol leg is a no-op");
        assertTrue(client.newsForIsin("US1667641005", 10).isEmpty(), "ISIN leg is a no-op");
        assertTrue(client.newsForName("", 10).isEmpty());
        assertTrue(client.newsForName("Chevron", 0).isEmpty());
        assertEquals(5, fetcher.fetches.get(),
                "no-op legs and blank/zero-limit queries never fetch");
    }

    @Test
    void softTwoHundredTrapIsAMissAndNeverPoisonsThePools() {
        WebResponse wall = new WebResponse(200,
                "<!doctype html>\n<html><body>Are you a robot?</body></html>", Map.of());
        // All 5 verticals answer the wall first, then the real feed.
        FakeFetcher fetcher = new FakeFetcher(wall, wall, wall, wall, wall, rss());
        BloombergNewsClient client = new BloombergNewsClient(fetcher);

        assertTrue(client.newsForName("Chevron Corp.", 10).isEmpty(),
                "an HTML 200 is a miss, not a feed");
        assertEquals(1, client.newsForName("Chevron Corp.", 10).size(),
                "the miss was NOT cached — the next call refetches and succeeds");
        assertEquals(10, fetcher.fetches.get());
    }

    @Test
    void pubDateParsingIsTolerant() {
        assertEquals(Instant.parse("2026-07-16T16:40:16Z"),
                BloombergNewsClient.parsePubDate("Thu, 16 Jul 2026 16:40:16 GMT"));
        assertEquals(Instant.parse("2026-07-16T16:40:16Z"),
                BloombergNewsClient.parsePubDate("Wed, 16 Jul 2026 16:40:16 GMT"),
                "a wrong day-of-week token is tolerated — the date wins");
        assertNull(BloombergNewsClient.parsePubDate("yesterday"));
        assertNull(BloombergNewsClient.parsePubDate(""));
        assertNull(BloombergNewsClient.parsePubDate(null));
    }

    @Test
    void looksLikeRssAcceptsFeedsAndRejectsHtmlShells() {
        assertTrue(BloombergNewsClient.looksLikeRss(fixture()));
        assertTrue(BloombergNewsClient.looksLikeRss("<rss version=\"2.0\"><channel/></rss>"));
        assertFalse(BloombergNewsClient.looksLikeRss("<!doctype html><html></html>"));
        assertFalse(BloombergNewsClient.looksLikeRss(null));
        assertFalse(BloombergNewsClient.looksLikeRss("   "));
    }

    @Test
    void nameMatchingIsWordLevelAndUmlautTolerant() {
        var words = BloombergNewsClient.significantWords("Münchener Rück");
        assertTrue(BloombergNewsClient.textMatches("Muenchener Rueck lifts dividend", words));
        assertFalse(BloombergNewsClient.textMatches("Allianz lifts dividend", words));
        assertFalse(BloombergNewsClient.significantWords("Entain Plc").contains("plc"));
    }
}
