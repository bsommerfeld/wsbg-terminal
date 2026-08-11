package de.bsommerfeld.wsbg.terminal.web.impl.sources.kapitalmarktexperten;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport.FakeWebFetcher;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.kapitalmarktexperten.KapitalmarktexpertenClient.Term;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser, routing and fan tests against fixtures captured live from
 * kapitalmarktexperten.de on 2026-08-02 (bodies trimmed, nothing else changed).
 * Ported from the module world; the general-stream legs did not migrate, the
 * archive-window leg returned as {@code ArchiveSource}.
 */
class KapitalmarktexpertenClientTest {

    private static final String SEARCH = "kapitalmarktexperten-search.json";
    private static final String TAG_POSTS = "kapitalmarktexperten-tag-posts.json";
    private static final String LATEST = "kapitalmarktexperten-latest.json";
    private static final String TAGS = "kapitalmarktexperten-tags.json";

    private static String fixture(String name) {
        return SourceTestSupport.fixture("sources/kapitalmarktexperten/" + name);
    }

    /** The routing table every fan test uses: most specific fragment first. */
    private static FakeWebFetcher wiredFetcher() {
        return new FakeWebFetcher()
                .on("tags?", fixture(TAGS))
                .on("posts?tags=", fixture(TAG_POSTS))
                .on("posts?search=", fixture(SEARCH))
                .on("posts?per_page=", fixture(LATEST));
    }

    private static ResolvedInstrument named(String name) {
        return ResolvedInstrument.ofName(name);
    }

    private static ResolvedInstrument isin(String isin) {
        return new ResolvedInstrument(Isin.parse(isin), Optional.empty(), "");
    }

    // ---- post parser ----

    @Test
    void parsesPostsWithTheTopLevelIsinField() {
        List<Article> items = KapitalmarktexpertenClient.parsePosts(fixture(SEARCH));

        assertEquals(8, items.size());
        Article first = items.get(0);
        assertEquals("kapitalmarktexperten-234824", first.uuid());
        assertEquals("US68389X1054", first.isin(), "isin is a top-level post property");
        assertEquals("https://www.kapitalmarktexperten.de/oracle-aktie-sp-senkt-rating-auf-bbb/",
                first.link());
        assertEquals(Instant.parse("2026-08-02T02:12:15Z"), first.publishedAt());
        assertTrue(first.relatedTickers().isEmpty(), "the site tags no ticker symbols");
        assertFalse(first.sponsored());
    }

    @Test
    void titleEntitiesAreDecodedAndTagsRemoved() {
        List<Article> items = KapitalmarktexpertenClient.parsePosts(fixture(SEARCH));

        assertEquals("Oracle Aktie: S&P senkt Rating auf BBB-", items.get(0).title(),
                "&amp; must survive as a plain ampersand");
        items.forEach(it -> {
            assertFalse(it.title().contains("&amp;"));
            assertFalse(it.title().contains("<"));
        });
    }

