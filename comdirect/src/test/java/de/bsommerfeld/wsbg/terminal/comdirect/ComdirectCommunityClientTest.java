package de.bsommerfeld.wsbg.terminal.comdirect;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * comdirect community board feed — fixture is a live response excerpt
 * (2026-07-16, board WertpapiereAnlage) trimmed to 4 threads: a certificate
 * search-mask question (must never match a company query), the S&amp;P
 * Global / Mobility Global spin-off cost-base thread (the instrument sits in
 * the BODY), the Aroundtown dividend complaint and an Allianz discount
 * certificate question.
 */
class ComdirectCommunityClientTest {

    private static final String WPA_URL =
            "https://community.comdirect.de/rss/board?board.id=WertpapiereAnlage";

    /** The pinned second soft-200 shape: valid RSS skeleton, zero items. */
    private static final String ERROR_CHANNEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0">
              <channel>
                <title>Ressource nicht gefunden</title>
                <link>/axuzw55642/rss/board</link>
                <description>Die Ressource, auf die zugegriffen werden soll, wurde gelöscht oder hat nie existiert.</description>
              </channel>
            </rss>""";

    /** A one-thread stand-in feed for the non-WertpapiereAnlage boards. */
    private static final String MINI_BOARD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0">
              <channel>
                <title>CFD Themen</title>
                <item>
                  <title>Warum gibt es keine Kurse für KAKAO und KAFFEE?</title>
                  <link>https://community.comdirect.de/t5/cfd/m-p/375883#M4148</link>
                  <description>&lt;P&gt;Kaffee und Kakao keine Kurse, warum?&lt;/P&gt;</description>
                  <pubDate>Mon, 06 Jul 2026 11:23:37 GMT</pubDate>
                  <guid>https://community.comdirect.de/t5/cfd/m-p/375883#M4148</guid>
                  <dc:creator>echt</dc:creator>
                </item>
              </channel>
            </rss>""";

    private static String fixture() {
        try (InputStream in = ComdirectCommunityClientTest.class
                .getResourceAsStream("/comdirect-community-wertpapiere.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing", e);
        }
    }

    /**
     * Fake transport answering a per-URL sequence of bodies (the last reply
     * repeats), counting fetches per URL and in total.
     */
    private static final class FakeFetcher implements WebFetcher {
        final AtomicInteger fetches = new AtomicInteger();
        final Map<String, AtomicInteger> perUrl = new HashMap<>();
        private final Map<String, List<WebResponse>> replies = new HashMap<>();

        /** Every board answers its stand-in; WertpapiereAnlage the real fixture. */
        static FakeFetcher healthy() {
            FakeFetcher f = new FakeFetcher();
            for (String board : ComdirectCommunityClient.BOARDS) {
                f.replies.put(url(board), new ArrayList<>(List.of(
                        new WebResponse(200, MINI_BOARD, Map.of()))));
            }
            f.replies.put(WPA_URL, new ArrayList<>(List.of(
                    new WebResponse(200, fixture(), Map.of()))));
            return f;
        }

        static String url(String board) {
            return "https://community.comdirect.de/rss/board?board.id=" + board;
        }

        FakeFetcher wpaSequence(WebResponse... seq) {
            replies.put(WPA_URL, new ArrayList<>(List.of(seq)));
            return this;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            assertTrue(replies.containsKey(url),
                    "only the pinned board feed URLs are ever fetched: " + url);
            fetches.incrementAndGet();
            int n = perUrl.computeIfAbsent(url, u -> new AtomicInteger()).getAndIncrement();
            List<WebResponse> seq = replies.get(url);
            return seq.get(Math.min(n, seq.size() - 1));
        }
    }

    @Test
    void parseMapsTheLiveFeedFields() {
        List<RawNewsItem> items = ComdirectCommunityClient.parse(fixture());
        assertEquals(4, items.size(), "the pool is unfiltered — all feed threads parse");

        RawNewsItem first = items.get(0);
        assertEquals("Zertifikate gezielte Suchmaske", first.title());
        assertEquals("https://community.comdirect.de/t5/wertpapiere-anlage/"
                        + "zertifikate-gezielte-suchmaske/m-p/376465#M210570", first.link(),
                "the link is the thread-message permalink");
        assertEquals(first.link(), first.uuid(), "guid == link on this feed");
        assertEquals(Instant.parse("2026-07-16T03:12:12Z"), first.publishedAt(),
                "RFC-1123 GMT pubDate parses to the exact instant");
        assertEquals("comdirect Community (Traschke)", first.publisher(),
                "publisher = the venue plus the dc:creator forum author");
        assertNull(first.isin(), "the feed tags no ISINs");
        assertTrue(first.relatedTickers().isEmpty(), "the feed tags no tickers");

        // The entity-escaped post HTML is stripped into the summary.
        assertTrue(first.summary().contains("comdirect aktienanleihen"), first.summary());
        assertFalse(first.summary().contains("<"), "HTML tags are stripped");

        // The full first post is capped to the lead.
        RawNewsItem spinOff = items.get(1);
        assertTrue(spinOff.title().startsWith("S&P Global"),
                "escaped entities in the title decode");
        assertTrue(spinOff.summary().length() <= 501, "the summary keeps only the lead");
        assertTrue(spinOff.summary().contains("Mobility Global"),
                "the instrument buried in the post body survives into the summary");
        assertEquals("comdirect Community (topflop)", spinOff.publisher());
    }

