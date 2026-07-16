package de.bsommerfeld.wsbg.terminal.benzinga;

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
 * Benzinga newsdesk feeds — fixtures are live excerpts (2026-07-16): the
 * news feed trimmed to 3 items (a GlobeNewswire ROSEN class-action PR for
 * Verra Mobility, the Ark/Tesla trade note tagged via data-ticker
 * attributes, the Intel/SK-Hynix hire), the markets feed to 2 (Oaktree with
 * a data-ticker OAK-B, Geron tagged ONLY via a /quote/GERN anchor), the
 * "Why Is It Moving?" topic feed to 2 (Sleep Number, Scilex — both tagged
 * only via /quote anchors).
 */
class BenzingaNewsClientTest {

    private static final String NEWS_URL = "https://www.benzinga.com/news/feed";
    private static final String MARKETS_URL = "https://www.benzinga.com/markets/feed";
    private static final String WIIM_URL =
            "https://www.benzinga.com/topic/why-is-it-moving/feed";

    private static String resource(String name) {
        try (InputStream in = BenzingaNewsClientTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing: " + name, e);
        }
    }

    private static String newsFixture() {
        return resource("/benzinga-news.xml");
    }

    private static String marketsFixture() {
        return resource("/benzinga-markets.xml");
    }

    /** Fake transport answering per-URL body sequences, counting fetches. */
    private static final class FakeFetcher implements WebFetcher {
        final AtomicInteger fetches = new AtomicInteger();
        private final Map<String, List<WebResponse>> replies;
        private final Map<String, Integer> served = new HashMap<>();

        FakeFetcher(Map<String, List<WebResponse>> replies) {
            this.replies = replies;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            List<WebResponse> queue = replies.get(url);
            assertTrue(queue != null, "only the catalogued newsdesk feeds are fetched: " + url);
            fetches.getAndIncrement();
            int n = served.merge(url, 1, Integer::sum) - 1;
            return queue.get(Math.min(n, queue.size() - 1));
        }
    }

    private static String wiimFixture() {
        return resource("/benzinga-wiim.xml");
    }

    private static FakeFetcher allFeeds() {
        return new FakeFetcher(Map.of(
                NEWS_URL, List.of(new WebResponse(200, newsFixture(), Map.of())),
                MARKETS_URL, List.of(new WebResponse(200, marketsFixture(), Map.of())),
                WIIM_URL, List.of(new WebResponse(200, wiimFixture(), Map.of()))));
    }

    @Test
    void parseMapsTheLiveFeedFieldsAndHarvestsTickers() {
        List<BenzingaNewsClient.WireItem> items = BenzingaNewsClient.parse(newsFixture());
        assertEquals(3, items.size(), "the pool is unfiltered — all feed items parse");

        RawNewsItem intel = items.get(2).item();
        assertTrue(intel.title().startsWith("Intel Hires Former SK Hynix CEO"));
        assertEquals("53295522 at https://www.benzinga.com", intel.uuid(),
                "uuid = the stable WordPress guid");
        assertEquals(Instant.parse("2026-06-19T02:22:20Z"), intel.publishedAt());
        assertEquals("Benzinga", intel.publisher(),
                "the publisher is the wire, even on syndicated PRs");
        assertTrue(intel.relatedTickers().contains("INTC"),
                "data-ticker attributes are harvested into relatedTickers");
        assertNull(intel.isin());
        assertTrue(intel.summary().contains("Intel Corp."), intel.summary());
        assertFalse(intel.summary().contains("<"), "the article HTML is stripped");

        RawNewsItem rosen = items.get(0).item();
        assertFalse(rosen.summary().contains("gnw_nitf"),
                "GlobeNewswire's stylesheet tag never leaks into the teaser");
        assertTrue(rosen.summary().length() <= 601, "the teaser is capped");
    }

    @Test
    void parseToleratesGarbageAndHtmlAnswers() {
        assertTrue(BenzingaNewsClient.parse("<html><body>wall</body></html>").isEmpty());
        assertTrue(BenzingaNewsClient.parse("not xml { }").isEmpty());
        assertTrue(BenzingaNewsClient.parse("").isEmpty());
        assertTrue(BenzingaNewsClient.parse(null).isEmpty());
        // Truncated mid-item (a torn response) — parses what it can, never throws.
        String torn = newsFixture().substring(0, newsFixture().indexOf("<pubDate>") + 20);
        assertTrue(BenzingaNewsClient.parse(torn).isEmpty());
    }

