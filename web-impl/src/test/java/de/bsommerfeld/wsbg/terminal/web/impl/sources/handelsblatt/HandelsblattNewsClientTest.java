package de.bsommerfeld.wsbg.terminal.web.impl.sources.handelsblatt;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport.FakeWebFetcher;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser and fan tests against live-captured Handelsblatt / WirtschaftsWoche
 * fixtures (probed 2026-08-02). Ported from the module world; the article
 * reader and section feeds did not migrate, the archive/window leg returned
 * as {@code ArchiveSource}.
 */
class HandelsblattNewsClientTest {

    private static String fixture(String name) {
        return SourceTestSupport.fixture("sources/handelsblatt/" + name);
    }

    /** Every migrated surface of both mastheads, served from fixtures. */
    private static FakeWebFetcher fullFetcher() {
        return new FakeWebFetcher()
                .on("wiwo.de/api/ticker", fixture("wiwo-ticker.json"))
                .on("handelsblatt.com/api/ticker", fixture("hb-ticker.json"))
                .on("eager/?url=%2Fthemen", fixture("hb-topic-siemens.json"))
                .on("api/search", fixture("hb-search-siemens.json"))
                .on("sitemaps_topics.xml", fixture("hb-topics-sitemap.xml"));
    }

    private static ResolvedInstrument named(String name) {
        return ResolvedInstrument.ofName(name);
    }

    // ------------------------------------------------------------ ticker

    @Test
    void parsesTickerTeasersWithIdLinkAndTeaser() {
        List<Article> items = HandelsblattNewsClient.parseTicker(
                fixture("hb-ticker.json"), HandelsblattBrand.HANDELSBLATT);

        assertEquals(6, items.size());
        Article first = items.get(0);
        assertEquals("100244536", first.uuid(), "the CMS id is the identity");
        assertTrue(first.title().startsWith("AstraZeneca"));
        assertEquals("Handelsblatt", first.publisher());
        assertEquals("https://www.handelsblatt.com/unternehmen/industrie/pharma-astrazeneca"
                + "-erwaegt-mega-fusion-mit-us-rivalen-bristol-myers-squibb/100244536.html",
                first.link());
        assertEquals(Instant.parse("2026-08-02T19:26:43.706Z"), first.publishedAt());
        assertNotNull(first.summary(), "kicker + lead text carry the teaser");
        assertTrue(first.summary().startsWith("Pharma - "), "kicker prefixes the lead");
        assertTrue(first.relatedTickers().isEmpty(), "teasers tag no tickers");
        assertNull(first.isin(), "teasers tag no ISIN - only article bodies do");
        assertFalse(first.sponsored());
        assertEquals("https://images.handelsblatt.com/gsIIbPNOkyEi/cover/900/506/0/0/0/0/"
                + "0.5/0.5/astrazeneca.jpeg", first.imageUrl(),
                "the teaser's own image url is a decoy, the CDN url is rebuilt (verified 200)");
    }

    @Test
    void gatedTickerItemsKeepTheHeadlineUnderThePlusByline() {
        List<Article> hb = HandelsblattNewsClient.parseTicker(
                fixture("hb-ticker.json"), HandelsblattBrand.HANDELSBLATT);
        List<Article> wiwo = HandelsblattNewsClient.parseTicker(
                fixture("wiwo-ticker.json"), HandelsblattBrand.WIWO);

        List<Article> hbGated = hb.stream()
                .filter(it -> "Handelsblatt Plus".equals(it.publisher())).toList();
        List<Article> wiwoGated = wiwo.stream()
                .filter(it -> "WirtschaftsWoche Plus".equals(it.publisher())).toList();

        assertEquals(3, hbGated.size(), "fixture carries three H_PLUS pieces");
        assertEquals(2, wiwoGated.size(), "fixture carries two WIWO_PLUS pieces");
        hbGated.forEach(it -> assertFalse(it.title().isBlank(), "the headline survives the wall"));
        assertTrue(wiwo.stream().anyMatch(it -> "WirtschaftsWoche".equals(it.publisher())));
    }

    @Test
    void accessCategoryMapsAcrossBothSpellings() {
        assertFalse(HandelsblattNewsClient.isGated("NONE"));
        assertFalse(HandelsblattNewsClient.isGated(null), "no marker means no wall");
        assertFalse(HandelsblattNewsClient.isGated("  "));
        assertTrue(HandelsblattNewsClient.isGated("H_PLUS"));
        assertTrue(HandelsblattNewsClient.isGated("H_PLUS_PREMIUM_BUSINESS"));
        assertTrue(HandelsblattNewsClient.isGated("WIWO_PLUS"));
    }

    // ------------------------------------------------------------ search

