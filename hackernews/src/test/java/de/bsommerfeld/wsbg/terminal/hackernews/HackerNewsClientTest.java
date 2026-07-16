package de.bsommerfeld.wsbg.terminal.hackernews;

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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HN Algolia search — fixture is a live response (2026-07-16) trimmed to
 * 4 Nvidia story hits plus one REAL url-less Ask HN self post (Ask HN and
 * other self posts carry no {@code url} field at all), so the fixture pins
 * both link shapes the API actually serves.
 */
class HackerNewsClientTest {

    private static String fixture() {
        try (InputStream in = HackerNewsClientTest.class
                .getResourceAsStream("/hn-algolia-search.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing", e);
        }
    }

    /** Fake transport answering a fixed sequence of bodies, counting fetches. */
    private static final class FakeFetcher implements WebFetcher {
        final AtomicInteger fetches = new AtomicInteger();
        volatile String lastUrl;
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
            lastUrl = url;
            assertTrue(url.startsWith("https://hn.algolia.com/api/v1/search?query="), url);
            assertTrue(url.contains("&tags=story"), "stories only — no bare comments");
            assertTrue(url.contains("&numericFilters=created_at_i%3E"),
                    "the recency cutoff rides percent-encoded — a literal '>' is a 400 "
                            + "HTML page, not JSON (pinned live 2026-07-16)");
            int n = fetches.getAndIncrement();
            return replies.get(Math.min(n, replies.size() - 1));
        }
    }

    private static WebResponse ok() {
        return new WebResponse(200, fixture(), Map.of());
    }

    @Test
    void parseMapsTheLiveHitFields() {
        List<RawNewsItem> items =
                HackerNewsClient.parse(fixture(), Set.of("nvidia"));
        assertNotNull(items);
        assertEquals(4, items.size(),
                "4 of 5 fixture hits name Nvidia in the TITLE; the Ask HN hit does not");

        RawNewsItem vram = items.get(0);
        assertEquals("48377404", vram.uuid(), "objectID is the stable identity");
        assertEquals("Use your Nvidia GPU's VRAM as swap space on Linux", vram.title());
        assertEquals("Hacker News", vram.publisher());
        assertEquals("https://github.com/c0dejedi/nbd-vram", vram.link(),
                "a hit with a url links to the story itself");
        assertEquals(Instant.parse("2026-06-02T22:55:33Z"), vram.publishedAt());
        assertEquals("472 Punkte, 126 Kommentare auf Hacker News", vram.summary(),
                "points/comments ARE the salience signal — they ride in the line");
        assertTrue(vram.relatedTickers().isEmpty());
        assertNull(vram.isin());
    }

    @Test
    void urlLessSelfPostLinksToItsOwnHnThread() {
        // Ask HN / self posts carry NO url field (pinned live 2026-07-16) —
        // the HN discussion thread IS the item then.
        List<RawNewsItem> items =
                HackerNewsClient.parse(fixture(), Set.of("blind", "programmers"));
        assertNotNull(items);
        assertEquals(1, items.size());
        assertEquals("https://news.ycombinator.com/item?id=18478776", items.get(0).link());
        assertEquals("18478776", items.get(0).uuid());
        assertEquals("202 Punkte, 43 Kommentare auf Hacker News", items.get(0).summary());
    }

    @Test
    void precisionFilterKeepsOnlyTitleNamedCompanies() {
        HackerNewsClient client = new HackerNewsClient(new FakeFetcher(ok()));

        List<RawNewsItem> coreweave = client.newsForName("CoreWeave, Inc.", 10);
        assertEquals(1, coreweave.size(),
                "Algolia matches loosely (URL text too) — only a TITLE naming the "
                        + "company survives the house filter");
        assertTrue(coreweave.get(0).title().contains("CoreWeave"));

        assertEquals(4, client.newsForName("NVIDIA Corporation", 10).size(),
                "generic legal words (Corporation) never carry the match — 'nvidia' does");
        assertEquals(2, client.newsForName("NVIDIA Corporation", 2).size(),
                "the limit caps after filtering");
        assertTrue(client.newsForName("Rheinmetall AG", 10).isEmpty(),
                "a company the answer doesn't title-name yields nothing");
        assertTrue(client.newsForName("AG", 10).isEmpty(),
                "a name with ONLY generic words never even queries");
        assertTrue(client.newsForName(null, 10).isEmpty());
        assertTrue(client.newsForName("Nvidia", 0).isEmpty());
    }

    @Test
    void perQueryCacheAnswersABurstWithOneFetch() {
        FakeFetcher fetcher = new FakeFetcher(ok());
        HackerNewsClient client = new HackerNewsClient(fetcher);

        assertEquals(4, client.newsForName("Nvidia", 10).size());
        assertEquals(4, client.newsForName("NVIDIA Corporation", 10).size());
        assertEquals(1, fetcher.fetches.get(),
                "both names normalise to the query 'nvidia' — ONE request per TTL");

        assertEquals(1, client.newsForName("Nebius", 10).size());
        assertEquals(2, fetcher.fetches.get(), "a different query is its own cache entry");
    }

    @Test
    void failuresAreNeverCachedButCleanEmptyAnswersAre() {
        FakeFetcher fetcher = new FakeFetcher(
                new WebResponse(500, "boom", Map.of()),
                new WebResponse(200, "<html><body>400 Bad Request</body></html>", Map.of()),
                ok());
        HackerNewsClient client = new HackerNewsClient(fetcher);

        assertTrue(client.newsForName("Nvidia", 10).isEmpty(), "a 500 is a miss");
        assertTrue(client.newsForName("Nvidia", 10).isEmpty(),
                "an HTML 200 (the unencoded-'>' failure shape) is a miss, not JSON");
        assertEquals(4, client.newsForName("Nvidia", 10).size(),
                "neither failure was cached — the third call refetches and succeeds");
        assertEquals(3, fetcher.fetches.get());

        assertEquals(4, client.newsForName("Nvidia", 10).size());
        assertEquals(3, fetcher.fetches.get(), "the good answer IS cached");
    }

    @Test
    void parseToleratesGarbage() {
        Set<String> words = Set.of("nvidia");
        assertNull(HackerNewsClient.parse(null, words));
        assertNull(HackerNewsClient.parse("", words));
        assertNull(HackerNewsClient.parse("not json at all", words));
        assertNull(HackerNewsClient.parse("<html><body>404</body></html>", words));
        assertNull(HackerNewsClient.parse("{\"error\":\"nope\"}", words),
                "JSON without a hits array is garbage, not an empty answer");
        String torn = fixture().substring(0, fixture().length() / 2);
        assertNull(HackerNewsClient.parse(torn, words));

        List<RawNewsItem> incomplete = HackerNewsClient.parse(
                "{\"hits\":[{\"objectID\":\"1\"},{\"title\":\"Nvidia x\"},"
                        + "{\"objectID\":\"2\",\"title\":\"Nvidia y\",\"created_at\":\"junk\"}]}",
                words);
        assertNotNull(incomplete);
        assertEquals(1, incomplete.size(), "hits missing id or title are skipped");
        assertNull(incomplete.get(0).publishedAt(),
                "an unparseable created_at yields null, never a guessed timestamp");
    }

    @Test
    void symbolAndIsinLegsAreNoOps() {
        FakeFetcher fetcher = new FakeFetcher(ok());
        HackerNewsClient client = new HackerNewsClient(fetcher);
        assertTrue(client.newsFor("NVDA", 10).isEmpty());
        assertTrue(client.newsForIsin("US67066G1040", 10).isEmpty());
        assertEquals(0, fetcher.fetches.get(), "no-op legs never touch the wire");
    }
}