    @Test
    void cloudflareScriptTrailerAfterTheRootElementIsCut() {
        // Live 2026-07-16: Cloudflare appends its email-decode <script> AFTER
        // </rss> — the feed must still parse completely and cleanly.
        String withTrailer = newsFixture() + "<script data-cfasync=\"false\" "
                + "src=\"/cdn-cgi/scripts/5c5dd728/cloudflare-static/email-decode.min.js\">"
                + "</script>";
        assertEquals(3, BenzingaNewsClient.parse(withTrailer).size());
    }

    @Test
    void symbolLegAnswersByExactTagMatchOnly() {
        BenzingaNewsClient client = new BenzingaNewsClient(allFeeds());

        List<RawNewsItem> intel = client.newsFor("INTC", 10);
        assertEquals(1, intel.size());
        assertTrue(intel.get(0).title().contains("Intel"));

        assertEquals(1, client.newsFor("TSLA", 10).size(),
                "data-ticker tags answer the symbol query");
        assertEquals(1, client.newsFor("GERN", 10).size(),
                "a /quote/… anchor alone carries the tag — and it sits in the "
                        + "SECOND feed, so both pools answer the union");
        assertEquals(1, client.newsFor("OAK-B", 10).size(),
                "share-class shapes match verbatim");

        assertTrue(client.newsFor("AI", 10).isEmpty(),
                "an untagged symbol yields nothing — prose is never fuzzy-matched");
        assertTrue(client.newsFor("", 10).isEmpty());
    }

    @Test
    void relevanceFilterMatchesTitleAndTeaser() {
        BenzingaNewsClient client = new BenzingaNewsClient(allFeeds());

        assertEquals(1, client.newsForName("Verra Mobility Corporation", 10).size());
        assertEquals(1, client.newsForName("Oaktree Capital", 10).size(),
                "the markets feed is part of the pool");
        assertEquals(1, client.newsForName("Geron Corporation", 10).size());

        assertTrue(client.newsForName("Rheinmetall AG", 10).isEmpty(),
                "a company the wire doesn't carry yields nothing");
        assertTrue(client.newsForName("Corporation", 10).isEmpty(),
                "a name with ONLY generic words must never flood the pool");
        assertTrue(client.newsForIsin("US4586731058", 10).isEmpty(), "ISIN leg is a no-op");
    }

