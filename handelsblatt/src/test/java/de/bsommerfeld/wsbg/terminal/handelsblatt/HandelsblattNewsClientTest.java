package de.bsommerfeld.wsbg.terminal.handelsblatt;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Parser and fan tests against live-captured Handelsblatt / WirtschaftsWoche
 * fixtures (probed 2026-08-02).
 */
class HandelsblattNewsClientTest {

    private static final String FREE_ARTICLE =
            "https://www.handelsblatt.com/politik/international/"
                    + "iran-krieg-trump-angriffe-auf-iran-abgesagt/100244481.html";
    private static final String METERED_ARTICLE =
            "https://www.handelsblatt.com/unternehmen/industrie/pharma-astrazeneca-erwaegt"
                    + "-mega-fusion-mit-us-rivalen-bristol-myers-squibb/100244536.html";
    private static final String PREMIUM_ARTICLE =
            "https://www.handelsblatt.com/technik/it-internet/kuenstliche-intelligenz-wie-metas"
                    + "-schulden-in-deutschen-sparpolicen-landen-koennten/100243249.html";

    private static String fixture(String name) throws IOException {
        try (InputStream in = HandelsblattNewsClientTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------ ticker

    @Test
    void parsesTickerTeasersWithIdLinkAndTeaser() throws Exception {
        List<RawNewsItem> items = HandelsblattNewsClient.parseTicker(
                fixture("hb-ticker.json"), HandelsblattBrand.HANDELSBLATT);

        assertEquals(6, items.size());
        RawNewsItem first = items.get(0);
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
    void gatedTickerItemsKeepTheHeadlineUnderThePlusByline() throws Exception {
        List<RawNewsItem> hb = HandelsblattNewsClient.parseTicker(
                fixture("hb-ticker.json"), HandelsblattBrand.HANDELSBLATT);
        List<RawNewsItem> wiwo = HandelsblattNewsClient.parseTicker(
                fixture("wiwo-ticker.json"), HandelsblattBrand.WIWO);

        List<RawNewsItem> hbGated = hb.stream()
                .filter(it -> "Handelsblatt Plus".equals(it.publisher())).toList();
        List<RawNewsItem> wiwoGated = wiwo.stream()
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
    void parsesSearchPageAndItsPagination() throws Exception {
        String json = fixture("hb-search-siemens.json");
        List<RawNewsItem> items =
                HandelsblattNewsClient.parseTeaserPage(json, HandelsblattBrand.HANDELSBLATT);

        assertEquals(37, HandelsblattNewsClient.totalPages(json), "590 hits / 16 per page");
        assertFalse(items.isEmpty());
        items.forEach(it -> {
            assertTrue(it.link().startsWith("https://www.handelsblatt.com/"));
            assertNotNull(it.publishedAt());
        });
        assertTrue(items.stream().anyMatch(it -> it.title().startsWith("Siemens Healthineers")));
    }

    @Test
    void podcastTeasersAreDropped() throws Exception {
        String json = fixture("hb-search-siemens.json");
        assertTrue(json.contains("podcastTeaser"), "fixture must carry a podcast entry");

        List<RawNewsItem> items =
                HandelsblattNewsClient.parseTeaserPage(json, HandelsblattBrand.HANDELSBLATT);

        assertEquals(4, items.size(), "two podcast teasers of six drop out");
        assertTrue(items.stream().noneMatch(it -> it.link().startsWith(
                "https://www.handelsblatt.com/audio/")), "a player carries no article text");
    }

    // ------------------------------------------------------ topic pages

    @Test
    void parsesTopicPageWithTheSameTeaserShape() throws Exception {
        String json = fixture("hb-topic-siemens.json");
        List<RawNewsItem> items =
                HandelsblattNewsClient.parseTeaserPage(json, HandelsblattBrand.HANDELSBLATT);

        assertEquals(6, HandelsblattNewsClient.totalPages(json), "~300 pieces / 15 months");
        assertEquals(5, items.size());
        assertEquals("Handelsblatt Plus", items.get(0).publisher());
        assertTrue(items.get(0).title().startsWith("Siemens Healthineers"));
    }

    @Test
    void parsesTopicSitemapIntoSlugs() throws Exception {
        Set<String> slugs = HandelsblattNewsClient.parseTopicSitemap(
                fixture("hb-topics-sitemap.xml"));

        assertTrue(slugs.contains("siemens"));
        assertTrue(slugs.contains("siemens-energy"));
        assertTrue(slugs.contains("barack-obama"), "topics are companies AND people");
        assertTrue(HandelsblattNewsClient.parseTopicSitemap("<urlset/>").isEmpty());
        assertTrue(HandelsblattNewsClient.parseTopicSitemap("garbage").isEmpty());
    }

    // -------------------------------------------------------------- feeds

    @Test
    void parsesSectionFeedIncludingTheAccessCategory() throws Exception {
        List<RawNewsItem> items = HandelsblattNewsClient.parseFeed(
                fixture("hb-feed-finanzen.xml"), HandelsblattBrand.HANDELSBLATT);

        assertEquals(4, items.size());
        assertTrue(items.stream().anyMatch(it -> "Handelsblatt".equals(it.publisher())),
                "the feed carries accessCategory NONE items");
        assertTrue(items.stream().anyMatch(it -> "Handelsblatt Plus".equals(it.publisher())),
                "media:category label=accessCategory marks the walled ones");
        RawNewsItem any = items.get(0);
        assertNotNull(any.publishedAt());
        assertNotNull(any.summary());
        assertTrue(any.link().startsWith("https://www.handelsblatt.com/"));
        assertNotNull(any.imageUrl(), "the enclosure carries the image");
    }

    @Test
    void wiwoFeedMapsItsOwnWallMarker() throws Exception {
        List<RawNewsItem> items = HandelsblattNewsClient.parseFeed(
                fixture("wiwo-feed-finanzen.xml"), HandelsblattBrand.WIWO);

        assertFalse(items.isEmpty());
        assertTrue(items.stream().anyMatch(it -> "WirtschaftsWoche Plus".equals(it.publisher())));
        assertTrue(items.stream().allMatch(it -> it.link().contains("wiwo.de")));
    }

    // ------------------------------------------------------------ article

    @Test
    void freeArticleIsReadInFull() throws Exception {
        FakeFetcher fetcher = articleFetcher();
        HandelsblattNewsClient client = new HandelsblattNewsClient(fetcher);

        HandelsblattArticle article = client.article(FREE_ARTICLE);

        assertNotNull(article);
        assertEquals("FREE", article.classification());
        assertEquals("NONE", article.accessCategory());
        assertTrue(article.hasBody());
        assertTrue(article.text().contains("Truth Social"));
        assertFalse(article.text().contains("<p>"), "the body is plain text");
        assertEquals(1, fetcher.count("lazy/story"), "exactly one body fetch");
    }

    @Test
    void meteredArticleNeverYieldsABody() throws Exception {
        FakeFetcher fetcher = articleFetcher();
        HandelsblattNewsClient client = new HandelsblattNewsClient(fetcher);

        HandelsblattArticle article = client.article(METERED_ARTICLE);

        assertNotNull(article);
        assertEquals("METERED", article.classification());
        assertEquals("NONE", article.accessCategory(),
                "a teaser marked NONE can still be classified METERED");
        assertFalse(article.hasBody(), "METERED is treated exactly like PREMIUM - house rule");
        assertNull(article.text());
        assertEquals(0, fetcher.count("lazy/story"),
                "no body fetch at all - the client-side meter is not walked around");
        assertNotNull(article.headline());
        assertNotNull(article.leadText());
    }

    @Test
    void premiumArticleNeverYieldsABody() throws Exception {
        FakeFetcher fetcher = articleFetcher();
        HandelsblattArticle article = new HandelsblattNewsClient(fetcher).article(PREMIUM_ARTICLE);

        assertNotNull(article);
        assertEquals("PREMIUM", article.classification());
        assertEquals("H_PLUS", article.accessCategory());
        assertFalse(article.hasBody());
        assertEquals(0, fetcher.count("lazy/story"));
    }

    @Test
    void editorialIsinLinksAreLiftedAndInternalIdsDropped() throws Exception {
        String metered = fixture("hb-article-metered.json");
        assertTrue(metered.contains("/boerse/isin/S11435"), "fixture carries an internal id");

        List<String> isins = HandelsblattNewsClient.extractIsins(metered);

        assertEquals(List.of("GB0009895292"), isins,
                "only ISIN-shaped tags survive; S11435 is an internal entity id");
        assertTrue(HandelsblattNewsClient.extractIsins("<p>no links here</p>").isEmpty());
        assertTrue(HandelsblattNewsClient.extractIsins(null).isEmpty());
    }

    @Test
    void isinsSurviveEvenWhenTheBodyStaysShut() throws Exception {
        HandelsblattArticle article =
                new HandelsblattNewsClient(articleFetcher()).article(METERED_ARTICLE);

        assertNotNull(article);
        assertFalse(article.hasBody());
        assertEquals(List.of("GB0009895292"), article.isins(),
                "the editorial instrument tag is metadata, not body text");
    }

    // --------------------------------------------------------------- fans

    @Test
    void symbolAndIsinFansStaySilent() {
        HandelsblattNewsClient client = new HandelsblattNewsClient(new FakeFetcher(Map.of()));
        assertTrue(client.newsFor("SIE.DE", 5).isEmpty());
        assertTrue(client.newsForIsin("DE0007236101", 5).isEmpty());
    }

    @Test
    void nameFanMergesPoolTopicAndSearchWithoutDuplicates() throws Exception {
        HandelsblattNewsClient client = new HandelsblattNewsClient(fullFetcher());

        List<RawNewsItem> hits = client.newsForName("Siemens AG", 50);

        assertFalse(hits.isEmpty());
        assertEquals(hits.size(), hits.stream().map(RawNewsItem::link).distinct().count(),
                "merge must dedupe by link");
        assertTrue(hits.stream().anyMatch(it -> it.title().startsWith("Siemens Healthineers")));
        Instant previous = null;
        for (RawNewsItem it : hits) {
            if (previous != null && it.publishedAt() != null) {
                assertFalse(it.publishedAt().isAfter(previous), "not newest-first");
            }
            if (it.publishedAt() != null) previous = it.publishedAt();
        }
    }

    @Test
    void curatedTopicHitsSurviveWithoutATitleMatch() throws Exception {
        HandelsblattNewsClient client = new HandelsblattNewsClient(fullFetcher());

        List<RawNewsItem> hits = client.newsForName("Siemens", 50);

        assertTrue(hits.stream().anyMatch(it -> it.title().startsWith("EU startet Milliarden")),
                "the newsroom assigned it to the Siemens topic - that beats a title match");
    }

    @Test
    void searchHitsAreCutAgainstTheTitlePrecisionFilter() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(ordered(
                "api/search", fixture("hb-search-siemens.json")));
        HandelsblattNewsClient client = new HandelsblattNewsClient(fetcher);

        List<RawNewsItem> hits = client.newsForName("Siemens", 50);

        assertFalse(hits.isEmpty());
        hits.forEach(it -> assertTrue(
                (it.title() + " " + it.summary()).toLowerCase().contains("siemens"),
                "unrelated search hit leaked through: " + it.title()));
    }

    @Test
    void poolIsFetchedOncePerTtlAndCoversBothMastheads() throws Exception {
        FakeFetcher fetcher = fullFetcher();
        HandelsblattNewsClient client = new HandelsblattNewsClient(fetcher);

        client.newsForName("Siemens", 5);
        int afterFirst = fetcher.count("api/ticker");
        client.newsForName("Porsche", 5);

        assertEquals(2, afterFirst, "one ticker fetch per masthead");
        assertEquals(afterFirst, fetcher.count("api/ticker"), "second call reuses the pool");
        assertEquals(2, fetcher.count("sitemaps_topics.xml"), "topic catalogue is cached too");
    }

    @Test
    void outageKeepsThePreviousPool() throws Exception {
        FakeFetcher fetcher = fullFetcher();
        HandelsblattNewsClient client = new HandelsblattNewsClient(fetcher);
        assertFalse(client.latest(10).isEmpty());

        fetcher.failAll = true;
        assertFalse(client.latest(10).isEmpty(), "the cached pool answers through an outage");
    }

    // ------------------------------------------------------------ archive

    @Test
    void archiveWindowBehindTheTwelveMonthHorizonCostsNoFetch() throws Exception {
        FakeFetcher fetcher = fullFetcher();
        HandelsblattNewsClient client = new HandelsblattNewsClient(fetcher);

        List<RawNewsItem> hits =
                client.newsForNameWindow("Siemens", null, "2019-01-01", "2019-06-01", 20);

        assertTrue(hits.isEmpty(), "the search index is a rolling 12-month window");
        assertEquals(0, fetcher.calls(), "and no pointless fetch is spent on it");
    }

    @Test
    void archiveWindowKeepsOnlyItemsInsideTheWindow() throws Exception {
        HandelsblattNewsClient client = new HandelsblattNewsClient(fullFetcher());
        String from = LocalDate.now(ZoneOffset.UTC).minusDays(120).toString();
        String to = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();

        List<RawNewsItem> hits = client.newsForNameWindow("Siemens", null, from, to, 50);

        Instant lower = Instant.parse(from + "T00:00:00Z");
        Instant upper = Instant.parse(to + "T00:00:00Z");
        assertFalse(hits.isEmpty());
        hits.forEach(it -> {
            assertNotNull(it.publishedAt());
            assertFalse(it.publishedAt().isBefore(lower));
            assertTrue(it.publishedAt().isBefore(upper));
        });
    }

    @Test
    void archiveWindowRejectsUnparseableOrInvertedBounds() {
        HandelsblattNewsClient client = new HandelsblattNewsClient(new FakeFetcher(Map.of()));
        assertTrue(client.newsForNameWindow("Siemens", null, "nope", "2026-08-01", 10).isEmpty());
        assertTrue(client.newsForNameWindow("Siemens", null, "2026-07-01", null, 10).isEmpty());
        assertTrue(client.newsForNameWindow("Siemens", null, "2026-08-01", "2026-07-01", 10)
                .isEmpty());
    }

    // -------------------------------------------- general (instrument-free)

    @Test
    void latestServesThePooledTickerOfBothMastheadsNewestFirst() throws Exception {
        FakeFetcher fetcher = fullFetcher();
        HandelsblattNewsClient client = new HandelsblattNewsClient(fetcher);

        List<RawNewsItem> latest = client.latest(10);

        assertEquals(10, latest.size(), "6 Handelsblatt + 4 WirtschaftsWoche items");
        assertTrue(latest.stream().anyMatch(it -> it.link().contains("wiwo.de")));
        assertTrue(latest.stream().anyMatch(it -> it.link().contains("handelsblatt.com")));
        Instant previous = null;
        for (RawNewsItem it : latest) {
            if (previous != null) assertFalse(it.publishedAt().isAfter(previous));
            previous = it.publishedAt();
        }
    }

    @Test
    void latestCostsNoExtraFetchBeyondThePool() throws Exception {
        FakeFetcher fetcher = fullFetcher();
        HandelsblattNewsClient client = new HandelsblattNewsClient(fetcher);

        client.newsForName("Siemens", 5);
        int afterName = fetcher.count("api/ticker");
        client.latest(20);

        assertEquals(afterName, fetcher.count("api/ticker"), "general stream rides the pool");
    }

    @Test
    void sectionReachesAnyFeedOfEitherMasthead() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(ordered(
                "feeds.cms.wiwo.de", fixture("wiwo-feed-finanzen.xml"),
                "feeds.cms.handelsblatt.com", fixture("hb-feed-finanzen.xml")));
        HandelsblattNewsClient client = new HandelsblattNewsClient(fetcher);

        assertFalse(client.section("handelsblatt/dpa", 5).isEmpty());
        assertEquals(1, fetcher.count("feeds.cms.handelsblatt.com/dpa"));
        assertFalse(client.section("wiwo/erfolg", 5).isEmpty());
        assertEquals(1, fetcher.count("feeds.cms.wiwo.de/rss/erfolg"));
        // A section nobody wired must still be reachable without editing the class.
        assertFalse(client.section("handelsblatt/immobilien", 5).isEmpty());
    }

    @Test
    void generalAccessorsRejectEmptyInput() {
        HandelsblattNewsClient client = new HandelsblattNewsClient(new FakeFetcher(Map.of()));
        assertTrue(client.latest(0).isEmpty());
        assertTrue(client.section(null, 5).isEmpty());
        assertTrue(client.section("handelsblatt", 5).isEmpty(), "a key needs brand/section");
        assertTrue(client.section("spiegel/wirtschaft", 5).isEmpty(), "unknown masthead");
        assertTrue(client.topicTeasers(null, "siemens", 1, 5).isEmpty());
        assertTrue(client.topicTeasers(HandelsblattBrand.WIWO, " ", 1, 5).isEmpty());
        assertTrue(client.search(HandelsblattBrand.HANDELSBLATT, "", 1, 5).isEmpty());
        assertTrue(client.topicSlugs(null).isEmpty());
        assertNull(client.article(" "));
        assertEquals(14, client.feeds().size());
        assertEquals(2, client.brands().size());
    }

    @Test
    void topicAccessorsAreLiveAndPublic() throws Exception {
        FakeFetcher fetcher = fullFetcher();
        HandelsblattNewsClient client = new HandelsblattNewsClient(fetcher);

        Set<String> slugs = client.topicSlugs(HandelsblattBrand.HANDELSBLATT);
        assertTrue(slugs.contains("siemens"));

        List<RawNewsItem> page2 =
                client.topicTeasers(HandelsblattBrand.HANDELSBLATT, "siemens", 2, 50);

        assertFalse(page2.isEmpty());
        assertTrue(fetcher.lastCallContaining("eager").contains("%2Fthemen%2Fsiemens%3Fpage%3D2"),
                "page 2 is asked for as an encoded query on the topic path");
    }

    @Test
    void relevanceSearchIsNotTitleFiltered() throws Exception {
        HandelsblattNewsClient client = new HandelsblattNewsClient(new FakeFetcher(ordered(
                "api/search", fixture("hb-search-siemens.json"))));

        List<RawNewsItem> hits =
                client.search(HandelsblattBrand.HANDELSBLATT, "Zoelle", 1, 20);

        assertEquals(4, hits.size(), "a theme query must not be cut against a company name");
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
    void pathAndBrandAreDerivedFromTheLink() {
        assertEquals(HandelsblattBrand.WIWO,
                HandelsblattNewsClient.brandOf("https://www.wiwo.de/finanzen/x/1.html"));
        assertEquals(HandelsblattBrand.HANDELSBLATT,
                HandelsblattNewsClient.brandOf("https://www.handelsblatt.com/x/1.html"));
        assertEquals("/finanzen/x/1.html",
                HandelsblattNewsClient.pathOf("https://www.wiwo.de/finanzen/x/1.html"));
        assertEquals("/finanzen/x/1.html",
                HandelsblattNewsClient.pathOf("finanzen/x/1.html"));
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
        assertTrue(HandelsblattNewsClient.parseFeed("<rss><channel><item></item>",
                HandelsblattBrand.HANDELSBLATT).isEmpty());
        assertTrue(HandelsblattNewsClient.parseFeed(null,
                HandelsblattBrand.HANDELSBLATT).isEmpty());
        assertEquals(0, HandelsblattNewsClient.totalPages("nonsense"));
        assertNull(HandelsblattNewsClient.parseIsoInstant("nonsense"));
        assertNull(HandelsblattNewsClient.parseRssDate("nonsense"));
        assertNull(HandelsblattNewsClient.startOfDay("nonsense"));
    }

    // ------------------------------------------------------------ fixtures

    private FakeFetcher articleFetcher() throws IOException {
        return new FakeFetcher(ordered(
                "lazy/story", fixture("hb-lazy-story.json"),
                "eager/?url=%2Fpolitik", fixture("hb-article-free.json"),
                "eager/?url=%2Funternehmen", fixture("hb-article-metered.json"),
                "eager/?url=%2Ftechnik", fixture("hb-article-premium.json")));
    }

    /** Every surface of both mastheads, served from fixtures. */
    private FakeFetcher fullFetcher() throws IOException {
        return new FakeFetcher(ordered(
                "wiwo.de/api/ticker", fixture("wiwo-ticker.json"),
                "handelsblatt.com/api/ticker", fixture("hb-ticker.json"),
                "eager/?url=%2Fthemen", fixture("hb-topic-siemens.json"),
                "api/search", fixture("hb-search-siemens.json"),
                "sitemaps_topics.xml", fixture("hb-topics-sitemap.xml"),
                "feeds.cms", fixture("hb-feed-finanzen.xml")));
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    /** Serves fixtures by URL fragment (first match wins) and counts calls. */
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

        int calls() {
            return calls.size();
        }

        String lastCallContaining(String fragment) {
            return calls.stream().filter(u -> u.contains(fragment))
                    .reduce((a, b) -> b).orElse("");
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