    @Test
    void parseToleratesGarbageAndCfChallengeHtml() {
        assertTrue(ComdirectCommunityClient.parse(
                "<!DOCTYPE html><html lang=\"en-US\"><head><title>Just a moment..."
                        + "</title></head><body>challenge</body></html>").isEmpty());
        assertTrue(ComdirectCommunityClient.parse("not xml at all { \"json\": true }").isEmpty());
        assertTrue(ComdirectCommunityClient.parse("").isEmpty());
        assertTrue(ComdirectCommunityClient.parse(null).isEmpty());
        // The error channel is VALID RSS with zero items.
        assertTrue(ComdirectCommunityClient.parse(ERROR_CHANNEL).isEmpty());
        // Truncated mid-item (a torn response) — parses what it can, never throws.
        String torn = fixture().substring(0, fixture().indexOf("<pubDate>") + 20);
        assertTrue(ComdirectCommunityClient.parse(torn).isEmpty());
    }

    @Test
    void relevanceFilterMatchesTitleAndPostBody() {
        ComdirectCommunityClient client = new ComdirectCommunityClient(FakeFetcher.healthy());

        List<RawNewsItem> aroundtown = client.newsForName("Aroundtown SA", 10);
        assertEquals(1, aroundtown.size(), "a title mention matches");
        assertTrue(aroundtown.get(0).title().contains("Aroundtown"));

        List<RawNewsItem> mobility = client.newsForName("Mobility Global Inc.", 10);
        assertFalse(mobility.isEmpty(),
                "an instrument buried in the POST BODY matches — forum threads "
                        + "often name the company only in the post");
        assertTrue(mobility.stream().anyMatch(i -> i.summary().contains("Mobility Global")));

        assertEquals(1, client.newsForName("Allianz SE", 10).size(),
                "generic legal words (SE) never carry the match — 'Allianz' does");

        assertTrue(client.newsForName("Rheinmetall AG", 10).isEmpty(),
                "a company the boards don't carry yields nothing");
        assertTrue(client.newsForName("AG", 10).isEmpty(),
                "a name with ONLY generic words must never flood the pool");
    }

    @Test
    void matchesAreSortedMostRecentFirstAcrossBoards() {
        ComdirectCommunityClient client = new ComdirectCommunityClient(FakeFetcher.healthy());
        // "Kaffee"/KAKAO sits on the stand-in board (3 board copies, older) —
        // a query matching threads across boards comes back newest-first.
        List<RawNewsItem> kaffee = client.newsForName("Kaffee Kakao Handelshaus", 10);
        assertFalse(kaffee.isEmpty());
        for (int i = 1; i < kaffee.size(); i++) {
            assertFalse(kaffee.get(i).publishedAt()
                            .isAfter(kaffee.get(i - 1).publishedAt()),
                    "merged board results are sorted newest-first");
        }
    }

    @Test
    void nameMatchingIsWordLevelAndUmlautTolerant() {
        var words = ComdirectCommunityClient.significantWords("Münchener Rück");
        assertTrue(ComdirectCommunityClient.textMatches(
                "Muenchener Rueck Dividende nicht erhalten", words));
        assertFalse(ComdirectCommunityClient.textMatches(
                "Allianz Dividende nicht erhalten", words));
        // A generic legal word never carries the match alone.
        assertFalse(ComdirectCommunityClient.significantWords("Aroundtown SA")
                .contains("sa"));
        assertTrue(ComdirectCommunityClient.significantWords("Aroundtown SA")
                .contains("aroundtown"));
    }

    @Test
    void cfChallengeSoftTwoHundredIsAMissAndNeverPoisonsThePool() {
        // The pinned trap: a Cloudflare challenge can arrive 200-shaped HTML —
        // a 200 proves nothing, only content does.
        FakeFetcher fetcher = FakeFetcher.healthy().wpaSequence(
                new WebResponse(200, "<!DOCTYPE html><html lang=\"en-US\"><head>"
                        + "<title>Just a moment...</title></head><body></body></html>", Map.of()),
                new WebResponse(200, fixture(), Map.of()));
        ComdirectCommunityClient client = new ComdirectCommunityClient(fetcher);

        assertTrue(client.newsForName("Aroundtown SA", 10).isEmpty(),
                "an HTML 200 is a miss, not a feed");
        assertEquals(1, client.newsForName("Aroundtown SA", 10).size(),
                "the miss was NOT cached — the next call refetches and succeeds");
        assertEquals(2, fetcher.perUrl.get(WPA_URL).get());
    }