    @Test
    void nameMatchScansTheFullDeliveredTextBeyondTheTeaserCap() {
        // The display teaser is capped at 600 chars — the SEARCH text is not:
        // a mention deep in the article body still carries the match.
        String filler = "word ".repeat(200); // 1000 chars of body before the name
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><item>
                <title>Defense Movers Roundup</title>
                <link>https://www.benzinga.com/x</link>
                <guid isPermaLink="false">99 at https://www.benzinga.com</guid>
                <pubDate>Thu, 16 Jul 2026 10:00:00 +0000</pubDate>
                <description>&lt;p&gt;FILLER Rheinmetall closed higher.&lt;/p&gt;</description>
                </item></channel></rss>
                """.replace("FILLER", filler);
        List<BenzingaNewsClient.WireItem> items = BenzingaNewsClient.parse(xml);
        assertEquals(1, items.size());
        assertTrue(items.get(0).item().summary().length() <= 601, "the teaser stays capped");
        assertTrue(BenzingaNewsClient.textMatches(items.get(0).searchText(),
                        BenzingaNewsClient.significantWords("Rheinmetall AG")),
                "a mention beyond the teaser cap still matches");
    }

    @Test
    void whyIsItMovingDeskIsPartOfThePool() {
        BenzingaNewsClient client = new BenzingaNewsClient(allFeeds());

        List<RawNewsItem> snbr = client.newsFor("SNBR", 10);
        assertEquals(1, snbr.size(),
                "a WIIM mover is found via its /quote anchor tag");
        assertTrue(snbr.get(0).title().contains("Why Is It Moving?"));

        assertEquals(1, client.newsForName("Sleep Number", 10).size());
        assertEquals(1, client.newsForName("Scilex", 10).size());
    }

    @Test
    void poolCacheAnswersABurstWithOneFetchPerFeed() {
        FakeFetcher fetcher = allFeeds();
        BenzingaNewsClient client = new BenzingaNewsClient(fetcher);

        assertEquals(1, client.newsFor("INTC", 10).size());
        assertEquals(1, client.newsFor("GERN", 10).size());
        assertEquals(1, client.newsForName("Verra Mobility", 10).size());
        assertEquals(3, fetcher.fetches.get(),
                "the POOLS are cached — a burst makes ONE request per feed");
    }

    @Test
    void softTwoHundredTrapIsAMissAndNeverPoisonsThePools() {
        WebResponse wall = new WebResponse(200,
                "<!doctype html>\n<html><body>403</body></html>", Map.of());
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                NEWS_URL, List.of(wall, new WebResponse(200, newsFixture(), Map.of())),
                MARKETS_URL, List.of(wall, new WebResponse(200, marketsFixture(), Map.of())),
                WIIM_URL, List.of(wall, new WebResponse(200, wiimFixture(), Map.of()))));
        BenzingaNewsClient client = new BenzingaNewsClient(fetcher);

        assertTrue(client.newsFor("INTC", 10).isEmpty(),
                "an HTML 200 is a miss, not a feed");
        assertEquals(1, client.newsFor("INTC", 10).size(),
                "the miss was NOT cached — the next call refetches and succeeds");
        assertEquals(6, fetcher.fetches.get());
    }

    @Test
    void tickerHarvestReadsBothTagShapes() {
        assertEquals(List.of("TSLA"), BenzingaNewsClient.harvestTickers(
                "<a class=\"ticker-link\" data-ticker=\"TSLA\" href=\"x\">Tesla</a>"));
        assertEquals(List.of("GERN"), BenzingaNewsClient.harvestTickers(
                "<a href=\"https://www.benzinga.com/quote/GERN\">Geron</a>"));
        assertEquals(List.of("TSLA", "ROKU"), BenzingaNewsClient.harvestTickers(
                "<a data-ticker=\"TSLA\">t</a> <a data-ticker=\"ROKU\">r</a> "
                        + "<a href=\"benzinga.com/quote/TSLA\">t again</a>"),
                "duplicates collapse, first-seen order wins");
        assertTrue(BenzingaNewsClient.harvestTickers("no tags here").isEmpty());
        assertTrue(BenzingaNewsClient.harvestTickers(null).isEmpty());
    }

    @Test
    void entityDecodingHandlesTheNumericReferences() {
        // The feed armours the article HTML once more than usual: after the
        // XML layer, numeric references like "&#8217;" are still armoured —
        // the decoder resolves them into the real character.
        assertEquals("Oaktree’s fund",
                BenzingaNewsClient.stripHtml("<p>Oaktree&#8217;s fund</p>"));
        assertEquals("a & b \"quoted\"",
                BenzingaNewsClient.stripHtml("<p>a &amp; b &quot;quoted&quot;</p>"));
        assertNull(BenzingaNewsClient.stripHtml(null));
    }

    @Test
    void pubDateParsingIsTolerant() {
        assertEquals(Instant.parse("2026-06-19T02:22:20Z"),
                BenzingaNewsClient.parsePubDate("Fri, 19 Jun 2026 02:22:20 +0000"));
        assertEquals(Instant.parse("2026-06-19T02:22:20Z"),
                BenzingaNewsClient.parsePubDate("Thu, 19 Jun 2026 02:22:20 +0000"),
                "a wrong day-of-week token is tolerated — the date wins");
        assertNull(BenzingaNewsClient.parsePubDate("yesterday"));
        assertNull(BenzingaNewsClient.parsePubDate(null));
    }

    @Test
    void bareSymbolCutsOnlyTheVenueSuffix() {
        assertEquals("INTC", BenzingaNewsClient.bareSymbol("INTC.DE"));
        assertEquals("OAK-B", BenzingaNewsClient.bareSymbol("OAK-B"),
                "share-class hyphens survive — only the dot suffix falls");
        assertEquals("", BenzingaNewsClient.bareSymbol(null));
    }
}
