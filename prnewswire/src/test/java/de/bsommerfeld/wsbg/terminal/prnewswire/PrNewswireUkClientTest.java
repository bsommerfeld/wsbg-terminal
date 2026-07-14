package de.bsommerfeld.wsbg.terminal.prnewswire;

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
 * PR Newswire UK all-releases feed — fixture is a live response excerpt
 * (2026-07-14) trimmed to 4 items: Lucara Diamond Corp., HCLTech, Universal
 * Music Group N.V., plus a generic market-research release (Future Market
 * Insights) that must never match a company query.
 */
class PrNewswireUkClientTest {

    private static String fixture() {
        try (InputStream in = PrNewswireUkClientTest.class
                .getResourceAsStream("/prnewswire-uk-all-news.xml")) {
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
            assertEquals("https://www.prnewswire.co.uk/rss/news-releases-list.rss", url,
                    "ONE feed URL — this source is a firehose, never a search");
            int n = fetches.getAndIncrement();
            return replies.get(Math.min(n, replies.size() - 1));
        }
    }

    private static WebResponse rss() {
        return new WebResponse(200, fixture(), Map.of());
    }

    @Test
    void parseMapsTheLiveFeedFields() {
        List<RawNewsItem> items = PrNewswireUkClient.parse(fixture());
        assertEquals(4, items.size(), "the pool is unfiltered — all feed items parse");

        RawNewsItem lucara = items.get(0);
        assertEquals("LUCARA ANNOUNCES RECOVERY OF TENTH DIAMOND OVER 1,000 CARATS "
                + "FROM THE KAROWE MINE IN BOTSWANA", lucara.title());
        assertEquals("https://www.prnewswire.co.uk/news-releases/lucara-announces-recovery-"
                        + "of-tenth-diamond-over-1-000-carats-from-the-karowe-mine-in-botswana-"
                        + "302824240.html", lucara.link(),
                "the link is the DIRECT release URL (the article digester reads it)");
        assertEquals(lucara.link(), lucara.uuid(), "guid == link on this feed");
        assertEquals(Instant.parse("2026-07-13T21:19:00Z"), lucara.publishedAt(),
                "RFC-1123 pubDate with numeric offset parses to the exact instant");
        assertEquals("Lucara Diamond Corp.", lucara.publisher(),
                "publisher = dc:contributor, the issuing organisation");
        assertNull(lucara.isin(), "the feed tags no ISINs");
        assertTrue(lucara.relatedTickers().isEmpty(), "the feed tags no tickers");

        // The CDATA HTML teaser is stripped into the summary.
        assertTrue(lucara.summary().contains("Lucara Diamond Corp."), lucara.summary());
        assertFalse(lucara.summary().contains("<"), "HTML tags are stripped");

        assertEquals("HCLTech", items.get(1).publisher());
        assertEquals("Future Market Insights", items.get(3).publisher());
    }

    @Test
    void parseToleratesGarbageAndHtmlAnswers() {
        assertTrue(PrNewswireUkClient.parse("<html><body>404</body></html>").isEmpty());
        assertTrue(PrNewswireUkClient.parse("not xml at all { \"json\": true }").isEmpty());
        assertTrue(PrNewswireUkClient.parse("").isEmpty());
        assertTrue(PrNewswireUkClient.parse(null).isEmpty());
        // Truncated mid-item (a torn response) — parses what it can, never throws.
        String torn = fixture().substring(0, fixture().indexOf("<pubDate>") + 20);
        assertTrue(PrNewswireUkClient.parse(torn).isEmpty());
    }

    @Test
    void relevanceFilterIsPrecisionOverRecall() {
        PrNewswireUkClient client = new PrNewswireUkClient(new FakeFetcher(rss()));

        List<RawNewsItem> lucara = client.newsForName("Lucara Diamond Corp.", 10);
        assertEquals(1, lucara.size());
        assertTrue(lucara.get(0).title().contains("LUCARA"),
                "the match is against the TITLE, case-insensitively");

        assertEquals(1, client.newsForName("Universal Music Group N.V.", 10).size(),
                "generic legal words (Group, N.V.) never carry the match — "
                        + "'Universal'/'Music' do");
        assertEquals(1, client.newsForName("HCLTech", 10).size());

        assertTrue(client.newsForName("Rheinmetall AG", 10).isEmpty(),
                "a company the feed doesn't carry yields nothing");
        assertTrue(client.newsForName("AG", 10).isEmpty(),
                "a name with ONLY generic words must never flood the pool");
    }

    @Test
    void titleMatchingIsWordLevelAndUmlautTolerant() {
        var words = PrNewswireUkClient.significantWords("Münchener Rück");
        assertTrue(PrNewswireUkClient.titleMatches("Muenchener Rueck lifts dividend", words));
        assertFalse(PrNewswireUkClient.titleMatches("Allianz lifts dividend", words));
        // A generic legal word never carries the match alone.
        assertFalse(PrNewswireUkClient.significantWords("Universal Music Group N.V.")
                .contains("group"));
        assertTrue(PrNewswireUkClient.significantWords("Universal Music Group N.V.")
                .contains("universal"));
    }

