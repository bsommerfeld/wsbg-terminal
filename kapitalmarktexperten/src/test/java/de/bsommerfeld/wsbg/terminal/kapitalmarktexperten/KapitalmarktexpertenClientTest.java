package de.bsommerfeld.wsbg.terminal.kapitalmarktexperten;

import de.bsommerfeld.wsbg.terminal.kapitalmarktexperten.KapitalmarktexpertenClient.Term;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser, routing and fan tests against fixtures captured live from
 * kapitalmarktexperten.de on 2026-08-02 (bodies trimmed, nothing else changed).
 */
class KapitalmarktexpertenClientTest {

    private static final String SEARCH = "kapitalmarktexperten-search.json";
    private static final String TAG_POSTS = "kapitalmarktexperten-tag-posts.json";
    private static final String LATEST = "kapitalmarktexperten-latest.json";
    private static final String TAGS = "kapitalmarktexperten-tags.json";
    private static final String CATEGORIES = "kapitalmarktexperten-categories.json";

    private static String fixture(String name) throws IOException {
        try (InputStream in =
                     KapitalmarktexpertenClientTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The routing table every fan test uses: most specific fragment first. */
    private static FakeFetcher wiredFetcher() throws IOException {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("tags?", fixture(TAGS));
        routes.put("categories?", fixture(CATEGORIES));
        routes.put("posts?tags=", fixture(TAG_POSTS));
        routes.put("posts?categories=", fixture(TAG_POSTS));
        routes.put("posts?search=", fixture(SEARCH));
        routes.put("posts?per_page=", fixture(LATEST));
        return new FakeFetcher(routes);
    }

    // ---- post parser ----

    @Test
    void parsesPostsWithTheTopLevelIsinField() throws Exception {
        List<RawNewsItem> items = KapitalmarktexpertenClient.parsePosts(fixture(SEARCH));

        assertEquals(8, items.size());
        RawNewsItem first = items.get(0);
        assertEquals("kapitalmarktexperten-234824", first.uuid());
        assertEquals("US68389X1054", first.isin(), "isin is a top-level post property");
        assertEquals("https://www.kapitalmarktexperten.de/oracle-aktie-sp-senkt-rating-auf-bbb/",
                first.link());
        assertEquals(Instant.parse("2026-08-02T02:12:15Z"), first.publishedAt());
        assertTrue(first.relatedTickers().isEmpty(), "the site tags no ticker symbols");
        assertFalse(first.sponsored());
    }

    @Test
    void titleEntitiesAreDecodedAndTagsRemoved() throws Exception {
        List<RawNewsItem> items = KapitalmarktexpertenClient.parsePosts(fixture(SEARCH));

        assertEquals("Oracle Aktie: S&P senkt Rating auf BBB-", items.get(0).title(),
                "&amp; must survive as a plain ampersand");
        items.forEach(it -> {
            assertFalse(it.title().contains("&amp;"));
            assertFalse(it.title().contains("<"));
        });
    }

    @Test
    void leadTextComesFromTheBodyWithoutHtmlAndIsCapped() throws Exception {
        List<RawNewsItem> items = KapitalmarktexpertenClient.parsePosts(fixture(SEARCH));

        RawNewsItem first = items.get(0);
        assertNotNull(first.summary());
        assertTrue(first.summary().startsWith("Elf Prozent plus in einer Woche"));
        assertTrue(first.summary().length() <= KapitalmarktexpertenClient.SUMMARY_MAX + 1);
        items.forEach(it -> {
            assertFalse(it.summary().contains("<p>"), "content.rendered markup must be stripped");
            assertFalse(it.summary().contains("&hellip;"), "entities must be decoded");
            assertFalse(it.summary().contains("  "), "whitespace must be collapsed");
        });
    }

    @Test
    void leadTextFallsBackToTheExcerptWhenNoBodyWasRequested() throws Exception {
        // The slim projection (pool / general streams) carries no content field.
        List<RawNewsItem> items = KapitalmarktexpertenClient.parsePosts(
                fixture(LATEST).replace("\"content\"", "\"content_removed\""));

        assertFalse(items.isEmpty());
        assertTrue(items.get(0).summary().startsWith("Drei regionale Fed-Präsidenten"));
        assertFalse(items.get(0).summary().contains("&hellip;"));
    }

    @Test
    void everyItemIsMarkedAsGeneratedSecondHandCopy() throws Exception {
        List<RawNewsItem> all = new ArrayList<>();
        all.addAll(KapitalmarktexpertenClient.parsePosts(fixture(SEARCH)));
        all.addAll(KapitalmarktexpertenClient.parsePosts(fixture(LATEST)));

        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(wiredFetcher());
        all.addAll(client.latest(10));
        all.addAll(client.byTag(852, 5));
        all.addAll(client.byCategory(10, 5));

        assertFalse(all.isEmpty());
        all.forEach(it -> assertEquals("Kapitalmarktexperten (KI-generiert)", it.publisher(),
                "the origin marking is the fact-check hook - it must never be dropped"));
    }

    @Test
    void postGarbageYieldsEmptyListNotException() {
        assertTrue(KapitalmarktexpertenClient.parsePosts("{\"code\":\"rest_no_route\"}").isEmpty());
        assertTrue(KapitalmarktexpertenClient.parsePosts("<html>nope</html>").isEmpty());
        assertTrue(KapitalmarktexpertenClient.parsePosts("[{\"id\":1}]").isEmpty(),
                "a post without title/link is dropped, not emitted half-built");
        assertTrue(KapitalmarktexpertenClient.parsePosts("").isEmpty());
        assertTrue(KapitalmarktexpertenClient.parsePosts(null).isEmpty());
    }

    // ---- taxonomy ----

    @Test
    void parsesTermIndexAndSurvivesGarbage() throws Exception {
        List<Term> tags = KapitalmarktexpertenClient.parseTerms(fixture(TAGS));

        assertEquals(3, tags.size());
        assertEquals(758, tags.get(0).id());
        assertEquals("Siemens", tags.get(0).name());
        assertEquals("siemens", tags.get(0).slug());
        assertEquals(92, tags.get(0).count());

        assertTrue(KapitalmarktexpertenClient.parseTerms("{\"code\":\"boom\"}").isEmpty());
        assertTrue(KapitalmarktexpertenClient.parseTerms("garbage").isEmpty());
        assertTrue(KapitalmarktexpertenClient.parseTerms(null).isEmpty());
    }

    @Test
    void tagPickNeverResolvesToAMoreSpecificCompany() throws Exception {
        List<Term> candidates = KapitalmarktexpertenClient.parseTerms(fixture(TAGS));

        assertEquals(852, KapitalmarktexpertenClient.pickTag(candidates, "Siemens Energy"));
        assertEquals(758, KapitalmarktexpertenClient.pickTag(candidates, "Siemens"));
        assertEquals(758, KapitalmarktexpertenClient.pickTag(candidates, "Siemens AG"),
                "'Siemens AG' must not be answered with the Siemens Energy tag");
        assertEquals(765,
                KapitalmarktexpertenClient.pickTag(candidates, "Siemens Healthineers AG"));
        assertEquals(-1, KapitalmarktexpertenClient.pickTag(candidates, "Oracle"));
        assertEquals(-1, KapitalmarktexpertenClient.pickTag(List.of(), "Siemens"));
    }

    @Test
    void tagAndCategoryIndexesAreReachable() throws Exception {
        FakeFetcher fetcher = wiredFetcher();
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        List<Term> tags = client.tags("siemens", 10);
        List<Term> categories = client.categories(10);

        assertEquals(3, tags.size());
        assertEquals(10, categories.size());
        assertEquals("Analystenstimmen", categories.get(0).name());
        assertEquals(8657, categories.get(0).count());
        assertTrue(fetcher.lastUrlContaining("tags?").contains("search=siemens"));
        assertTrue(fetcher.lastUrlContaining("categories?").contains("orderby=count"));
        assertTrue(client.tags("siemens", 0).isEmpty());
        assertTrue(client.categories(0).isEmpty());
    }

    // ---- instrument fans ----

    @Test
    void symbolFanStaysSilentAndTheSourceIsNoSentimentVenue() {
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(
                new FakeFetcher(Map.of()));
        assertTrue(client.newsFor("ORCL", 5).isEmpty());
        assertFalse(client.socialSentiment(), "articles, not forum posts");
        assertEquals("kapitalmarktexperten", client.sourceName());
    }

    @Test
    void nameFanTakesThePreciseTagRouteWhenTheSiteHasATag() throws Exception {
        FakeFetcher fetcher = wiredFetcher();
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        List<RawNewsItem> hits = client.newsForName("Siemens Energy", 5);

        assertEquals(5, hits.size());
        assertTrue(fetcher.lastUrlContaining("posts?tags=").contains("tags=852"),
                "name → tag id → posts is the precise route");
        assertEquals(0, fetcher.count("posts?search="),
                "the broad search must not run when the tag route already filled the limit");
        hits.forEach(it -> assertEquals("DE000ENER6Y0", it.isin()));
    }

    @Test
    void nameFanFallsBackToSearchAndCutsPassingMentionsByTitle() throws Exception {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("tags?", "[]"); // Oracle has no company tag in this fixture world
        routes.put("posts?search=", fixture(SEARCH));
        FakeFetcher fetcher = new FakeFetcher(routes);

        List<RawNewsItem> hits = new KapitalmarktexpertenClient(fetcher).newsForName("Oracle", 20);

        assertEquals(4, hits.size(), "8 full-text hits, 4 of them actually about Oracle");
        hits.forEach(it -> assertTrue(it.title().contains("Oracle"), it.title()));
        assertTrue(fetcher.count("posts?search=") > 0);
    }

    @Test
    void tagCacheKeepsRepeatedNameQueriesToOneLookup() throws Exception {
        FakeFetcher fetcher = wiredFetcher();
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        client.newsForName("Siemens Energy", 5);
        client.newsForName("siemens energy", 5);

        assertEquals(1, fetcher.count("tags?"), "the resolved tag id is memoised");
    }

    // ---- archive window ----

    @Test
    void archiveWindowSendsTheServerSideBoundsAndCutsExactlyOnDateGmt() throws Exception {
        FakeFetcher fetcher = wiredFetcher();
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        List<RawNewsItem> hits = client.newsForNameWindow(
                "Siemens Energy", "DE000ENER6Y0", "2026-07-30", "2026-08-02", 20);

        String url = fetcher.lastUrlContaining("posts?tags=");
        assertTrue(url.contains("after=2026-07-30T00:00:00"), url);
        assertTrue(url.contains("before=2026-08-02T00:00:00"), url);
        assertFalse(hits.isEmpty());
        hits.forEach(it -> {
            assertFalse(it.publishedAt().isBefore(Instant.parse("2026-07-30T00:00:00Z")));
            assertTrue(it.publishedAt().isBefore(Instant.parse("2026-08-02T00:00:00Z")));
        });
        assertEquals(5, hits.size(), "the two 08-02 and the 07-29 fixture posts fall outside");
        Instant previous = null;
        for (RawNewsItem it : hits) {
            if (previous != null) assertFalse(it.publishedAt().isAfter(previous), "not newest-first");
            previous = it.publishedAt();
        }
    }

    @Test
    void archiveWindowIsinAcceptIsAdditiveNeverSubtractive() throws Exception {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("tags?", "[]"); // no tag → search route, where the title cut applies
        routes.put("posts?search=", fixture(SEARCH));
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(new FakeFetcher(routes));

        List<RawNewsItem> byTitleOnly = client.newsForNameWindow(
                "Nebius", null, "2026-07-29", "2026-08-03", 20);
        // Same name, plus an isin that four OTHER fixture posts carry in their field.
        List<RawNewsItem> withIsin = client.newsForNameWindow(
                "Nebius", "US68389X1054", "2026-07-29", "2026-08-03", 20);

        assertEquals(1, byTitleOnly.size(), "the title route works on its own");
        assertEquals(5, withIsin.size(), "the exact field hits are ADDED to the title hits");
        assertTrue(withIsin.stream().anyMatch(it -> it.title().contains("Nebius")),
                "the title hit must survive the isin sharpener - it never subtracts");
        assertEquals(4, withIsin.stream()
                .filter(it -> "US68389X1054".equals(it.isin())).count());
    }

    @Test
    void archiveWindowRejectsUnusableBounds() throws Exception {
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(wiredFetcher());

        assertTrue(client.newsForNameWindow("Siemens Energy", null, "nope", "2026-08-02", 10)
                .isEmpty());
        assertTrue(client.newsForNameWindow("Siemens Energy", null, "2026-07-01", null, 10)
                .isEmpty());
        assertTrue(client.newsForNameWindow("Siemens Energy", null, "2026-08-02", "2026-07-01", 10)
                .isEmpty(), "an inverted window yields nothing");
        assertTrue(client.newsForNameWindow(null, null, "2026-07-01", "2026-08-01", 10).isEmpty());
        assertTrue(client.newsForNameWindow("Siemens Energy", null, "2026-07-01", "2026-08-01", 0)
                .isEmpty());
    }

    // ---- ISIN ----

    @Test
    void isinFanAnswersExactlyFromThePoolWithoutAnExtraFetch() throws Exception {
        FakeFetcher fetcher = wiredFetcher();
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        List<RawNewsItem> apple = client.newsForIsin("US0378331005", 10);
        int fetchesAfterFirst = fetcher.total();
        List<RawNewsItem> novo = client.newsForIsin("dk0062498333", 10);

        assertEquals(1, apple.size());
        assertEquals("Apple Aktie: 7,06-Prozent-Absturz trotz Rekordgewinn", apple.get(0).title());
        assertEquals(1, novo.size(), "the field compare is case-insensitive");
        assertEquals(fetchesAfterFirst, fetcher.total(), "the pool answers, no second fetch");
        assertTrue(client.newsForIsin("US0000000000", 10).isEmpty(), "no hit → empty, not fuzzy");
    }

    @Test
    void isinFanRejectsMalformedInputInsteadOfQueryingForIt() throws Exception {
        FakeFetcher fetcher = wiredFetcher();
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        assertTrue(client.newsForIsin("Apple", 10).isEmpty());
        assertTrue(client.newsForIsin("US037833100", 10).isEmpty());
        assertTrue(client.newsForIsin(null, 10).isEmpty());
        assertTrue(client.newsForIsin("US0378331005", 0).isEmpty());
        assertEquals(0, fetcher.total(),
                "a malformed isin must not even warm the pool (?search=<ISIN> is 0 hits anyway)");
    }

    // ---- general stream ----

    @Test
    void latestServesTheFirehoseFromASharedPoolOncePerTtl() throws Exception {
        FakeFetcher fetcher = wiredFetcher();
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        List<RawNewsItem> first = client.latest(3);
        int afterFirst = fetcher.count("posts?per_page=");
        client.latest(6);
        client.newsForIsin("US0378331005", 5);

        assertEquals(3, first.size());
        assertEquals("MSCI World ETF: Apple verliert fast 10 Prozent", first.get(0).title());
        assertEquals(1, afterFirst, "a short page ends the pool walk immediately");
        assertEquals(afterFirst, fetcher.count("posts?per_page="),
                "latest and the isin fan share one pool");
        assertTrue(client.latest(0).isEmpty());
    }

    @Test
    void poolWalksSeveralPagesUntilAShortPageEndsIt() throws Exception {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("&page=1&", fullPage());
        routes.put("posts?per_page=", fixture(LATEST));
        FakeFetcher fetcher = new FakeFetcher(routes);

        List<RawNewsItem> items = new KapitalmarktexpertenClient(fetcher).latest(500);

        assertEquals(2, fetcher.count("posts?per_page="), "page 2 was short, so page 3 is skipped");
        assertEquals(KapitalmarktexpertenClient.PAGE_SIZE + 6, items.size(),
                "both pages merged, no duplicates");
    }

    @Test
    void topicalStreamsAddressTheRightEndpoints() throws Exception {
        FakeFetcher fetcher = wiredFetcher();
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        List<RawNewsItem> byTag = client.byTag(852, 3);
        List<RawNewsItem> byCategory = client.byCategory(10, 4);

        assertEquals(3, byTag.size());
        assertEquals(4, byCategory.size());
        assertTrue(fetcher.lastUrlContaining("posts?tags=").contains("tags=852"));
        assertTrue(fetcher.lastUrlContaining("posts?categories=").contains("categories=10"));
        assertTrue(client.byTag(0, 5).isEmpty());
        assertTrue(client.byCategory(10, 0).isEmpty());
    }

    @Test
    void everyLegAnswersEmptyWhenTheSiteIsDown() throws Exception {
        FakeFetcher fetcher = wiredFetcher();
        fetcher.failAll = true;
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        assertTrue(client.latest(5).isEmpty());
        assertTrue(client.newsForName("Siemens Energy", 5).isEmpty());
        assertTrue(client.newsForNameWindow("Siemens Energy", "DE000ENER6Y0",
                "2026-07-01", "2026-08-01", 5).isEmpty());
        assertTrue(client.newsForIsin("US0378331005", 5).isEmpty());
        assertTrue(client.tags("siemens", 5).isEmpty());
        assertTrue(client.categories(5).isEmpty());
    }

    // ---- helpers ----

    /** A synthetic full-size page (100 posts) - the pool's "keep paging" signal. */
    private static String fullPage() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < KapitalmarktexpertenClient.PAGE_SIZE; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(900000 + i)
                    .append(",\"date_gmt\":\"2026-08-02T10:00:00\"")
                    .append(",\"link\":\"https://www.kapitalmarktexperten.de/p").append(i)
                    .append("/\",\"title\":{\"rendered\":\"Testwert ").append(i)
                    .append(" Aktie\"},\"isin\":\"US000000000").append(i % 10)
                    .append("\",\"excerpt\":{\"rendered\":\"<p>Kurz.</p>\"}}");
        }
        return sb.append(']').toString();
    }

