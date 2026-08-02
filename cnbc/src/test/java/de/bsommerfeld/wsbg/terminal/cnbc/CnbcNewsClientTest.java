package de.bsommerfeld.wsbg.terminal.cnbc;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser and fan tests against live-captured CNBC fixtures (2026-08-02). */
class CnbcNewsClientTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = CnbcNewsClientTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- section feed ----

    @Test
    void parsesFeedItemsWithStableIdAndTeaser() throws Exception {
        List<RawNewsItem> items = CnbcNewsClient.parseFeed(fixture("cnbc-earnings-rss.xml"));

        assertFalse(items.isEmpty());
        RawNewsItem first = items.get(0);
        assertEquals("108343148", first.uuid(), "metadata:id is the identity, not the guid");
        assertTrue(first.title().startsWith("From a Fed decision"));
        assertEquals(CnbcNewsClient.PUBLISHER, first.publisher());
        assertTrue(first.link().startsWith("https://www.cnbc.com/2026/08/01/"));
        assertEquals("Wall Street got back to its winning ways last week.", first.summary());
        assertFalse(first.sponsored());
        assertEquals(Instant.parse("2026-08-01T12:16:13Z"), first.publishedAt());
        assertTrue(first.relatedTickers().isEmpty(), "CNBC tags no tickers");
    }

    @Test
    void feedGarbageYieldsEmptyListNotException() {
        assertTrue(CnbcNewsClient.parseFeed("<rss><channel><item></item>").isEmpty());
        assertTrue(CnbcNewsClient.parseFeed("not xml at all").isEmpty());
        assertTrue(CnbcNewsClient.parseFeed("").isEmpty());
        assertTrue(CnbcNewsClient.parseFeed(null).isEmpty());
    }

    // ---- search ----

    @Test
    void parsesSearchResults() throws Exception {
        List<RawNewsItem> items = CnbcNewsClient.parseSearch(fixture("cnbc-search.json"));

        assertFalse(items.isEmpty());
        RawNewsItem first = items.get(0);
        assertNotNull(first.title());
        assertTrue(first.link().startsWith("https://www.cnbc.com/"));
        assertNotNull(first.publishedAt());
        assertNotNull(first.summary());
    }

    @Test
    void gatedArticlesKeepHeadlineButAreMarkedAsPro() throws Exception {
        List<RawNewsItem> items = CnbcNewsClient.parseSearch(fixture("cnbc-search.json"));

        List<RawNewsItem> gated = items.stream()
                .filter(it -> CnbcNewsClient.PUBLISHER_GATED.equals(it.publisher()))
                .toList();
        assertFalse(gated.isEmpty(), "fixture carries premium/investingClub items");
        gated.forEach(it -> {
            assertNotNull(it.title(), "the headline survives the wall");
            assertFalse(it.title().isBlank());
        });
    }

    @Test
    void videoEntriesAreDropped() throws Exception {
        String json = fixture("cnbc-search.json");
        assertTrue(json.contains("cnbcvideo"), "fixture must contain a video entry");
        List<RawNewsItem> items = CnbcNewsClient.parseSearch(json);
        assertTrue(items.stream().noneMatch(it -> it.link().contains("/video/")),
                "video items carry no article text");
    }

    @Test
    void searchGarbageYieldsEmptyListNotException() {
        assertTrue(CnbcNewsClient.parseSearch("{\"results\":\"nope\"}").isEmpty());
        assertTrue(CnbcNewsClient.parseSearch("<html>error</html>").isEmpty());
        assertTrue(CnbcNewsClient.parseSearch("").isEmpty());
        assertTrue(CnbcNewsClient.parseSearch(null).isEmpty());
    }

    // ---- dates ----

    @Test
    void parsesBothDateShapes() {
        assertEquals(Instant.parse("2026-08-01T12:16:13Z"),
                CnbcNewsClient.parseRssDate("Sat, 01 Aug 2026 12:16:13 GMT"));
        assertEquals(Instant.parse("2026-07-27T21:10:17Z"),
                CnbcNewsClient.parseSearchDate("2026-07-27T21:10:17+0000"),
                "queryly's offset carries no colon");
        assertNull(CnbcNewsClient.parseRssDate("nonsense"));
        assertNull(CnbcNewsClient.parseSearchDate("nonsense"));
        assertNull(CnbcNewsClient.parseSearchDate(null));
    }

    // ---- precision filter ----

    @Test
    void significantWordsDropLegalFormsAndShortTokens() {
        assertEquals(Set.of("siemens", "energy"),
                CnbcNewsClient.significantWords("Siemens Energy AG"));
        assertEquals(Set.of("apple"), CnbcNewsClient.significantWords("Apple Inc."));
        assertTrue(CnbcNewsClient.significantWords("AG SE").isEmpty());
    }

    @Test
    void titleMatchNeedsAWholeWord() {
        Set<String> words = CnbcNewsClient.significantWords("Rheinmetall");
        assertTrue(CnbcNewsClient.titleMatches("Rheinmetall raises guidance", words));
        assertFalse(CnbcNewsClient.titleMatches("Der Rheinpegel steigt", words),
                "stem overlap must not count as a hit");
    }

    // ---- fans ----

    @Test
    void symbolFanStaysSilent() {
        assertTrue(new CnbcNewsClient(new FakeFetcher(Map.of())).newsFor("NVDA", 5).isEmpty());
    }

    @Test
    void nameFanMergesPoolAndSearchWithoutDuplicates() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                "rss", fixture("cnbc-earnings-rss.xml"),
                "queryly", fixture("cnbc-search.json")));
        CnbcNewsClient client = new CnbcNewsClient(fetcher);

        List<RawNewsItem> hits = client.newsForName("Nvidia", 50);

        assertFalse(hits.isEmpty());
        long distinctLinks = hits.stream().map(RawNewsItem::link).distinct().count();
        assertEquals(hits.size(), distinctLinks, "merge must dedupe by link");
        hits.forEach(it -> assertTrue(
                (it.title() + " " + (it.summary() == null ? "" : it.summary()))
                        .toLowerCase().contains("nvidia"),
                "precision filter: " + it.title()));
    }

    @Test
    void nameFanSortsNewestFirst() throws Exception {
        CnbcNewsClient client = new CnbcNewsClient(new FakeFetcher(Map.of(
                "rss", fixture("cnbc-earnings-rss.xml"),
                "queryly", fixture("cnbc-search.json"))));

        List<RawNewsItem> hits = client.newsForName("Nvidia", 50);

        Instant previous = null;
        for (RawNewsItem it : hits) {
            if (previous != null && it.publishedAt() != null) {
                assertFalse(it.publishedAt().isAfter(previous), "not newest-first");
            }
            if (it.publishedAt() != null) previous = it.publishedAt();
        }
    }

    @Test
    void poolIsFetchedOncePerTtl() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                "rss", fixture("cnbc-earnings-rss.xml"),
                "queryly", fixture("cnbc-search.json")));
        CnbcNewsClient client = new CnbcNewsClient(fetcher);

        client.newsForName("Nvidia", 5);
        int afterFirst = fetcher.count("rss");
        client.newsForName("Apple", 5);

        assertEquals(afterFirst, fetcher.count("rss"), "second call must reuse the pool");
        assertEquals(CnbcNewsClient.SECTION_FEEDS.size(), afterFirst,
                "one fetch per wired section feed");
    }

    @Test
    void archiveWindowKeepsOnlyItemsInsideTheWindow() throws Exception {
        CnbcNewsClient client = new CnbcNewsClient(new FakeFetcher(Map.of(
                "queryly", fixture("cnbc-search.json"))));

        List<RawNewsItem> hits =
                client.newsForNameWindow("Nvidia", null, "2026-07-30", "2026-08-01", 20);

        hits.forEach(it -> {
            assertTrue(it.publishedAt().isBefore(Instant.parse("2026-08-01T00:00:00Z")));
            assertFalse(it.publishedAt().isBefore(Instant.parse("2026-07-30T00:00:00Z")));
        });
    }

    @Test
    void archiveWindowRejectsUnparseableBounds() {
        CnbcNewsClient client = new CnbcNewsClient(new FakeFetcher(Map.of()));
        assertTrue(client.newsForNameWindow("Nvidia", null, "nope", "2026-08-01", 10).isEmpty());
        assertTrue(client.newsForNameWindow("Nvidia", null, "2026-07-01", null, 10).isEmpty());
    }

    @Test
    void outageKeepsThePreviousPool() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                "rss", fixture("cnbc-earnings-rss.xml")));
        CnbcNewsClient client = new CnbcNewsClient(fetcher);
        assertFalse(client.newsForName("Fed", 10).isEmpty());

        fetcher.failAll = true;
        // TTL has not elapsed, so the cached pool answers regardless.
        assertFalse(client.newsForName("Fed", 10).isEmpty());
    }

    // ---- general (instrument-free) stream ----

    @Test
    void latestServesTheWholePoolNewestFirst() throws Exception {
        CnbcNewsClient client = new CnbcNewsClient(new FakeFetcher(Map.of(
                "rss", fixture("cnbc-earnings-rss.xml"))));

        List<RawNewsItem> latest = client.latest(3);

        assertEquals(3, latest.size());
        Instant previous = null;
        for (RawNewsItem it : latest) {
            if (previous != null) assertFalse(it.publishedAt().isAfter(previous));
            previous = it.publishedAt();
        }
    }

    @Test
    void latestCostsNoExtraFetchBeyondThePool() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of("rss", fixture("cnbc-earnings-rss.xml")));
        CnbcNewsClient client = new CnbcNewsClient(fetcher);

        client.newsForName("Fed", 5);
        int afterName = fetcher.count("rss");
        client.latest(10);

        assertEquals(afterName, fetcher.count("rss"), "general stream rides the same pool");
    }

    @Test
    void sectionReachesFeedsBeyondTheWiredTwelve() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of("rss", fixture("cnbc-earnings-rss.xml")));
        CnbcNewsClient client = new CnbcNewsClient(fetcher);

        // 10000116 = Retail: populated at CNBC, deliberately not in the standing poll.
        assertFalse(CnbcNewsClient.SECTION_FEEDS.containsKey("10000116"));
        List<RawNewsItem> retail = client.section("10000116", 5);

        assertFalse(retail.isEmpty());
        assertEquals(1, fetcher.count("10000116"), "section fetches exactly its own feed");
    }

    @Test
    void generalAccessorsRejectEmptyInput() {
        CnbcNewsClient client = new CnbcNewsClient(new FakeFetcher(Map.of()));
        assertTrue(client.latest(0).isEmpty());
        assertTrue(client.section(null, 5).isEmpty());
        assertTrue(client.section("  ", 5).isEmpty());
        assertTrue(client.searchByRelevance("", 5).isEmpty());
        assertFalse(client.sections().isEmpty());
    }

    @Test
    void relevanceSearchDoesNotApplyTheNameFilter() throws Exception {
        CnbcNewsClient client = new CnbcNewsClient(new FakeFetcher(Map.of(
                "queryly", fixture("cnbc-search.json"))));

        // A theme query: results must survive even though no title carries the word.
        List<RawNewsItem> hits = client.searchByRelevance("tariffs", 20);

        assertFalse(hits.isEmpty(), "theme search must not be title-filtered");
    }

    /** Serves fixtures by URL fragment and counts calls. */
    private static final class FakeFetcher implements WebFetcher {
        private final Map<String, String> byFragment;
        private final List<String> calls = new ArrayList<>();
        boolean failAll;

        FakeFetcher(Map<String, String> byFragment) {
            this.byFragment = byFragment;
        }

        int count(String fragment) {
            return (int) calls.stream().filter(u -> u.contains(fragment)).count();
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            calls.add(url);
            if (failAll) return WebResponse.failure();
            for (Map.Entry<String, String> e : byFragment.entrySet()) {
                if (url.contains(e.getKey())) {
                    return new WebResponse(200, e.getValue(), Map.of());
                }
            }
            return new WebResponse(404, "", Map.of());
        }
    }
}