    @Test
    void softTwoHundredTrapIsAMissAndNeverPoisonsThePool() {
        // The pinned trap: unknown /rss paths and error pages answer 200-shaped
        // HTML — a 200 proves nothing, only content does.
        FakeFetcher fetcher = new FakeFetcher(
                new WebResponse(200, "<!doctype html>\n<html><head><title>404 | PR Newswire UK"
                        + "</title></head><body>not a feed</body></html>", Map.of()),
                rss());
        PrNewswireUkClient client = new PrNewswireUkClient(fetcher);

        assertTrue(client.newsForName("Lucara Diamond Corp.", 10).isEmpty(),
                "an HTML 200 is a miss, not a feed");
        assertEquals(1, client.newsForName("Lucara Diamond Corp.", 10).size(),
                "the miss was NOT cached — the next call refetches and succeeds");
        assertEquals(2, fetcher.fetches.get());
    }

    @Test
    void poolCacheAnswersABurstWithOneFetch() {
        FakeFetcher fetcher = new FakeFetcher(rss());
        PrNewswireUkClient client = new PrNewswireUkClient(fetcher);

        assertEquals(1, client.newsForName("Lucara Diamond Corp.", 10).size());
        assertEquals(1, client.newsForName("HCLTech", 10).size());
        assertEquals(1, client.newsForName("Universal Music Group N.V.", 10).size());
        assertEquals(1, client.newsForName("Lucara Diamond Corp.", 10).size());
        assertEquals(1, fetcher.fetches.get(),
                "the POOL is cached — a burst of different names makes ONE request");

        assertTrue(client.newsFor("LUC", 10).isEmpty(), "symbol leg is a no-op");
        assertTrue(client.newsForIsin("CA5492551004", 10).isEmpty(), "ISIN leg is a no-op");
        assertTrue(client.newsForName("", 10).isEmpty());
        assertTrue(client.newsForName("Lucara", 0).isEmpty());
        assertEquals(1, fetcher.fetches.get(),
                "no-op legs and blank/zero-limit queries never fetch");
    }

    @Test
    void limitCapsTheFilteredList() {
        PrNewswireUkClient client = new PrNewswireUkClient(new FakeFetcher(rss()));
        // "billion" sits in TWO fixture titles (HCLTech, FMI) — a hypothetical
        // company of that name matches both; the limit caps the filtered list.
        assertEquals(2, client.newsForName("Billion Industries", 10).size());
        assertEquals(1, client.newsForName("Billion Industries", 1).size(),
                "limit caps the relevance-filtered list");
    }

    @Test
    void pubDateParsingIsTolerant() {
        assertEquals(Instant.parse("2026-07-13T21:19:00Z"),
                PrNewswireUkClient.parsePubDate("Mon, 13 Jul 2026 21:19:00 +0000"));
        assertEquals(Instant.parse("2026-07-13T08:30:00Z"),
                PrNewswireUkClient.parsePubDate("Mon, 13 Jul 2026 08:30:00 GMT"));
        assertEquals(Instant.parse("2026-07-13T08:30:00Z"),
                PrNewswireUkClient.parsePubDate("Sun, 13 Jul 2026 08:30:00 GMT"),
                "a wrong day-of-week token is tolerated — the date wins");
        assertNull(PrNewswireUkClient.parsePubDate("yesterday"));
        assertNull(PrNewswireUkClient.parsePubDate(""));
        assertNull(PrNewswireUkClient.parsePubDate(null));
    }

    @Test
    void looksLikeRssAcceptsFeedsAndRejectsHtmlShells() {
        assertTrue(PrNewswireUkClient.looksLikeRss(fixture()));
        assertTrue(PrNewswireUkClient.looksLikeRss("\n  <?xml version=\"1.0\"?><rss/>"));
        assertTrue(PrNewswireUkClient.looksLikeRss("<rss version=\"2.0\"><channel/></rss>"));
        assertFalse(PrNewswireUkClient.looksLikeRss(
                "<!doctype html>\n<html><head><title>404 | PR Newswire UK</title></head></html>"));
        assertFalse(PrNewswireUkClient.looksLikeRss(null));
        assertFalse(PrNewswireUkClient.looksLikeRss("   "));
    }

    @Test
    void stripHtmlFlattensTheTeaser() {
        assertEquals("a & b \"quoted\"",
                PrNewswireUkClient.stripHtml("<p>a &amp; b\n &quot;quoted&quot;</p>"));
        assertNull(PrNewswireUkClient.stripHtml(null));
    }
}
