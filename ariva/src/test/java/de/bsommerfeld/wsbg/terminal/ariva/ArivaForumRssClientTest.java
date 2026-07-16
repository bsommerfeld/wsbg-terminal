package de.bsommerfeld.wsbg.terminal.ariva;

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
 * Ariva community feed — fixture is a live response excerpt (2026-07-16)
 * trimmed to 4 posts: a VW post tagged with BOTH share-class ISINs, a
 * Rakuten post, a BYD post whose title never names the company ("geb ich
 * dir absolut Recht" — why name addressing must stay off), and a
 * Commerzbank post. The descriptions carry Ariva's live quirk: RAW HTML
 * child elements ({@code <br/>}, the {@code <b><i> -author</i></b>} marker)
 * inside the XML, not escaped entities.
 */
class ArivaForumRssClientTest {

    private static String fixture() {
        try (InputStream in = ArivaForumRssClientTest.class
                .getResourceAsStream("/ariva-forum.xml")) {
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
            assertEquals("https://www.ariva.de/forum/rss", url,
                    "ONE community feed URL — this source is a firehose, never a search");
            int n = fetches.getAndIncrement();
            return replies.get(Math.min(n, replies.size() - 1));
        }
    }

    private static WebResponse rss() {
        return new WebResponse(200, fixture(), Map.of());
    }

    @Test
    void parseMapsTheLiveFeedFieldsIncludingRawHtmlDescriptions() {
        List<ArivaForumRssClient.ForumPost> posts = ArivaForumRssClient.parse(fixture());
        assertEquals(4, posts.size(), "the pool is unfiltered — all feed posts parse");

        ArivaForumRssClient.ForumPost vw = posts.get(0);
        assertEquals("ID3 bleibt", vw.item().title());
        assertEquals("https://www.ariva.de/forum/volkswagen-vorzuege-352177"
                        + "?page=1115#jumppos27897", vw.item().link(),
                "the link is the post-deep permalink (page + jumppos) — also the uuid");
        assertEquals(vw.item().link(), vw.item().uuid());
        assertEquals(Instant.parse("2026-07-16T12:36:38Z"), vw.item().publishedAt(),
                "Ariva pubDate has NO day-of-week token and a numeric offset");
        assertEquals(List.of("DE0007664039", "DE0007664005"), vw.isins(),
                "a multi-listing thread tags BOTH share-class ISINs");
        assertEquals("Ariva-Forum (St2023)", vw.item().publisher(),
                "the author from the trailing <b><i> -name</i></b> marker");

        // The RAW <br/> child element must not swallow the text after it.
        assertTrue(vw.item().summary().contains("Heute morgen vom Spezialisten"),
                "text AFTER a raw <br/> survives: " + vw.item().summary());
        assertFalse(vw.item().summary().contains("St2023"),
                "the author marker is stripped from the summary");
        assertFalse(vw.item().summary().contains("<"), "HTML tags are stripped");

        assertEquals("Ariva-Forum (Libuda)", posts.get(1).item().publisher());
        assertEquals(List.of("JP3967200001"), posts.get(1).isins());
        assertEquals("Ariva-Forum (Real Cinderella born)", posts.get(3).item().publisher(),
                "author names may contain spaces");
    }

    @Test
    void parseToleratesGarbageAndHtmlAnswers() {
        assertTrue(ArivaForumRssClient.parse("<html><body>404</body></html>").isEmpty());
        assertTrue(ArivaForumRssClient.parse("not xml at all { \"json\": true }").isEmpty());
        assertTrue(ArivaForumRssClient.parse("").isEmpty());
        assertTrue(ArivaForumRssClient.parse(null).isEmpty());
        // Truncated mid-item (a torn response) — parses what it can, never throws.
        String torn = fixture().substring(0, fixture().indexOf("<pubDate>") + 20);
        assertTrue(ArivaForumRssClient.parse(torn).isEmpty());
    }

    @Test
    void isinFilterMatchesAnyTagAndEmitsTheQueriedIsin() {
        ArivaForumRssClient client = new ArivaForumRssClient(new FakeFetcher(rss()));

        // The VW post tags ordinaries AND prefs — BOTH queries find it, and the
        // emitted item carries the QUERIED isin so triangulation stays exact.
        List<RawNewsItem> prefs = client.newsForIsin("DE0007664039", 10);
        assertEquals(1, prefs.size());
        assertEquals("DE0007664039", prefs.get(0).isin());

        List<RawNewsItem> ords = client.newsForIsin("DE0007664005", 10);
        assertEquals(1, ords.size());
        assertEquals("DE0007664005", ords.get(0).isin());
        assertEquals(prefs.get(0).uuid(), ords.get(0).uuid(), "same post either way");

        assertEquals(1, client.newsForIsin("CNE100000296", 10).size(), "BYD");
        assertEquals(1, client.newsForIsin("de000cbk1001", 10).size(),
                "the queried isin is case-normalised");
        assertTrue(client.newsForIsin("US0378331005", 10).isEmpty(),
                "an instrument the feed doesn't carry yields nothing");
    }

    @Test
    void symbolAndNameLegsAreNoOps() {
        FakeFetcher fetcher = new FakeFetcher(rss());
        ArivaForumRssClient client = new ArivaForumRssClient(fetcher);
        assertTrue(client.newsFor("VOW3", 10).isEmpty(), "symbol leg is a no-op");
        assertTrue(client.newsForName("Volkswagen", 10).isEmpty(),
                "name leg is a no-op — post titles never name the company");
        assertTrue(client.newsForIsin("", 10).isEmpty());
        assertTrue(client.newsForIsin("DE0007664039", 0).isEmpty());
        assertEquals(0, fetcher.fetches.get(),
                "no-op legs and blank/zero-limit queries never fetch");
    }

    @Test
    void poolCacheAnswersABurstWithOneFetch() {
        FakeFetcher fetcher = new FakeFetcher(rss());
        ArivaForumRssClient client = new ArivaForumRssClient(fetcher);

        assertEquals(1, client.newsForIsin("DE0007664039", 10).size());
        assertEquals(1, client.newsForIsin("JP3967200001", 10).size());
        assertEquals(1, client.newsForIsin("DE000CBK1001", 10).size());
        assertEquals(1, fetcher.fetches.get(),
                "the POOL is cached — a burst of different ISINs makes ONE request");
    }

    @Test
    void softTwoHundredTrapIsAMissAndNeverPoisonsThePool() {
        FakeFetcher fetcher = new FakeFetcher(
                new WebResponse(200, "<!doctype html>\n<html><head><title>ARIVA.DE"
                        + "</title></head><body>not a feed</body></html>", Map.of()),
                rss());
        ArivaForumRssClient client = new ArivaForumRssClient(fetcher);

        assertTrue(client.newsForIsin("DE0007664039", 10).isEmpty(),
                "an HTML 200 is a miss, not a feed");
        assertEquals(1, client.newsForIsin("DE0007664039", 10).size(),
                "the miss was NOT cached — the next call refetches and succeeds");
        assertEquals(2, fetcher.fetches.get());
    }

    @Test
    void authorExtractionHandlesTheMarkerShapes() {
        assertEquals("St2023", ArivaForumRssClient.extractAuthor(
                "text<br /><b><i> -St2023</i></b>"));
        assertEquals("Real Cinderella born", ArivaForumRssClient.extractAuthor(
                "text <b><i> -Real Cinderella born</i></b>  "));
        assertNull(ArivaForumRssClient.extractAuthor("text without marker"));
        assertNull(ArivaForumRssClient.extractAuthor(null));
        assertEquals("text", ArivaForumRssClient.stripHtml(
                ArivaForumRssClient.stripAuthorMarker("text<br /><b><i> -St2023</i></b>")));
    }

    @Test
    void pubDateParsingIsTolerant() {
        assertEquals(Instant.parse("2026-07-16T12:36:38Z"),
                ArivaForumRssClient.parsePubDate("16 Jul 2026 14:36:38 +0200"));
        assertEquals(Instant.parse("2026-07-16T12:36:38Z"),
                ArivaForumRssClient.parsePubDate("Thu, 16 Jul 2026 14:36:38 +0200"),
                "a stray day-of-week prefix is tolerated");
        assertNull(ArivaForumRssClient.parsePubDate("gestern"));
        assertNull(ArivaForumRssClient.parsePubDate(""));
        assertNull(ArivaForumRssClient.parsePubDate(null));
    }

    @Test
    void looksLikeRssAcceptsFeedsAndRejectsHtmlShells() {
        assertTrue(ArivaForumRssClient.looksLikeRss(fixture()));
        assertTrue(ArivaForumRssClient.looksLikeRss("<rss version=\"2.0\"><channel/></rss>"));
        assertFalse(ArivaForumRssClient.looksLikeRss(
                "<!doctype html>\n<html><head><title>ARIVA.DE</title></head></html>"));
        assertFalse(ArivaForumRssClient.looksLikeRss(null));
        assertFalse(ArivaForumRssClient.looksLikeRss("   "));
    }
}