    @Test
    void errorChannelRssIsAMissAndNeverPoisonsThePool() {
        // The SECOND pinned trap: an unknown/removed board.id answers a VALID
        // RSS skeleton ("Ressource nicht gefunden") with zero items.
        FakeFetcher fetcher = FakeFetcher.healthy().wpaSequence(
                new WebResponse(200, ERROR_CHANNEL, Map.of()),
                new WebResponse(200, fixture(), Map.of()));
        ComdirectCommunityClient client = new ComdirectCommunityClient(fetcher);

        assertTrue(client.newsForName("Aroundtown SA", 10).isEmpty(),
                "the zero-item error channel is a miss, not an empty board");
        assertEquals(1, client.newsForName("Aroundtown SA", 10).size(),
                "the miss was NOT cached — the next call refetches and succeeds");
        assertEquals(2, fetcher.perUrl.get(WPA_URL).get());
    }

    @Test
    void poolCacheAnswersABurstWithOneFetchPerBoard() {
        FakeFetcher fetcher = FakeFetcher.healthy();
        ComdirectCommunityClient client = new ComdirectCommunityClient(fetcher);

        assertEquals(1, client.newsForName("Aroundtown SA", 10).size());
        assertEquals(1, client.newsForName("Allianz SE", 10).size());
        assertFalse(client.newsForName("Mobility Global Inc.", 10).isEmpty());
        assertEquals(1, client.newsForName("Aroundtown SA", 10).size());
        assertEquals(ComdirectCommunityClient.BOARDS.size(), fetcher.fetches.get(),
                "the POOLS are cached — a burst of names makes ONE request per board");

        assertTrue(client.newsFor("AT1", 10).isEmpty(), "symbol leg is a no-op");
        assertTrue(client.newsForIsin("LU1673108939", 10).isEmpty(), "ISIN leg is a no-op");
        assertTrue(client.newsForName("", 10).isEmpty());
        assertTrue(client.newsForName("Aroundtown", 0).isEmpty());
        assertEquals(ComdirectCommunityClient.BOARDS.size(), fetcher.fetches.get(),
                "no-op legs and blank/zero-limit queries never fetch");
    }

    @Test
    void limitCapsTheFilteredList() {
        ComdirectCommunityClient client = new ComdirectCommunityClient(FakeFetcher.healthy());
        // "Zertifikat" sits in TWO fixture threads (Suchmaske via body,
        // Discount Zertifikat Allianz via title).
        assertTrue(client.newsForName("Zertifikate Handelshaus", 10).size() >= 2);
        assertEquals(1, client.newsForName("Zertifikate Handelshaus", 1).size(),
                "limit caps the relevance-filtered list");
    }

    @Test
    void pubDateParsingIsTolerant() {
        assertEquals(Instant.parse("2026-07-16T03:12:12Z"),
                ComdirectCommunityClient.parsePubDate("Thu, 16 Jul 2026 03:12:12 GMT"));
        assertEquals(Instant.parse("2026-07-13T08:30:00Z"),
                ComdirectCommunityClient.parsePubDate("Mon, 13 Jul 2026 08:30:00 +0000"));
        assertEquals(Instant.parse("2026-07-13T08:30:00Z"),
                ComdirectCommunityClient.parsePubDate("Sun, 13 Jul 2026 08:30:00 GMT"),
                "a wrong day-of-week token is tolerated — the date wins");
        assertNull(ComdirectCommunityClient.parsePubDate("gestern"));
        assertNull(ComdirectCommunityClient.parsePubDate(""));
        assertNull(ComdirectCommunityClient.parsePubDate(null));
    }

    @Test
    void looksLikeRssAcceptsFeedsAndRejectsHtmlShells() {
        assertTrue(ComdirectCommunityClient.looksLikeRss(fixture()));
        assertTrue(ComdirectCommunityClient.looksLikeRss(ERROR_CHANNEL));
        assertTrue(ComdirectCommunityClient.looksLikeRss(
                "\n  <?xml version=\"1.0\"?><rss/>"));
        assertFalse(ComdirectCommunityClient.looksLikeRss(
                "<!DOCTYPE html><html lang=\"en-US\"><head><title>Just a moment..."
                        + "</title></head></html>"));
        assertFalse(ComdirectCommunityClient.looksLikeRss(null));
        assertFalse(ComdirectCommunityClient.looksLikeRss("   "));
    }

    @Test
    void stripHtmlFlattensThePostAndCapKeepsTheLead() {
        assertEquals("a & b \"quoted\"",
                ComdirectCommunityClient.stripHtml("<p>a &amp; b\n &quot;quoted&quot;</p>"));
        assertNull(ComdirectCommunityClient.stripHtml(null));

        assertNull(ComdirectCommunityClient.capSummary(null));
        assertEquals("kurz", ComdirectCommunityClient.capSummary("kurz"));
        String longPost = "x".repeat(900);
        String capped = ComdirectCommunityClient.capSummary(longPost);
        assertEquals(501, capped.length(), "500 chars of lead plus the ellipsis");
        assertTrue(capped.endsWith("…"));
    }
}
