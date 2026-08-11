package de.bsommerfeld.wsbg.terminal.web.impl.sources.cnbc;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser and fan tests against the live-captured queryly fixture (2026-08-02).
 * Ported from the module world; the section-RSS pool leg lives in the curated
 * feed catalog now, so only the search surface is under test here.
 */
class CnbcSearchSourceTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = CnbcSearchSourceTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- search parsing ----

    @Test
    void parsesSearchResults() throws Exception {
        List<Article> items = CnbcSearchSource.parseSearch(fixture("cnbc-search.json"));

        assertFalse(items.isEmpty());
        Article first = items.get(0);
        assertNotNull(first.title());
        assertTrue(first.link().startsWith("https://www.cnbc.com/"));
        assertNotNull(first.publishedAt());
        assertNotNull(first.summary());
    }

    @Test
    void gatedArticlesKeepHeadlineButAreMarkedAsPro() throws Exception {
        List<Article> items = CnbcSearchSource.parseSearch(fixture("cnbc-search.json"));

        List<Article> gated = items.stream()
                .filter(it -> CnbcSearchSource.PUBLISHER_GATED.equals(it.publisher()))
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
        List<Article> items = CnbcSearchSource.parseSearch(json);
        assertTrue(items.stream().noneMatch(it -> it.link().contains("/video/")),
                "video items carry no article text");
    }

    @Test
    void searchGarbageYieldsEmptyListNotException() {
        assertTrue(CnbcSearchSource.parseSearch("{\"results\":\"nope\"}").isEmpty());
        assertTrue(CnbcSearchSource.parseSearch("<html>error</html>").isEmpty());
        assertTrue(CnbcSearchSource.parseSearch("").isEmpty());
        assertTrue(CnbcSearchSource.parseSearch(null).isEmpty());
    }

    // ---- dates ----

    @Test
    void parsesTheQuerylyDateShape() {
        assertEquals(Instant.parse("2026-07-27T21:10:17Z"),
                CnbcSearchSource.parseSearchDate("2026-07-27T21:10:17+0000"),
                "queryly's offset carries no colon");
        assertNull(CnbcSearchSource.parseSearchDate("nonsense"));
        assertNull(CnbcSearchSource.parseSearchDate(null));
    }

    // ---- fans ----

    @Test
    void symbolOnlyInstrumentStaysSilent() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of());
        CnbcSearchSource source = new CnbcSearchSource(fetcher);
        assertTrue(source.newsFor(new ResolvedInstrument(
                Optional.empty(), Ticker.parse("NVDA"), ""), 5).isEmpty(),
                "CNBC tags no tickers — a bare symbol must never become a full-text query");
        assertTrue(fetcher.calls.isEmpty(), "and it costs no request");
    }

    @Test
    void nameFanIsPrecisionFilteredDedupedAndNewestFirst() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                "endindex=0", fixture("cnbc-search.json")));
        CnbcSearchSource source = new CnbcSearchSource(fetcher);

        List<Article> hits = source.newsFor(ResolvedInstrument.ofName("Nvidia"), 50);

        assertFalse(hits.isEmpty());
        assertTrue(fetcher.calls.get(0).contains("sort=date"), "the instrument fan is chronological");
        long distinctIds = hits.stream().map(Article::uuid).distinct().count();
        assertEquals(hits.size(), distinctIds, "the fan dedupes by id");
        hits.forEach(it -> assertTrue(
                (it.title() + " " + (it.summary() == null ? "" : it.summary()))
                        .toLowerCase().contains("nvidia"),
                "precision filter: " + it.title()));

        Instant previous = null;
        for (Article it : hits) {
            if (previous != null && it.publishedAt() != null) {
                assertFalse(it.publishedAt().isAfter(previous), "not newest-first");
            }
            if (it.publishedAt() != null) previous = it.publishedAt();
        }
    }

    @Test
    void nameFanPagesViaEndindexUntilAPageComesBackEmpty() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                "endindex=0", fixture("cnbc-search.json"),
                "endindex=50", "{\"results\":[]}"));
        CnbcSearchSource source = new CnbcSearchSource(fetcher);

        source.newsFor(ResolvedInstrument.ofName("Nvidia"), 500);

        assertEquals(2, fetcher.calls.size(),
                "endindex paging walks on until an empty page ends the archive");
        assertTrue(fetcher.calls.get(1).contains("endindex=50"));
    }

    @Test
    void relevanceSearchDoesNotApplyTheNameFilter() throws Exception {
        CnbcSearchSource source = new CnbcSearchSource(new FakeFetcher(Map.of(
                "queryly", fixture("cnbc-search.json"))));

        // A theme query: results must survive even though no title carries the word.
        List<Article> hits = source.search("tariffs", 20);

        assertFalse(hits.isEmpty(), "theme search must not be title-filtered");
    }

    // ---- archive window ----

    @Test
    void archiveWindowKeepsOnlyItemsInsideTheWindow() throws Exception {
        CnbcSearchSource source = new CnbcSearchSource(new FakeFetcher(Map.of(
                "queryly", fixture("cnbc-search.json"))));

        List<Article> hits = source.newsForWindow(ResolvedInstrument.ofName("Nvidia"),
                LocalDate.parse("2026-07-30"), LocalDate.parse("2026-08-01"), 20);

        assertFalse(hits.isEmpty(), "the fixture carries in-window Nvidia items");
        hits.forEach(it -> {
            assertTrue(it.publishedAt().isBefore(Instant.parse("2026-08-01T00:00:00Z")));
            assertFalse(it.publishedAt().isBefore(Instant.parse("2026-07-30T00:00:00Z")));
        });
    }

    @Test
    void archiveWindowIsChronologicalAndStopsPastTheLowerBound() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                "queryly", fixture("cnbc-search.json")));
        CnbcSearchSource source = new CnbcSearchSource(fetcher);

        // The fixture's oldest items (2026-07-30) fall below this lower bound,
        // so page one already paged past the window - no second fetch.
        source.newsForWindow(ResolvedInstrument.ofName("Nvidia"),
                LocalDate.parse("2026-07-31"), LocalDate.parse("2026-08-02"), 500);

        assertEquals(1, fetcher.calls.size(),
                "paging stops once the window's lower bound falls out of view");
        assertTrue(fetcher.calls.get(0).contains("sort=date"), "the archive walk is chronological");
    }

    @Test
    void archiveWindowRejectsMissingBounds() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of());
        CnbcSearchSource source = new CnbcSearchSource(fetcher);
        assertTrue(source.newsForWindow(ResolvedInstrument.ofName("Nvidia"),
                null, LocalDate.parse("2026-08-01"), 10).isEmpty());
        assertTrue(source.newsForWindow(ResolvedInstrument.ofName("Nvidia"),
                LocalDate.parse("2026-07-01"), null, 10).isEmpty());
        assertTrue(fetcher.calls.isEmpty(), "a broken window costs no fetch");
    }

    @Test
    void accessorsRejectEmptyInput() throws Exception {
        CnbcSearchSource source = new CnbcSearchSource(new FakeFetcher(Map.of()));
        assertTrue(source.search("", 5).isEmpty());
        assertTrue(source.search("nvidia", 0).isEmpty());
        assertTrue(source.newsFor(null, 5).isEmpty());
        assertTrue(source.newsFor(ResolvedInstrument.ofName("AG SE"), 5).isEmpty(),
                "an all-generic name yields no match words and no fetch");
    }

    /** Serves fixtures by URL fragment and counts calls. */
    private static final class FakeFetcher implements WebFetcher {
        private final Map<String, String> byFragment;
        final List<String> calls = new ArrayList<>();

        FakeFetcher(Map<String, String> byFragment) {
            this.byFragment = byFragment;
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            calls.add(url);
            for (Map.Entry<String, String> e : byFragment.entrySet()) {
                if (url.contains(e.getKey())) {
                    return WebResponse.text(200, e.getValue(), Map.of());
                }
            }
            return WebResponse.text(404, "", Map.of());
        }

        @Override
        public WebResponse fetchBinary(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
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
}
