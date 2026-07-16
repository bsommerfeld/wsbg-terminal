package de.bsommerfeld.wsbg.terminal.reuters;

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
 * Reuters Arc news-sitemap — fixture is a live excerpt (2026-07-16) trimmed
 * to 5 entries: Csquare NYSE debut (business), Franco-German defence
 * (aerospace), LNG/S&P Global (energy), gold macro, and one Portuguese-desk
 * item (Abiec beef) that pins the deliberately uncurated ingestion.
 */
class ReutersNewsClientTest {

    private static String fixture() {
        try (InputStream in = ReutersNewsClientTest.class
                .getResourceAsStream("/reuters-news-sitemap.xml")) {
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
            assertEquals("https://www.reuters.com/arc/outboundfeeds/news-sitemap/"
                            + "?outputType=xml", url,
                    "ONE sitemap URL — this source is a firehose, never a search");
            int n = fetches.getAndIncrement();
            return replies.get(Math.min(n, replies.size() - 1));
        }
    }

    private static WebResponse sitemap() {
        return new WebResponse(200, fixture(), Map.of());
    }

    @Test
    void parseMapsTheLiveSitemapFields() {
        List<RawNewsItem> items = ReutersNewsClient.parse(fixture());
        assertEquals(5, items.size(), "the pool is unfiltered — all entries parse, "
                + "including the non-English desks");

        RawNewsItem csquare = items.get(0);
        assertEquals("Brookfield-backed Csquare valued at $3.24 billion in NYSE debut",
                csquare.title(), "the headline is the CDATA news:title");
        assertTrue(csquare.link().startsWith(
                        "https://www.reuters.com/business/brookfield-backed-csquare-valued"),
                "the link is the article <loc> — never the <image:loc> of the same entry");
        assertEquals(csquare.link(), csquare.uuid());
        assertEquals(Instant.parse("2026-07-16T16:54:42.616Z"), csquare.publishedAt(),
                "ISO publication_date with fractional seconds parses exactly");
        assertEquals("Reuters", csquare.publisher());
        assertNull(csquare.summary(), "a sitemap carries no teaser — the headline is the value");
        assertNull(csquare.isin());
        assertTrue(csquare.relatedTickers().isEmpty());
    }

    @Test
    void parseToleratesGarbageAndHtmlAnswers() {
        assertTrue(ReutersNewsClient.parse("<html><body>bot wall</body></html>").isEmpty());
        assertTrue(ReutersNewsClient.parse("not xml { }").isEmpty());
        assertTrue(ReutersNewsClient.parse("").isEmpty());
        assertTrue(ReutersNewsClient.parse(null).isEmpty());
        // Truncated mid-entry (a torn response) — parses what it can, never throws.
        String torn = fixture().substring(0, fixture().indexOf("publication_date") + 10);
        assertTrue(ReutersNewsClient.parse(torn).isEmpty());
    }

    @Test
    void relevanceFilterIsPrecisionOverRecall() {
        ReutersNewsClient client = new ReutersNewsClient(new FakeFetcher(sitemap()));

        assertEquals(1, client.newsForName("Csquare", 10).size());
        assertEquals(1, client.newsForName("Brookfield Asset Management", 10).size(),
                "'Brookfield-backed' carries the word-boundary match");
        assertEquals(1, client.newsForName("S&P Global", 10).size());
        assertEquals(1, client.newsForName("Abiec", 10).size(),
                "the non-English desks stay in the pool — ingestion wide, the model judges");

        assertTrue(client.newsForName("Rheinmetall AG", 10).isEmpty(),
                "a company the wire doesn't carry yields nothing");
        assertTrue(client.newsForName("AG", 10).isEmpty(),
                "a name with ONLY generic words must never flood the pool");
    }

    @Test
    void poolCacheAnswersABurstWithOneFetch() {
        FakeFetcher fetcher = new FakeFetcher(sitemap());
        ReutersNewsClient client = new ReutersNewsClient(fetcher);

        assertEquals(1, client.newsForName("Csquare", 10).size());
        assertEquals(1, client.newsForName("Abiec", 10).size());
        assertEquals(1, client.newsForName("Csquare", 10).size());
        assertEquals(1, fetcher.fetches.get(),
                "the POOL is cached — a burst of different names makes ONE request");

        assertTrue(client.newsFor("CSQ", 10).isEmpty(), "symbol leg is a no-op");
        assertTrue(client.newsForIsin("US12345A1016", 10).isEmpty(), "ISIN leg is a no-op");
        assertTrue(client.newsForName("", 10).isEmpty());
        assertTrue(client.newsForName("Csquare", 0).isEmpty());
        assertEquals(1, fetcher.fetches.get(),
                "no-op legs and blank/zero-limit queries never fetch");
    }

    @Test
    void softTwoHundredTrapIsAMissAndNeverPoisonsThePool() {
        // The bot wall answers 200-shaped HTML challenges — only content counts.
        FakeFetcher fetcher = new FakeFetcher(
                new WebResponse(200, "<!doctype html>\n<html><body>Please verify you are "
                        + "a human</body></html>", Map.of()),
                sitemap());
        ReutersNewsClient client = new ReutersNewsClient(fetcher);

        assertTrue(client.newsForName("Csquare", 10).isEmpty(),
                "an HTML 200 is a miss, not a sitemap");
        assertEquals(1, client.newsForName("Csquare", 10).size(),
                "the miss was NOT cached — the next call refetches and succeeds");
        assertEquals(2, fetcher.fetches.get());
    }

    @Test
    void publicationDateParsingIsTolerant() {
        assertEquals(Instant.parse("2026-07-16T16:49:11.350Z"),
                ReutersNewsClient.parseDate("2026-07-16T16:49:11.35Z"));
        assertEquals(Instant.parse("2026-07-16T16:53:43.816Z"),
                ReutersNewsClient.parseDate("2026-07-16T16:53:43.816Z"));
        assertNull(ReutersNewsClient.parseDate("Thu, 16 Jul 2026"));
        assertNull(ReutersNewsClient.parseDate(""));
        assertNull(ReutersNewsClient.parseDate(null));
    }

    @Test
    void looksLikeSitemapAcceptsUrlsetsAndRejectsEverythingElse() {
        assertTrue(ReutersNewsClient.looksLikeSitemap(fixture()));
        assertTrue(ReutersNewsClient.looksLikeSitemap(
                "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"></urlset>"));
        assertFalse(ReutersNewsClient.looksLikeSitemap(
                "<?xml version=\"1.0\"?><rss version=\"2.0\"/>"),
                "an RSS answer is NOT the sitemap — the endpoint changed shape");
        assertFalse(ReutersNewsClient.looksLikeSitemap("<!doctype html><html></html>"));
        assertFalse(ReutersNewsClient.looksLikeSitemap(null));
        assertFalse(ReutersNewsClient.looksLikeSitemap("   "));
    }

    @Test
    void nameMatchingIsWordLevelAndUmlautTolerant() {
        var words = ReutersNewsClient.significantWords("Münchener Rück");
        assertTrue(ReutersNewsClient.titleMatches("Muenchener Rueck lifts dividend", words));
        assertFalse(ReutersNewsClient.titleMatches("Allianz lifts dividend", words));
        assertFalse(ReutersNewsClient.significantWords("Csquare Holdings Ltd")
                .contains("holdings"));
    }
}