    @Test
    void leadTextComesFromTheBodyWithoutHtmlAndIsCapped() {
        List<Article> items = KapitalmarktexpertenClient.parsePosts(fixture(SEARCH));

        Article first = items.get(0);
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
    void leadTextFallsBackToTheExcerptWhenNoBodyWasRequested() {
        // The slim projection (the recent firehose) carries no content field.
        List<Article> items = KapitalmarktexpertenClient.parsePosts(
                fixture(LATEST).replace("\"content\"", "\"content_removed\""));

        assertFalse(items.isEmpty());
        assertTrue(items.get(0).summary().startsWith("Drei regionale Fed-Präsidenten"));
        assertFalse(items.get(0).summary().contains("&hellip;"));
    }

    @Test
    void everyItemIsMarkedAsGeneratedSecondHandCopy() {
        List<Article> all = new java.util.ArrayList<>();
        all.addAll(KapitalmarktexpertenClient.parsePosts(fixture(SEARCH)));
        all.addAll(KapitalmarktexpertenClient.parsePosts(fixture(LATEST)));

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
    void parsesTermIndexAndSurvivesGarbage() {
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
    void tagPickNeverResolvesToAMoreSpecificCompany() {
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

    // ---- instrument fan ----

    @Test
    void symbolKeyStaysSilentAndTheSourceIsNoSentimentVenue() {
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(new FakeWebFetcher());
        assertTrue(client.newsFor(new ResolvedInstrument(
                Optional.empty(), Ticker.parse("ORCL"), ""), 5).isEmpty());
        assertFalse(client.socialSentiment(), "articles, not forum posts");
        assertEquals("kapitalmarktexperten", client.sourceName());
    }

    @Test
    void nameFanTakesThePreciseTagRouteWhenTheSiteHasATag() {
        FakeWebFetcher fetcher = wiredFetcher();
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        List<Article> hits = client.newsFor(named("Siemens Energy"), 5);

        assertEquals(5, hits.size());
        assertTrue(fetcher.lastCallContaining("posts?tags=").contains("tags=852"),
                "name → tag id → posts is the precise route");
        assertEquals(0, fetcher.count("posts?search="),
                "the broad search must not run when the tag route already filled the limit");
        hits.forEach(it -> assertEquals("DE000ENER6Y0", it.isin()));
    }

    @Test
    void nameFanFallsBackToSearchAndCutsPassingMentionsByTitle() {
        FakeWebFetcher fetcher = new FakeWebFetcher()
                .on("tags?", "[]") // Oracle has no company tag in this fixture world
                .on("posts?search=", fixture(SEARCH));

        List<Article> hits = new KapitalmarktexpertenClient(fetcher).newsFor(named("Oracle"), 20);

        assertEquals(4, hits.size(), "8 full-text hits, 4 of them actually about Oracle");
        hits.forEach(it -> assertTrue(it.title().contains("Oracle"), it.title()));
        assertTrue(fetcher.count("posts?search=") > 0);
    }

    @Test
    void tagCacheKeepsRepeatedNameQueriesToOneLookup() {
        FakeWebFetcher fetcher = wiredFetcher();
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        client.newsFor(named("Siemens Energy"), 5);
        client.newsFor(named("siemens energy"), 5);

        assertEquals(1, fetcher.count("tags?"), "the resolved tag id is memoised");
    }

    @Test
    void isinKeyAnswersExactlyFromTheRecentFirehose() {
        FakeWebFetcher fetcher = wiredFetcher();
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        List<Article> apple = client.newsFor(isin("US0378331005"), 10);

        assertEquals(1, apple.size());
        assertEquals("Apple Aktie: 7,06-Prozent-Absturz trotz Rekordgewinn", apple.get(0).title());
        assertTrue(fetcher.count("posts?per_page=") > 0, "the firehose was read");
        assertTrue(client.newsFor(isin("US0378331005"), 0).isEmpty());
    }

    @Test
    void isinAndNameLegsAreAdditive() {
        FakeWebFetcher fetcher = new FakeWebFetcher()
                .on("tags?", "[]")
                .on("posts?search=", fixture(SEARCH))
                .on("posts?per_page=", fixture(SEARCH));
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        List<Article> byTitleOnly = client.newsFor(named("Nebius"), 20);
        List<Article> withIsin = client.newsFor(new ResolvedInstrument(
                Isin.parse("US68389X1054"), Optional.empty(), "Nebius"), 20);

        assertEquals(1, byTitleOnly.size(), "the title route works on its own");
        assertEquals(5, withIsin.size(), "the exact field hits are ADDED to the title hits");
        assertTrue(withIsin.stream().anyMatch(it -> it.title().contains("Nebius")),
                "the title hit must survive the isin leg - it never subtracts");
        assertEquals(4, withIsin.stream()
                .filter(it -> "US68389X1054".equals(it.isin())
                        && !it.title().contains("Nebius")).count());
    }

    @Test
    void everyLegAnswersEmptyWhenTheSiteIsDown() throws Exception {
        FakeWebFetcher fetcher = wiredFetcher();
        fetcher.failAll = true;
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        assertTrue(client.newsFor(named("Siemens Energy"), 5).isEmpty());
        assertTrue(client.newsFor(isin("US0378331005"), 5).isEmpty());
        assertTrue(client.newsForWindow(
                new ResolvedInstrument(Isin.parse("DE000ENER6Y0"), Optional.empty(),
                        "Siemens Energy"),
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-08-01"), 5).isEmpty());
    }

    // ---- archive window ----

    @Test
    void archiveWindowSendsTheServerSideBoundsAndCutsExactlyOnDateGmt() throws Exception {
        FakeWebFetcher fetcher = wiredFetcher();
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        List<Article> hits = client.newsForWindow(
                new ResolvedInstrument(Isin.parse("DE000ENER6Y0"), Optional.empty(),
                        "Siemens Energy"),
                LocalDate.parse("2026-07-30"), LocalDate.parse("2026-08-02"), 20);

        String url = fetcher.lastCallContaining("posts?tags=");
        assertTrue(url.contains("after=2026-07-30T00:00:00"), url);
        assertTrue(url.contains("before=2026-08-02T00:00:00"), url);
        assertFalse(hits.isEmpty());
        hits.forEach(it -> {
            assertFalse(it.publishedAt().isBefore(Instant.parse("2026-07-30T00:00:00Z")));
            assertTrue(it.publishedAt().isBefore(Instant.parse("2026-08-02T00:00:00Z")));
        });
        assertEquals(5, hits.size(), "the two 08-02 and the 07-29 fixture posts fall outside");
        Instant previous = null;
        for (Article it : hits) {
            if (previous != null) assertFalse(it.publishedAt().isAfter(previous), "not newest-first");
            previous = it.publishedAt();
        }
    }

    @Test
    void archiveWindowIsinAcceptIsAdditiveNeverSubtractive() throws Exception {
        FakeWebFetcher fetcher = new FakeWebFetcher()
                .on("tags?", "[]") // no tag → search route, where the title cut applies
                .on("posts?search=", fixture(SEARCH));
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        List<Article> byTitleOnly = client.newsForWindow(named("Nebius"),
                LocalDate.parse("2026-07-29"), LocalDate.parse("2026-08-03"), 20);
        // Same name, plus an isin that four OTHER fixture posts carry in their field.
        List<Article> withIsin = client.newsForWindow(
                new ResolvedInstrument(Isin.parse("US68389X1054"), Optional.empty(), "Nebius"),
                LocalDate.parse("2026-07-29"), LocalDate.parse("2026-08-03"), 20);

        assertEquals(1, byTitleOnly.size(), "the title route works on its own");
        assertEquals(5, withIsin.size(), "the exact field hits are ADDED to the title hits");
        assertTrue(withIsin.stream().anyMatch(it -> it.title().contains("Nebius")),
                "the title hit must survive the isin sharpener - it never subtracts");
        assertEquals(4, withIsin.stream()
                .filter(it -> "US68389X1054".equals(it.isin())).count());
    }

    @Test
    void archiveWindowRejectsUnusableBounds() throws Exception {
        FakeWebFetcher fetcher = wiredFetcher();
        KapitalmarktexpertenClient client = new KapitalmarktexpertenClient(fetcher);

        assertTrue(client.newsForWindow(named("Siemens Energy"),
                LocalDate.parse("2026-08-02"), LocalDate.parse("2026-07-01"), 10)
                .isEmpty(), "an inverted window yields nothing");
        assertTrue(client.newsForWindow(named(""),
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-08-01"), 10).isEmpty());
        assertTrue(client.newsForWindow(named("Siemens Energy"),
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-08-01"), 0).isEmpty());
        assertEquals(0, fetcher.count("posts?"), "unusable bounds never fetch posts");
    }

    // ---- helpers ----

    @Test
    void stripHtmlAndDateHelpersHandleTheirEdges() {
        assertEquals("A & B", KapitalmarktexpertenClient.stripHtml("<p>A &amp;  B</p>"));
        assertEquals("Umsatz…", KapitalmarktexpertenClient.stripHtml("Umsatz&hellip;"));
        assertNull(KapitalmarktexpertenClient.stripHtml(null));
        assertEquals(Instant.parse("2026-08-02T20:12:05Z"),
                KapitalmarktexpertenClient.parseStamp("2026-08-02T20:12:05"));
        assertNull(KapitalmarktexpertenClient.parseStamp("2026-08-02"));
        assertEquals(Set.of("siemens", "energy"),
                KapitalmarktexpertenClient.significantWords("Siemens Energy AG Aktie"));
        assertTrue(KapitalmarktexpertenClient.significantWords("AG SE").isEmpty());
        assertFalse(KapitalmarktexpertenClient.titleMatches("Der Rheinpegel steigt",
                KapitalmarktexpertenClient.significantWords("Rheinmetall")));
    }
}
