package de.bsommerfeld.wsbg.terminal.bluesky;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bluesky post search — fixture is a live {@code searchPosts} response mix
 * (2026-07-16, api.bsky.app) trimmed to 6 posts: two $NVDA cashtag posts, a
 * Rheinmetall news-bot post, Swedish "ABB-aktien" noise (why the precision
 * filter exists), a German Aktien reply with no company, and a bridged
 * ({@code *.web.brid.gy}) post with EMPTY text. The fixture carries the live
 * createdAt zoo: micros + {@code +00:00}, plain seconds + {@code +00:00},
 * and millis + {@code Z}.
 */
class BlueskyNewsClientTest {

    private static String fixture() {
        try (InputStream in = BlueskyNewsClientTest.class
                .getResourceAsStream("/bluesky-searchposts.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing", e);
        }
    }

    /** Fake transport answering a fixed sequence of bodies, recording every URL. */
    private static final class FakeFetcher implements WebFetcher {
        final List<String> urls = new ArrayList<>();
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
            urls.add(url);
            return replies.get(Math.min(urls.size() - 1, replies.size() - 1));
        }
    }

    private static WebResponse ok() {
        return new WebResponse(200, fixture(), Map.of());
    }

    private static BlueskyNewsClient client(FakeFetcher fetcher) {
        BlueskyNewsClient c = new BlueskyNewsClient(fetcher);
        c.searchUrl = "https://fake.test/xrpc/app.bsky.feed.searchPosts";
        return c;
    }

    @Test
    void parseMapsTheLivePostFields() {
        List<RawNewsItem> items = BlueskyNewsClient.parse(fixture());
        assertEquals(5, items.size(),
                "6 fixture posts, minus the bridged EMPTY-text post — text-less posts are skipped");

        RawNewsItem nvda = items.get(0);
        assertEquals("at://did:plc:v6ktaqawvwl54hpnyyoua7rd/app.bsky.feed.post/3mqrbirlhp42e",
                nvda.uuid(), "the at-uri is the stable identity");
        assertEquals("$NVDA powers Sharon AI's $1.32B cloud deal | @yahoofinance.com",
                nvda.title(), "a short single-line post IS its own title");
        assertEquals("Bluesky (@polaronfinance.bsky.social)", nvda.publisher(),
                "the publisher names the source AND the account — these are social posts");
        assertEquals("https://bsky.app/profile/polaronfinance.bsky.social/post/3mqrbirlhp42e",
                nvda.link(), "the at-uri is rebuilt into the public web permalink");
        assertEquals(nvda.title(), nvda.summary(), "summary = the full post text");
        assertEquals(Instant.parse("2026-07-16T12:49:04.833190Z"), nvda.publishedAt(),
                "createdAt with MICROSECONDS and a +00:00 offset (client-written, live shape)");

        RawNewsItem longPost = items.get(1);
        assertTrue(longPost.title().length() <= 120, "long posts get a capped title");
        assertTrue(longPost.title().endsWith("…"));
        assertTrue(longPost.summary().contains("#Hyperscalers"),
                "the summary keeps the FULL text the title cut");

        RawNewsItem rhm = items.get(2);
        assertEquals("Rheinmetall, Space Norway Expand Space-Based Maritime Domain "
                + "Awareness Partnership", rhm.title(),
                "a multi-line post's title is its FIRST line, trimmed");
        assertEquals(Instant.parse("2026-07-16T12:01:22Z"), rhm.publishedAt(),
                "createdAt with plain seconds and a +00:00 offset");

        assertEquals(Instant.parse("2026-07-16T12:55:50.616Z"),
                items.get(4).publishedAt(), "createdAt with millis and Z");
    }

    @Test
    void parseToleratesGarbageAndHtmlAnswers() {
        assertNull(BlueskyNewsClient.parse("<html><body>403 Forbidden</body></html>"),
                "an HTML answer is NOT a searchPosts body — null so it is never cached");
        assertNull(BlueskyNewsClient.parse("not json at all"));
        assertNull(BlueskyNewsClient.parse("{\"error\":\"InvalidRequest\"}"),
                "an XRPC error object has no posts[] — a miss, not an empty answer");
        assertNull(BlueskyNewsClient.parse(""));
        assertNull(BlueskyNewsClient.parse(null));
        // Post-shaped fragments missing uri/handle/text are skipped, never thrown on.
        assertEquals(0, BlueskyNewsClient.parse(
                "{\"posts\":[{\"cid\":\"x\"},{\"uri\":\"at://did/app.bsky.feed.post/rk\"}]}").size());
        assertEquals(0, BlueskyNewsClient.parse("{\"posts\":[]}").size(),
                "an empty posts[] IS a valid (cacheable) answer");
    }

    @Test
    void symbolLegSearchesTheCashtag() {
        FakeFetcher fetcher = new FakeFetcher(ok());
        BlueskyNewsClient client = client(fetcher);

        List<RawNewsItem> items = client.newsFor("NVDA", 10);
        assertEquals(5, items.size(),
                "the cashtag leg trusts the search — cashtag hits are precise by construction");
        assertEquals(1, fetcher.urls.size());
        assertTrue(fetcher.urls.get(0).contains("q=%24NVDA"),
                "the query is the CASHTAG $NVDA, URL-encoded: " + fetcher.urls.get(0));
        assertTrue(fetcher.urls.get(0).contains("sort=latest"));
    }

    @Test
    void cashtagBuildTakesTheBaseBeforeTheSuffix() {
        assertEquals("$RHM", BlueskyNewsClient.cashtagFor("RHM.DE"),
                "suffixed symbols search the base part before the dot");
        assertEquals("$NVDA", BlueskyNewsClient.cashtagFor("NVDA"));
        assertEquals("$VOW3", BlueskyNewsClient.cashtagFor("vow3.de"), "uppercased");
        assertNull(BlueskyNewsClient.cashtagFor("BRK-B"),
                "a hyphenated share class is not a cashtag — no-op, not a wrong search");
        assertNull(BlueskyNewsClient.cashtagFor("^GDAXI"));
        assertNull(BlueskyNewsClient.cashtagFor("  "));
        assertNull(BlueskyNewsClient.cashtagFor(null));
        assertNull(BlueskyNewsClient.cashtagFor(".DE"));
    }

    @Test
    void nameLegFiltersLooseSearchHitsByTextRelevance() {
        FakeFetcher fetcher = new FakeFetcher(ok());
        BlueskyNewsClient client = client(fetcher);

        // Bluesky's search matches loosely — the fixture mixes Swedish
        // "ABB-aktien" noise and a German no-company Aktien reply among the
        // hits. Only the post whose TEXT names Rheinmetall survives.
        List<RawNewsItem> items = client.newsForName("Rheinmetall AG", 10);
        assertEquals(1, items.size(), "precision over recall");
        assertTrue(items.get(0).summary().contains("Rheinmetall"));
        assertTrue(fetcher.urls.get(0).contains("q=Rheinmetall"),
                "the legal suffix is not part of the query: " + fetcher.urls.get(0));
        assertFalse(fetcher.urls.get(0).contains("AG"));

        assertTrue(client.newsForName("Siemens Energy", 10).isEmpty(),
                "no text names the company — everything is filtered out");
    }

    @Test
    void nameQueryKeepsSignificantWordsInOriginalCasing() {
        assertEquals("Rheinmetall", BlueskyNewsClient.nameQuery("Rheinmetall AG"));
        assertEquals("NVIDIA", BlueskyNewsClient.nameQuery("NVIDIA Corporation"));
        assertEquals("Amazon.com", BlueskyNewsClient.nameQuery("Amazon.com, Inc."),
                "the comma cuts the legal tail");
        assertEquals("Münchener Rück", BlueskyNewsClient.nameQuery("Münchener Rück AG"),
                "umlauts stay in the QUERY (normalisation is for matching only)");
    }

    @Test
    void queryCacheAnswersABurstWithOneFetchPerQuery() {
        FakeFetcher fetcher = new FakeFetcher(ok());
        BlueskyNewsClient client = client(fetcher);

        assertEquals(5, client.newsFor("NVDA", 10).size());
        assertEquals(2, client.newsFor("NVDA", 2).size(), "limit caps on return");
        assertEquals(5, client.newsFor("NVDA", 10).size());
        assertEquals(1, fetcher.urls.size(),
                "a burst on the SAME query makes exactly ONE search request");

        client.newsForName("Rheinmetall", 10);
        assertEquals(2, fetcher.urls.size(), "a different query is its own fetch");
    }

    @Test
    void garbageAnswersAreAMissAndNeverPoisonTheCache() {
        FakeFetcher fetcher = new FakeFetcher(
                new WebResponse(200, "<html><body>WAF says no</body></html>", Map.of()),
                new WebResponse(500, "boom", Map.of()),
                ok());
        BlueskyNewsClient client = client(fetcher);

        assertTrue(client.newsFor("NVDA", 10).isEmpty(), "an HTML 200 is a miss");
        assertTrue(client.newsFor("NVDA", 10).isEmpty(), "a 500 is a miss");
        assertEquals(5, client.newsFor("NVDA", 10).size(),
                "no miss was cached — the third call refetches and succeeds");
        assertEquals(3, fetcher.urls.size());
    }

    @Test
    void blankAndZeroLimitQueriesNeverFetch() {
        FakeFetcher fetcher = new FakeFetcher(ok());
        BlueskyNewsClient client = client(fetcher);
        assertTrue(client.newsFor("NVDA", 0).isEmpty());
        assertTrue(client.newsFor(null, 10).isEmpty());
        assertTrue(client.newsForName("  ", 10).isEmpty());
        assertTrue(client.newsForName("AG Inc", 10).isEmpty(),
                "a name of only stopwords has no query");
        assertTrue(client.newsForIsin("DE0007030009", 10).isEmpty(), "ISIN leg is a no-op");
        assertEquals(0, fetcher.urls.size());
    }

    @Test
    void postLinkRebuildsTheAtUri() {
        assertEquals("https://bsky.app/profile/polaronfinance.bsky.social/post/3mqrbirlhp42e",
                BlueskyNewsClient.postLink(
                        "at://did:plc:v6ktaqawvwl54hpnyyoua7rd/app.bsky.feed.post/3mqrbirlhp42e",
                        "polaronfinance.bsky.social"));
        assertNull(BlueskyNewsClient.postLink("at://did:plc:x/app.bsky.feed.post/",
                "h.bsky.social"), "an rkey-less at-uri has no permalink");
        assertNull(BlueskyNewsClient.postLink("garbage", "h.bsky.social"));
        assertNull(BlueskyNewsClient.postLink(null, "h.bsky.social"));
        assertNull(BlueskyNewsClient.postLink("at://x/y/z", ""));
    }

    @Test
    void titleIsFirstLineCappedAtAWordBoundary() {
        assertEquals("short post", BlueskyNewsClient.titleOf("short post"));
        assertEquals("first line", BlueskyNewsClient.titleOf("first line\nsecond line"));
        String word1 = "a".repeat(100);
        String capped = BlueskyNewsClient.titleOf(word1 + " " + "b".repeat(30));
        assertTrue(capped.length() <= 120);
        assertEquals(word1 + "…", capped, "cut at a word boundary, not mid-word");
    }

    @Test
    void createdAtParsingCoversTheLiveShapeZoo() {
        assertEquals(Instant.parse("2026-07-16T12:49:04.833190Z"),
                BlueskyNewsClient.parseCreatedAt("2026-07-16T12:49:04.833190+00:00"));
        assertEquals(Instant.parse("2026-07-16T12:01:22Z"),
                BlueskyNewsClient.parseCreatedAt("2026-07-16T12:01:22+00:00"));
        assertEquals(Instant.parse("2026-07-16T12:55:50.616Z"),
                BlueskyNewsClient.parseCreatedAt("2026-07-16T12:55:50.616Z"));
        assertEquals(Instant.parse("2026-07-16T10:55:50Z"),
                BlueskyNewsClient.parseCreatedAt("2026-07-16T12:55:50+02:00"),
                "a non-UTC client offset normalises to the instant");
        assertNull(BlueskyNewsClient.parseCreatedAt("yesterday"));
        assertNull(BlueskyNewsClient.parseCreatedAt(""));
        assertNull(BlueskyNewsClient.parseCreatedAt(null));
    }

    @Test
    void textMatchingIsUmlautTolerant() {
        Set<String> words = BlueskyNewsClient.significantWords("Münchener Rück");
        assertTrue(BlueskyNewsClient.textMatches("Muenchener Rueck hebt Prognose", words));
        assertTrue(BlueskyNewsClient.textMatches("Die Münchener Rück liefert.", words));
        assertFalse(BlueskyNewsClient.textMatches("Aktien heute uneinheitlich", words));
        assertFalse(BlueskyNewsClient.textMatches(null, words));
    }
}
