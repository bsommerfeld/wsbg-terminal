package de.bsommerfeld.wsbg.terminal.web.impl.sources.hackernews;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
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
 * both link shapes the API actually serves. Ported from the module world;
 * the old name leg is now the {@code newsFor(ResolvedInstrument)} door, the
 * free research query the new {@code search} door.
 */
class HackerNewsSourceTest {

    private static String fixture() {
        try (InputStream in = HackerNewsSourceTest.class
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
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            lastUrl = url;
            assertEquals(FetchUtil.DIRECT, modes[0],
                    "keyless JSON with no wall — direct leads");
            assertTrue(url.startsWith("https://hn.algolia.com/api/v1/search?query="), url);
            assertTrue(url.contains("&tags=story"), "stories only — no bare comments");
            assertTrue(url.contains("&numericFilters=created_at_i%3E"),
                    "the recency cutoff rides percent-encoded — a literal '>' is a 400 "
                            + "HTML page, not JSON (pinned live 2026-07-16)");
            int n = fetches.getAndIncrement();
            return replies.get(Math.min(n, replies.size() - 1));
        }

        @Override
        public WebResponse fetchBinary(String url, Map<String, String> headers,
                Duration timeout, FetchUtil... modes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WebResponse post(String url, Map<String, String> headers, String body,
                String contentType, Duration timeout, FetchUtil... modes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hostCoolingDown(String url) {
            return false;
        }
    }

    private static WebResponse ok() {
        return WebResponse.text(200, fixture(), Map.of());
    }

    private static ResolvedInstrument named(String name) {
        return ResolvedInstrument.ofName(name);
    }

    @Test
    void parseMapsTheLiveHitFields() {
        List<Article> items = HackerNewsSource.parse(fixture(), Set.of("nvidia"));
        assertNotNull(items);
        assertEquals(4, items.size(),
                "4 of 5 fixture hits name Nvidia in the TITLE; the Ask HN hit does not");

        Article vram = items.get(0);
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
        List<Article> items = HackerNewsSource.parse(fixture(), Set.of("blind", "programmers"));
        assertNotNull(items);
        assertEquals(1, items.size());
        assertEquals("https://news.ycombinator.com/item?id=18478776", items.get(0).link());
        assertEquals("18478776", items.get(0).uuid());
        assertEquals("202 Punkte, 43 Kommentare auf Hacker News", items.get(0).summary());
    }

    @Test
    void precisionFilterKeepsOnlyTitleNamedCompanies() {
        HackerNewsSource source = new HackerNewsSource(new FakeFetcher(ok()));

        List<Article> coreweave = source.newsFor(named("CoreWeave, Inc."), 10);
        assertEquals(1, coreweave.size(),
                "Algolia matches loosely (URL text too) — only a TITLE naming the "
                        + "company survives the house filter");
        assertTrue(coreweave.get(0).title().contains("CoreWeave"));

        assertEquals(4, source.newsFor(named("NVIDIA Corporation"), 10).size(),
                "generic legal words (Corporation) never carry the match — 'nvidia' does");
        assertEquals(2, source.newsFor(named("NVIDIA Corporation"), 2).size(),
                "the limit caps after filtering");
        assertTrue(source.newsFor(named("Rheinmetall AG"), 10).isEmpty(),
                "a company the answer doesn't title-name yields nothing");
        assertTrue(source.newsFor(named("AG"), 10).isEmpty(),
                "a name with ONLY generic words never even queries");
        assertTrue(source.newsFor(named(""), 10).isEmpty());
        assertTrue(source.newsFor(named("Nvidia"), 0).isEmpty());
    }

    @Test
    void freeSearchCarriesTheQueryVerbatimAndUnfiltered() {
        FakeFetcher fetcher = new FakeFetcher(ok());
        HackerNewsSource source = new HackerNewsSource(fetcher);

        List<Article> items = source.search("Nvidia GPU cloud", 10);
        assertEquals(5, items.size(),
                "the free query is the caller's own phrasing — NO precision filter");
        assertTrue(fetcher.lastUrl.contains("query=Nvidia+GPU+cloud"),
                "the query rides verbatim: " + fetcher.lastUrl);

        assertEquals(2, source.search("Nvidia GPU cloud", 2).size(), "limit caps on return");
        assertEquals(1, fetcher.fetches.get(), "the free query rides the same politeness cache");
        assertTrue(source.search("  ", 10).isEmpty());
        assertTrue(source.search(null, 10).isEmpty());
        assertTrue(source.search("x", 0).isEmpty());
        assertEquals(1, fetcher.fetches.get(), "blank/zero-limit queries never fetch");
    }

    @Test
    void selfPostBodyCarriesTheMatchButAUrlNeverDoes() {
        // Ask-HN self posts ship their body as story_text — a company named
        // only there still counts (mandate 2026-07-16); a URL match alone
        // stays rejected.
        String json = """
                {"hits":[
                  {"objectID":"1","title":"Ask HN: which defense stocks are you watching?",
                   "story_text":"<p>Rheinmetall is printing money this year.</p>",
                   "points":42,"num_comments":17,"created_at":"2026-07-16T10:00:00Z"},
                  {"objectID":"2","title":"A completely unrelated post",
                   "url":"https://example.com/rheinmetall-teardown",
                   "points":3,"num_comments":0,"created_at":"2026-07-16T09:00:00Z"}
                ]}""";
        List<Article> items = HackerNewsSource.parse(json, Set.of("rheinmetall"));
        assertEquals(1, items.size(),
                "the self-post body matches, the URL-only hit is still dropped");
        assertTrue(items.get(0).title().startsWith("Ask HN"));
    }

    @Test
    void perQueryCacheAnswersABurstWithOneFetch() {
        FakeFetcher fetcher = new FakeFetcher(ok());
        HackerNewsSource source = new HackerNewsSource(fetcher);

        assertEquals(4, source.newsFor(named("Nvidia"), 10).size());
        assertEquals(4, source.newsFor(named("NVIDIA Corporation"), 10).size());
        assertEquals(1, fetcher.fetches.get(),
                "both names normalise to the query 'nvidia' — ONE request per TTL");

        assertEquals(1, source.newsFor(named("Nebius"), 10).size());
        assertEquals(2, fetcher.fetches.get(), "a different query is its own cache entry");
    }

    @Test
    void failuresAreNeverCachedButCleanEmptyAnswersAre() {
        FakeFetcher fetcher = new FakeFetcher(
                WebResponse.text(500, "boom", Map.of()),
                WebResponse.text(200, "<html><body>400 Bad Request</body></html>", Map.of()),
                ok());
        HackerNewsSource source = new HackerNewsSource(fetcher);

        assertTrue(source.newsFor(named("Nvidia"), 10).isEmpty(), "a 500 is a miss");
        assertTrue(source.newsFor(named("Nvidia"), 10).isEmpty(),
                "an HTML 200 (the unencoded-'>' failure shape) is a miss, not JSON");
        assertEquals(4, source.newsFor(named("Nvidia"), 10).size(),
                "neither failure was cached — the third call refetches and succeeds");
        assertEquals(3, fetcher.fetches.get());

        assertEquals(4, source.newsFor(named("Nvidia"), 10).size());
        assertEquals(3, fetcher.fetches.get(), "the good answer IS cached");
    }

    @Test
    void parseToleratesGarbage() {
        Set<String> words = Set.of("nvidia");
        assertNull(HackerNewsSource.parse(null, words));
        assertNull(HackerNewsSource.parse("", words));
        assertNull(HackerNewsSource.parse("not json at all", words));
        assertNull(HackerNewsSource.parse("<html><body>404</body></html>", words));
        assertNull(HackerNewsSource.parse("{\"error\":\"nope\"}", words),
                "JSON without a hits array is garbage, not an empty answer");
        String torn = fixture().substring(0, fixture().length() / 2);
        assertNull(HackerNewsSource.parse(torn, words));

        List<Article> incomplete = HackerNewsSource.parse(
                "{\"hits\":[{\"objectID\":\"1\"},{\"title\":\"Nvidia x\"},"
                        + "{\"objectID\":\"2\",\"title\":\"Nvidia y\",\"created_at\":\"junk\"}]}",
                words);
        assertNotNull(incomplete);
        assertEquals(1, incomplete.size(), "hits missing id or title are skipped");
        assertNull(incomplete.get(0).publishedAt(),
                "an unparseable created_at yields null, never a guessed timestamp");
    }

    @Test
    void tickerAndIsinAloneAreNoAddress() {
        FakeFetcher fetcher = new FakeFetcher(ok());
        HackerNewsSource source = new HackerNewsSource(fetcher);
        ResolvedInstrument keysOnly = new ResolvedInstrument(
                Isin.parse("US67066G1040"), Ticker.parse("NVDA"), "");
        assertTrue(source.newsFor(keysOnly, 10).isEmpty(),
                "HN knows neither tickers nor ISINs — only the name addresses it");
        assertEquals(0, fetcher.fetches.get(), "no-op legs never touch the wire");
    }
}