    @Test
    void parsesSearchPage() {
        String json = fixture("hb-search-siemens.json");
        List<Article> items =
                HandelsblattNewsClient.parseTeaserPage(json, HandelsblattBrand.HANDELSBLATT);

        assertFalse(items.isEmpty());
        items.forEach(it -> {
            assertTrue(it.link().startsWith("https://www.handelsblatt.com/"));
            assertNotNull(it.publishedAt());
        });
        assertTrue(items.stream().anyMatch(it -> it.title().startsWith("Siemens Healthineers")));
    }

    @Test
    void podcastTeasersAreDropped() {
        String json = fixture("hb-search-siemens.json");
        assertTrue(json.contains("podcastTeaser"), "fixture must carry a podcast entry");

        List<Article> items =
                HandelsblattNewsClient.parseTeaserPage(json, HandelsblattBrand.HANDELSBLATT);

        assertEquals(4, items.size(), "two podcast teasers of six drop out");
        assertTrue(items.stream().noneMatch(it -> it.link().startsWith(
                "https://www.handelsblatt.com/audio/")), "a player carries no article text");
    }

    // ------------------------------------------------------ topic pages

    @Test
    void parsesTopicPageWithTheSameTeaserShape() {
        String json = fixture("hb-topic-siemens.json");
        List<Article> items =
                HandelsblattNewsClient.parseTeaserPage(json, HandelsblattBrand.HANDELSBLATT);

        assertEquals(5, items.size());
        assertEquals("Handelsblatt Plus", items.get(0).publisher());
        assertTrue(items.get(0).title().startsWith("Siemens Healthineers"));
    }

    @Test
    void parsesTopicSitemapIntoSlugs() {
        Set<String> slugs = HandelsblattNewsClient.parseTopicSitemap(
                fixture("hb-topics-sitemap.xml"));

        assertTrue(slugs.contains("siemens"));
        assertTrue(slugs.contains("siemens-energy"));
        assertTrue(slugs.contains("barack-obama"), "topics are companies AND people");
        assertTrue(HandelsblattNewsClient.parseTopicSitemap("<urlset/>").isEmpty());
        assertTrue(HandelsblattNewsClient.parseTopicSitemap("garbage").isEmpty());
    }

    // --------------------------------------------------------------- fan

    @Test
    void nameFanMergesPoolTopicAndSearchWithoutDuplicates() throws Exception {
        HandelsblattNewsClient client = new HandelsblattNewsClient(fullFetcher());

        List<Article> hits = client.newsFor(named("Siemens AG"), 50);

        assertFalse(hits.isEmpty());
        assertEquals(hits.size(), hits.stream().map(Article::link).distinct().count(),
                "merge must dedupe by link");
        assertTrue(hits.stream().anyMatch(it -> it.title().startsWith("Siemens Healthineers")));
        Instant previous = null;
        for (Article it : hits) {
            if (previous != null && it.publishedAt() != null) {
                assertFalse(it.publishedAt().isAfter(previous), "not newest-first");
            }
            if (it.publishedAt() != null) previous = it.publishedAt();
        }
    }

    @Test
    void curatedTopicHitsSurviveWithoutATitleMatch() throws Exception {
        HandelsblattNewsClient client = new HandelsblattNewsClient(fullFetcher());

        List<Article> hits = client.newsFor(named("Siemens"), 50);

        assertTrue(hits.stream().anyMatch(it -> it.title().startsWith("EU startet Milliarden")),
                "the newsroom assigned it to the Siemens topic - that beats a title match");
    }

    @Test
    void searchHitsAreCutAgainstTheTitlePrecisionFilter() throws Exception {
        FakeWebFetcher fetcher = new FakeWebFetcher()
                .on("api/search", fixture("hb-search-siemens.json"));
        HandelsblattNewsClient client = new HandelsblattNewsClient(fetcher);

        List<Article> hits = client.newsFor(named("Siemens"), 50);

        assertFalse(hits.isEmpty());
        hits.forEach(it -> assertTrue(
                (it.title() + " " + it.summary()).toLowerCase().contains("siemens"),
                "unrelated search hit leaked through: " + it.title()));
    }

    @Test
    void topicCatalogueIsCachedAcrossQueries() throws Exception {
        FakeWebFetcher fetcher = fullFetcher();
        HandelsblattNewsClient client = new HandelsblattNewsClient(fetcher);

        client.newsFor(named("Siemens"), 5);
        client.newsFor(named("Porsche"), 5);

        assertEquals(2, fetcher.count("sitemaps_topics.xml"),
                "one topic-sitemap fetch per masthead, memoised across queries");
    }

    @Test
    void emptyNameNeverReachesTheNetwork() {
        FakeWebFetcher fetcher = fullFetcher();
        HandelsblattNewsClient client = new HandelsblattNewsClient(fetcher);

        assertTrue(client.newsFor(named("  "), 5).isEmpty());
        assertTrue(client.newsFor(named("AG"), 5).isEmpty());
        assertEquals(0, fetcher.total());
    }

