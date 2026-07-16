package de.bsommerfeld.wsbg.terminal.wallstreetonline;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WSO board RSS feeds — fixtures are live response excerpts (2026-07-16):
 * board-hot-stocks trimmed to 5 threads (AUMANN, Bombardier, Atos, Eutelsat,
 * NIU) and board-nebenwerte-deutschland trimmed to 4 (Deutsche Rohstoff,
 * Lang &amp; Schwarz, Circus SE, OHB), preserving the pinned quirks: every
 * field CDATA-wrapped, description = "Autor: &lt;name&gt;" only, {@code dc:date}
 * ISO-8601 with offset, link with the {@code #beitrag_} post anchor.
 */
class WsoBoardRssClientTest {

    private static final String HOT_STOCKS =
            "https://www.wallstreet-online.de/rss/board-hot-stocks.xml";
    private static final String NEBENWERTE =
            "https://www.wallstreet-online.de/rss/board-nebenwerte-deutschland.xml";

    private static String fixture(String name) {
        try (InputStream in = WsoBoardRssClientTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing: " + name, e);
        }
    }

    private static String hotStocks() {
        return fixture("wso-board-hot-stocks.xml");
    }

    private static String nebenwerte() {
        return fixture("wso-board-nebenwerte-deutschland.xml");
    }

    /**
     * Fake transport answering a per-URL sequence of bodies, counting fetches
     * per URL. Feeds without a scripted reply answer 404 (the client must skip
     * them gracefully — the pool is the union of whatever answered).
     */
    private static final class FakeFetcher implements WebFetcher {
        final Map<String, AtomicInteger> fetches = new HashMap<>();
        private final Map<String, List<WebResponse>> replies;

        FakeFetcher(Map<String, List<WebResponse>> replies) {
            this.replies = replies;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            assertTrue(WsoBoardRssClient.BOARD_FEEDS.contains(url),
                    "only the pinned board feeds are ever fetched, got: " + url);
            int n = fetches.computeIfAbsent(url, k -> new AtomicInteger()).getAndIncrement();
            List<WebResponse> scripted = replies.get(url);
            if (scripted == null) return new WebResponse(404, "not found", Map.of());
            return scripted.get(Math.min(n, scripted.size() - 1));
        }

        int fetchCount(String url) {
            AtomicInteger n = fetches.get(url);
            return n == null ? 0 : n.get();
        }

        int totalFetches() {
            return fetches.values().stream().mapToInt(AtomicInteger::get).sum();
        }
    }

    private static WebResponse rss(String body) {
        return new WebResponse(200, body, Map.of());
    }

    private static FakeFetcher bothBoards() {
        return new FakeFetcher(Map.of(
                HOT_STOCKS, List.of(rss(hotStocks())),
                NEBENWERTE, List.of(rss(nebenwerte()))));
    }

    @Test
    void parseMapsTheLiveFeedFields() {
        List<RawNewsItem> items = WsoBoardRssClient.parse(hotStocks());
        assertEquals(5, items.size(), "the pool is unfiltered — all feed items parse");

        RawNewsItem aumann = items.get(0);
        assertEquals("AUMANN womöglich die heißeste IPO der e-Mobilität", aumann.title(),
                "the CDATA thread title survives with its umlauts");
        assertEquals("https://www.wallstreet-online.de/diskussion/1248940-83/"
                        + "aumann-womoeglich-heisseste-ipo-e-mobilitaet#beitrag_79862518",
                aumann.link(),
                "the link is the thread deep link with the #beitrag_ post anchor");
        assertEquals(aumann.link(), aumann.uuid(),
                "uuid == link — the post anchor makes it unique per post, so the "
                        + "same thread re-surfaces as new posts land");
        assertEquals(Instant.parse("2026-07-16T12:48:34Z"), aumann.publishedAt(),
                "dc:date's ISO-8601 offset (+02:00) parses to the exact instant");
        assertEquals("wallstreet-online Forum (Marcoz)", aumann.publisher(),
                "the newest post's author rides in the publisher");
        assertNull(aumann.isin(), "the feed tags no ISINs");
        assertTrue(aumann.relatedTickers().isEmpty(), "the feed tags no tickers");
        assertNull(aumann.summary(),
                "the description is ONLY the author marker — there is no body to summarise");
        assertFalse(aumann.sponsored());

        assertEquals("wallstreet-online Forum (cure)", items.get(4).publisher());
    }

    @Test
    void parseToleratesGarbageAndHtmlAnswers() {
        assertTrue(WsoBoardRssClient.parse("<html><body>404</body></html>").isEmpty());
        assertTrue(WsoBoardRssClient.parse("not xml at all { \"json\": true }").isEmpty());
        assertTrue(WsoBoardRssClient.parse("").isEmpty());
        assertTrue(WsoBoardRssClient.parse(null).isEmpty());
        // Truncated mid-item (a torn response) — parses what it can, never throws.
        String torn = hotStocks().substring(0, hotStocks().indexOf("<dc:date>") + 12);
        assertTrue(WsoBoardRssClient.parse(torn).isEmpty());
    }

    @Test
    void relevanceFilterIsPrecisionOverRecall() {
        WsoBoardRssClient client = new WsoBoardRssClient(bothBoards());

        List<RawNewsItem> aumann = client.newsForName("Aumann AG", 10);
        assertEquals(1, aumann.size(),
                "the match is against the THREAD TITLE, case-insensitively");
        assertTrue(aumann.get(0).title().contains("AUMANN"));

        assertEquals(1, client.newsForName("OHB Technology", 10).size(),
                "a Nebenwerte-board thread surfaces through the same united pool");
        assertEquals(1, client.newsForName("Deutsche Rohstoff AG", 10).size());

        assertTrue(client.newsForName("Rheinmetall AG", 10).isEmpty(),
                "a company nobody is discussing yields nothing");
        assertTrue(client.newsForName("AG", 10).isEmpty(),
                "a name with ONLY generic words must never flood the pool");
    }

    @Test
    void titleMatchingIsWordLevelAndUmlautTolerant() {
        var words = WsoBoardRssClient.significantWords("Münchener Rück");
        assertTrue(WsoBoardRssClient.titleMatches("Muenchener Rueck im Höhenflug", words));
        assertFalse(WsoBoardRssClient.titleMatches("Allianz im Höhenflug", words));
        // A generic legal word never carries the match alone.
        assertFalse(WsoBoardRssClient.significantWords("Circus SE").contains("se"));
        assertTrue(WsoBoardRssClient.significantWords("Circus SE").contains("circus"));
    }

    @Test
    void poolUnitesBoardsNewestFirstAndDeduplicatesByPostAnchor() {
        WsoBoardRssClient client = new WsoBoardRssClient(bothBoards());
        // "Diskussion" sits in one hot-stocks title (Eutelsat, 14:03) and one
        // Nebenwerte title (Circus SE, 14:41) — the united pool interleaves
        // the boards newest-first, so Circus wins the top spot.
        List<RawNewsItem> talk = client.newsForName("Diskussion Holdings", 10);
        assertEquals(2, talk.size());
        assertTrue(talk.get(0).title().startsWith("Circus SE"),
                "the union is sorted newest-first ACROSS boards");
        assertTrue(talk.get(1).title().contains("Eutelsat"));
        assertEquals(1, client.newsForName("Diskussion Holdings", 1).size(),
                "limit caps the relevance-filtered list");

        // Two boards answering the SAME body (threads can echo across feeds)
        // collapse by the #beitrag_ uuid — no duplicate items in the pool.
        WsoBoardRssClient echoed = new WsoBoardRssClient(new FakeFetcher(Map.of(
                HOT_STOCKS, List.of(rss(hotStocks())),
                NEBENWERTE, List.of(rss(hotStocks())))));
        assertEquals(1, echoed.newsForName("Aumann", 10).size(),
                "identical posts from two feeds deduplicate by uuid");
    }

    @Test
    void poolCacheAnswersABurstWithOneFetchPerFeed() {
        FakeFetcher fetcher = bothBoards();
        WsoBoardRssClient client = new WsoBoardRssClient(fetcher);

        assertEquals(1, client.newsForName("Aumann", 10).size());
        assertEquals(1, client.newsForName("Circus SE", 10).size());
        assertEquals(1, client.newsForName("Bombardier", 10).size());
        assertEquals(1, fetcher.fetchCount(HOT_STOCKS),
                "each board is fetched at most ONCE per TTL, however many names ask");
        assertEquals(1, fetcher.fetchCount(NEBENWERTE));

        assertTrue(client.newsFor("AAG", 10).isEmpty(), "symbol leg is a no-op");
        assertTrue(client.newsForIsin("DE000A2DAM03", 10).isEmpty(), "ISIN leg is a no-op");
        assertTrue(client.newsForName("", 10).isEmpty());
        assertTrue(client.newsForName("Aumann", 0).isEmpty());
        assertEquals(1, fetcher.fetchCount(HOT_STOCKS),
                "no-op legs and blank/zero-limit queries never fetch");
    }

    @Test
    void aFailedBoardIsSkippedWithoutSinkingTheOthers() {
        // Nebenwerte has no scripted reply → the FakeFetcher 404s it; the
        // hot-stocks items must still flow.
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                HOT_STOCKS, List.of(rss(hotStocks()))));
        WsoBoardRssClient client = new WsoBoardRssClient(fetcher);
        assertEquals(1, client.newsForName("Aumann", 10).size(),
                "one dead board never empties the united pool");
        assertTrue(client.newsForName("Circus SE", 10).isEmpty());
    }

    @Test
    void softTwoHundredTrapIsAMissAndNeverPoisonsThePool() {
        // The pinned trap: WSO error shells answer 200-shaped HTML — a 200
        // proves nothing, only content does. (The nastier WSO flavour — an
        // unknown board slug answering a VALID default-board feed — is fenced
        // at the source: slugs are pinned from the live /rss index.)
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                HOT_STOCKS, List.of(
                        new WebResponse(200, "<!doctype html>\n<html><head><title>wallstreet"
                                + "-online</title></head><body>kein Feed</body></html>", Map.of()),
                        rss(hotStocks()))));
        WsoBoardRssClient client = new WsoBoardRssClient(fetcher);

        assertTrue(client.newsForName("Aumann", 10).isEmpty(),
                "an HTML 200 is a miss, not a feed");
        assertEquals(1, client.newsForName("Aumann", 10).size(),
                "the miss was NOT cached — the next call refetches and succeeds");
        assertEquals(2, fetcher.fetchCount(HOT_STOCKS));
    }

    @Test
    void dateParsingIsTolerant() {
        assertEquals(Instant.parse("2026-07-16T12:48:34Z"),
                WsoBoardRssClient.parseDate("2026-07-16T14:48:34+02:00"));
        assertEquals(Instant.parse("2026-07-16T12:48:34Z"),
                WsoBoardRssClient.parseDate("  2026-07-16T14:48:34+02:00\n"),
                "stray whitespace around the CDATA payload is tolerated");
        assertEquals(Instant.parse("2026-07-16T12:48:34Z"),
                WsoBoardRssClient.parseDate("2026-07-16T12:48:34Z"));
        assertNull(WsoBoardRssClient.parseDate("Wed, 16 Jul 2026 14:48:34 +0200"),
                "a foreign date shape yields null, never a guessed timestamp");
        assertNull(WsoBoardRssClient.parseDate("gestern"));
        assertNull(WsoBoardRssClient.parseDate(""));
        assertNull(WsoBoardRssClient.parseDate(null));
    }

    @Test
    void authorExtractionHandlesTheMarkerAndItsAbsence() {
        assertEquals("Marcoz", WsoBoardRssClient.extractAuthor("Autor: Marcoz"));
        assertEquals("Straßenkoeter",
                WsoBoardRssClient.extractAuthor("Autor: Straßenkoeter"),
                "author names keep their umlauts/eszett verbatim");
        assertEquals("k_ralle", WsoBoardRssClient.extractAuthor("  Autor:  k_ralle \n"));
        assertNull(WsoBoardRssClient.extractAuthor("Autor: "));
        assertNull(WsoBoardRssClient.extractAuthor("irgendwas anderes"));
        assertNull(WsoBoardRssClient.extractAuthor(null));
    }

    @Test
    void looksLikeRssAcceptsFeedsAndRejectsHtmlShells() {
        assertTrue(WsoBoardRssClient.looksLikeRss(hotStocks()));
        assertTrue(WsoBoardRssClient.looksLikeRss("\n  <?xml version=\"1.0\"?><rss/>"));
        assertTrue(WsoBoardRssClient.looksLikeRss("<rss version=\"2.0\"><channel/></rss>"));
        assertFalse(WsoBoardRssClient.looksLikeRss(
                "<!doctype html>\n<html><head><title>RSS</title></head></html>"));
        assertFalse(WsoBoardRssClient.looksLikeRss(null));
        assertFalse(WsoBoardRssClient.looksLikeRss("   "));
    }
}