    @Test
    void stripHtmlAndDateHelpersHandleTheirEdges() {
        assertEquals("A & B", KapitalmarktexpertenClient.stripHtml("<p>A &amp;  B</p>"));
        assertEquals("Umsatz…", KapitalmarktexpertenClient.stripHtml("Umsatz&hellip;"));
        assertNull(KapitalmarktexpertenClient.stripHtml(null));
        assertEquals(Instant.parse("2026-08-02T20:12:05Z"),
                KapitalmarktexpertenClient.parseStamp("2026-08-02T20:12:05"));
        assertNull(KapitalmarktexpertenClient.parseStamp("2026-08-02"));
        assertNull(KapitalmarktexpertenClient.startOfDay("nope"));
        assertEquals(Set.of("siemens", "energy"),
                KapitalmarktexpertenClient.significantWords("Siemens Energy AG Aktie"));
        assertTrue(KapitalmarktexpertenClient.significantWords("AG SE").isEmpty());
        assertFalse(KapitalmarktexpertenClient.titleMatches("Der Rheinpegel steigt",
                KapitalmarktexpertenClient.significantWords("Rheinmetall")));
    }

    /** Serves fixtures by URL fragment (first match wins) and counts calls. */
    private static final class FakeFetcher implements WebFetcher {
        private final Map<String, String> byFragment;
        private final List<String> calls = new ArrayList<>();
        boolean failAll;

        FakeFetcher(Map<String, String> byFragment) {
            this.byFragment = new LinkedHashMap<>(byFragment);
        }

        int count(String fragment) {
            return (int) calls.stream().filter(u -> u.contains(fragment)).count();
        }

        int total() {
            return calls.size();
        }

        String lastUrlContaining(String fragment) {
            return calls.stream().filter(u -> u.contains(fragment))
                    .reduce((a, b) -> b)
                    .orElseThrow(() -> new AssertionError("no call containing " + fragment
                            + " in " + calls));
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