    // ------------------------------------------------------------ archive

    @Test
    void archiveWindowBehindTheTwelveMonthHorizonCostsNoFetch() throws Exception {
        FakeWebFetcher fetcher = fullFetcher();
        HandelsblattNewsClient client = new HandelsblattNewsClient(fetcher);

        List<Article> hits = client.newsForWindow(ResolvedInstrument.ofName("Siemens"),
                LocalDate.parse("2019-01-01"), LocalDate.parse("2019-06-01"), 20);

        assertTrue(hits.isEmpty(), "the search index is a rolling 12-month window");
        assertEquals(0, fetcher.total(), "and no pointless fetch is spent on it");
    }

    @Test
    void archiveWindowKeepsOnlyItemsInsideTheWindow() throws Exception {
        HandelsblattNewsClient client = new HandelsblattNewsClient(fullFetcher());
        LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(120);
        LocalDate to = LocalDate.now(ZoneOffset.UTC).plusDays(1);

        List<Article> hits = client.newsForWindow(ResolvedInstrument.ofName("Siemens"),
                from, to, 50);

        Instant lower = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant upper = to.atStartOfDay(ZoneOffset.UTC).toInstant();
        assertFalse(hits.isEmpty());
        hits.forEach(it -> {
            assertNotNull(it.publishedAt());
            assertFalse(it.publishedAt().isBefore(lower));
            assertTrue(it.publishedAt().isBefore(upper));
        });
    }

    @Test
    void archiveWindowRejectsMissingOrInvertedBounds() throws Exception {
        FakeWebFetcher fetcher = new FakeWebFetcher();
        HandelsblattNewsClient client = new HandelsblattNewsClient(fetcher);
        assertTrue(client.newsForWindow(ResolvedInstrument.ofName("Siemens"),
                null, LocalDate.parse("2026-08-01"), 10).isEmpty());
        assertTrue(client.newsForWindow(ResolvedInstrument.ofName("Siemens"),
                LocalDate.parse("2026-07-01"), null, 10).isEmpty());
        assertTrue(client.newsForWindow(ResolvedInstrument.ofName("Siemens"),
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-07-01"), 10).isEmpty());
        assertEquals(0, fetcher.total(), "a broken window costs no fetch");
    }

    // ------------------------------------------------------------ helpers

    @Test
    void significantWordsAndSlugDropLegalForms() {
        assertEquals(Set.of("siemens", "energy"),
                HandelsblattNewsClient.significantWords("Siemens Energy AG"));
        assertTrue(HandelsblattNewsClient.significantWords("AG SE Holding").isEmpty());
        assertEquals("siemens-energy", HandelsblattNewsClient.slugify("Siemens Energy AG"));
        assertEquals("muenchener-rueck", HandelsblattNewsClient.slugify("Münchener Rück"));
        assertEquals("", HandelsblattNewsClient.slugify("AG"));
    }

    @Test
    void titleMatchNeedsAWholeWord() {
        Set<String> words = HandelsblattNewsClient.significantWords("Rheinmetall");
        assertTrue(HandelsblattNewsClient.titleMatches("Rheinmetall hebt Prognose", words));
        assertFalse(HandelsblattNewsClient.titleMatches("Der Rheinpegel steigt", words),
                "stem overlap must not count as a hit");
    }

    @Test
    void garbagePayloadsYieldEmptyListsNotExceptions() {
        assertTrue(HandelsblattNewsClient.parseTicker("{\"nope\":1}",
                HandelsblattBrand.HANDELSBLATT).isEmpty());
        assertTrue(HandelsblattNewsClient.parseTicker("<html>error</html>",
                HandelsblattBrand.HANDELSBLATT).isEmpty());
        assertTrue(HandelsblattNewsClient.parseTicker("", HandelsblattBrand.WIWO).isEmpty());
        assertTrue(HandelsblattNewsClient.parseTicker(null, HandelsblattBrand.WIWO).isEmpty());
        assertTrue(HandelsblattNewsClient.parseTeaserPage("{\"teasers\":\"nope\"}",
                HandelsblattBrand.HANDELSBLATT).isEmpty());
        assertTrue(HandelsblattNewsClient.parseTeaserPage("not json",
                HandelsblattBrand.HANDELSBLATT).isEmpty());
        assertNull(HandelsblattNewsClient.parseIsoInstant("nonsense"));
    }

    @Test
    void sourceNameAndOriginAreStable() {
        HandelsblattNewsClient client = new HandelsblattNewsClient(new FakeWebFetcher());
        assertEquals("handelsblatt", client.sourceName());
        assertEquals("DE", client.origin().sphere());
    }
}
