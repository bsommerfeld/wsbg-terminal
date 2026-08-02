package de.bsommerfeld.wsbg.terminal.tradersunion;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.tradersunion.TradersUnionMoversClientTest.FakeFetcher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static de.bsommerfeld.wsbg.terminal.tradersunion.TradersUnionMoversClientTest.fixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser and fan tests against live-captured Traders Union fixtures (2026-08-02). */
class TradersUnionNewsClientTest {

    private static TradersUnionNewsClient clientWithSitemaps() throws Exception {
        return new TradersUnionNewsClient(new FakeFetcher(Map.of(
                "news-sitemap-de", fixture("tu-news-sitemap-de.xml"),
                "sitemap_news_de", fixture("tu-news-index-de.xml"))));
    }

    // ---- sitemap parsing ----

    @Test
    void parsesGoogleNewsSitemapIntoTitledItems() throws Exception {
        List<TradersUnionNewsClient.NewsUrl> urls =
                TradersUnionNewsClient.parseSitemap(fixture("tu-news-sitemap-de.xml"));

        assertFalse(urls.isEmpty());
        TradersUnionNewsClient.NewsUrl first = urls.get(0);
        assertEquals("brokers-news", first.segment());
        assertEquals("2873107", first.articleId());
        assertEquals("OKX stellt Multi-Wallet-Handelsfunktion auf DEX vor", first.title());
        assertEquals(Instant.parse("2026-08-03T00:43:16Z"), first.lastModified());
        assertTrue(first.url().startsWith("https://tradersunion.com/de/news/"));
        urls.forEach(u -> assertNotNull(u.title(), "the gnews sitemap titles every url"));
    }

    @Test
    void parsesWideIndexSitemapWithoutTitles() throws Exception {
        List<TradersUnionNewsClient.NewsUrl> urls =
                TradersUnionNewsClient.parseSitemap(fixture("tu-news-index-de.xml"));

        assertFalse(urls.isEmpty());
        TradersUnionNewsClient.NewsUrl first = urls.get(0);
        assertEquals("stocks", first.segment());
        assertEquals("2864188", first.articleId());
        assertEquals("uipath-jumps-3-23percent-to-usd12-77", first.slug());
        assertNull(first.title(), "the wide sitemap carries loc + lastmod only");
        assertNotNull(first.lastModified());
        assertNull(first.asNewsItem(), "a titleless url is no wire item");
    }

    @Test
    void sitemapGarbageYieldsEmptyListNotException() {
        assertTrue(TradersUnionNewsClient.parseSitemap("<urlset><url></url>").isEmpty());
        assertTrue(TradersUnionNewsClient.parseSitemap("<html>cloudflare block</html>").isEmpty());
        assertTrue(TradersUnionNewsClient.parseSitemap("").isEmpty());
        assertTrue(TradersUnionNewsClient.parseSitemap(null).isEmpty());
        assertTrue(TradersUnionNewsClient.parseLocations("not xml").isEmpty());
    }

    // ---- the segment gate ----

    @Test
    void tickSegmentsAreMarkedAndEditorialSegmentsAreNot() throws Exception {
        List<TradersUnionNewsClient.NewsUrl> urls =
                TradersUnionNewsClient.parseSitemap(fixture("tu-news-index-de.xml"));

        TradersUnionNewsClient.NewsUrl tick = urls.stream()
                .filter(u -> "stocks".equals(u.segment())).findFirst().orElseThrow();
        TradersUnionNewsClient.NewsUrl editorial = urls.stream()
                .filter(u -> "editors-picks".equals(u.segment())).findFirst().orElseThrow();
        TradersUnionNewsClient.NewsUrl centralBank = urls.stream()
                .filter(u -> "central-banks".equals(u.segment())).findFirst().orElseThrow();

        assertTrue(tick.priceMoveGenerated(), "/news/stocks/ is 94 % tick copy");
        assertFalse(editorial.priceMoveGenerated(), "/news/editors-picks/ is 0 %");
        assertFalse(centralBank.priceMoveGenerated(), "/news/central-banks/ is 0 %");
        assertEquals(94, tick.segmentTickRate());
        assertEquals(0, editorial.segmentTickRate());

        // The slug pattern is the SECOND signal: it catches a move piece parked
        // in an otherwise editorial segment.
        assertTrue(TradersUnionNewsClient.isPriceMoveGenerated(
                "market-voices", "nvidia-surges-4-2percent-today"));
        assertFalse(TradersUnionNewsClient.isPriceMoveGenerated(
                "central-banks", "fed-officials-push"));
    }

    @Test
    void tickCopyIsMarkedByPublisherNotDropped() throws Exception {
        List<RawNewsItem> items = clientWithSitemaps().latest(50);

        List<RawNewsItem> tick = items.stream()
                .filter(it -> TradersUnionNewsClient.PUBLISHER_TICK.equals(it.publisher()))
                .toList();
        List<RawNewsItem> editorial = items.stream()
                .filter(it -> TradersUnionNewsClient.PUBLISHER.equals(it.publisher()))
                .toList();

        assertFalse(tick.isEmpty(), "the fixture carries companies/crypto move pieces");
        assertFalse(editorial.isEmpty(), "and editors-picks/financial-news pieces");
        assertEquals(items.size(), tick.size() + editorial.size(), "nothing is thrown away");
        tick.forEach(it -> assertFalse(it.title().isBlank(), "the headline survives the mark"));
    }

    @Test
    void brokerPrIsFlaggedSponsored() throws Exception {
        List<RawNewsItem> items = clientWithSitemaps().latest(50);

        List<RawNewsItem> sponsored = items.stream().filter(RawNewsItem::sponsored).toList();
        assertFalse(sponsored.isEmpty(), "/news/brokers-news/ is paid placement");
        sponsored.forEach(it -> assertTrue(it.link().contains("/news/brokers-news/")));
        items.stream().filter(it -> !it.link().contains("/news/brokers-news/"))
                .forEach(it -> assertFalse(it.sponsored(), "only broker PR is paid: " + it.link()));
    }

    // ---- the general stream ----

    @Test
    void latestAndSectionServeTheWholeStreamOffOneFetch() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                "news-sitemap-de", fixture("tu-news-sitemap-de.xml")));
        TradersUnionNewsClient client = new TradersUnionNewsClient(fetcher);

        List<RawNewsItem> latest = client.latest(50);
        assertFalse(latest.isEmpty());
        Instant previous = null;
        for (RawNewsItem it : latest) {
            if (previous != null) assertFalse(it.publishedAt().isAfter(previous), "not newest-first");
            previous = it.publishedAt();
        }

        List<RawNewsItem> picks = client.section("editors-picks", 10);
        assertFalse(picks.isEmpty());
        picks.forEach(it -> assertTrue(it.link().contains("/news/editors-picks/")));
        assertTrue(client.section("does-not-exist", 10).isEmpty());

        assertEquals(1, fetcher.count("news-sitemap-de"),
                "latest + two section calls share one cached pool");
    }

    @Test
    void wideIndexExposesEverySegmentForLaterMobilisation() throws Exception {
        TradersUnionNewsClient client = clientWithSitemaps();

        List<TradersUnionNewsClient.NewsUrl> index = client.index();
        Set<String> segments = index.stream()
                .map(TradersUnionNewsClient.NewsUrl::segment).collect(java.util.stream.Collectors.toSet());
        assertTrue(segments.containsAll(
                Set.of("stocks", "companies", "central-banks", "editors-picks",
                        "financial-news", "commodities", "institutions")));

        List<TradersUnionNewsClient.NewsUrl> banks = client.sectionUrls("central-banks", 10);
        assertFalse(banks.isEmpty());
        banks.forEach(u -> assertEquals("central-banks", u.segment()));
        assertEquals(TradersUnionNewsClient.SEGMENT_TICK_RATE.keySet().size(), 12,
                "the measured tick rates stay published for a gate further up");
    }

    // ---- full text ----

    @Test
    void readsArticleBodyIdentityAndLeadTicker() throws Exception {
        TradersUnionNewsClient.Article a = TradersUnionNewsClient.parseArticle(
                "https://tradersunion.com/de/news/stocks/show/2864188-uipath-jumps-3-23percent-to-usd12-77/",
                fixture("tu-article-stocks.html"));

        assertNotNull(a);
        assertEquals("UiPath-Aktienrallye: Kursziele im Blick", a.title());
        assertEquals(Instant.parse("2026-07-31T20:04:39Z"), a.publishedAt());
        assertEquals("Parshwa Turakhiya", a.author());
        assertTrue(a.imageUrl().endsWith("uipath_02.jpg"));
        assertEquals("stocks", a.segment());
        assertTrue(a.priceMoveGenerated());
        assertFalse(a.sponsored());
        assertTrue(a.fullText().length() > 1000, "div.news-single__content carries the body");
        assertTrue(a.fullText().contains("GAAP-Nettoüberschuss"),
                "tick copy is NOT empty - it quotes real figures");
        assertFalse(a.fullText().contains("<p>"), "tags are stripped");
        assertEquals(List.of("PATH"), a.relatedTickers(),
                "the only symbol an article prints sits in the lead");
        assertTrue(a.machineTranslated(),
                "every German piece is a machine translation of the English original");
    }

    @Test
    void editorialArticleStaysUnmarked() throws Exception {
        TradersUnionNewsClient.Article a = TradersUnionNewsClient.parseArticle(
                "https://tradersunion.com/de/news/central-banks/show/2861026-fed-officials-push/",
                fixture("tu-article-central-banks.html"));

        assertNotNull(a);
        assertEquals("central-banks", a.segment());
        assertFalse(a.priceMoveGenerated(), "0 % tick share - this is editorial");
        assertEquals(TradersUnionNewsClient.PUBLISHER, a.asNewsItem().publisher());
        assertTrue(a.fullText().length() > 500);

        assertNull(TradersUnionNewsClient.parseArticle("u", "<html><body>nothing</body></html>"));
        assertNull(TradersUnionNewsClient.parseArticle("u", ""));
    }

    // ---- 500 tolerance ----

    @Test
    void aFiveHundredNeverKillsTheFetch() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                "news-sitemap-de", fixture("tu-news-sitemap-de.xml")));
        fetcher.status = 500; // the wide sitemap and every article page 500
        TradersUnionNewsClient client = new TradersUnionNewsClient(fetcher);

        assertFalse(client.latest(10).isEmpty(), "the healthy leg still answers");
        assertTrue(client.index().isEmpty());
        assertNull(client.article("https://tradersunion.com/de/news/stocks/show/1-a/"));
        assertTrue(client.ranking("https://tradersunion.com/ranking/bafin-regulated-forex-brokers/")
                .isEmpty());
        assertNull(client.brokerReview("https://tradersunion.com/de/brokers/forex/view/etoro/"));
    }

    // ---- instrument fans ----

    @Test
    void symbolFanStaysSilentBecauseArticlesTagNoInstrument() {
        assertTrue(new TradersUnionNewsClient(new FakeFetcher(Map.of()))
                .newsFor("PATH", 5).isEmpty());
    }

    @Test
    void nameFanMatchesTitlesAndEnglishSlugsWithoutDuplicates() throws Exception {
        TradersUnionNewsClient client = new TradersUnionNewsClient(new FakeFetcher(Map.of(
                "news-sitemap-de", fixture("tu-news-sitemap-de.xml"),
                "sitemap_news_de", fixture("tu-news-index-de.xml"),
                "2864188-uipath", fixture("tu-article-stocks.html"))));

        List<RawNewsItem> hits = client.newsForName("UiPath", 20);

        assertFalse(hits.isEmpty(), "the German title never says UiPath, the English slug does");
        assertEquals(hits.size(), hits.stream().map(RawNewsItem::link).distinct().count());
        hits.forEach(it -> assertTrue(it.link().contains("uipath"), it.link()));
        assertEquals(TradersUnionNewsClient.PUBLISHER_TICK, hits.get(0).publisher());
    }

    @Test
    void precisionFilterNeedsAWholeSignificantWord() {
        assertEquals(Set.of("palo", "alto", "networks"),
                TradersUnionNewsClient.significantWords("Palo Alto Networks Inc."));
        assertTrue(TradersUnionNewsClient.significantWords("AG SE").isEmpty());

        Set<String> words = TradersUnionNewsClient.significantWords("Rheinmetall");
        assertTrue(TradersUnionNewsClient.matches("Rheinmetall hebt Prognose an", words));
        assertFalse(TradersUnionNewsClient.matches("Der Rheinpegel steigt", words),
                "stem overlap must not count as a hit");
    }

    @Test
    void archiveWindowKeepsOnlyItemsInsideTheWindowAndRejectsBadBounds() throws Exception {
        TradersUnionNewsClient client = clientWithSitemaps();

        List<RawNewsItem> hits =
                client.newsForNameWindow("Weatherford", null, "2026-08-01", "2026-08-03", 20);
        hits.forEach(it -> {
            assertFalse(it.publishedAt().isBefore(Instant.parse("2026-08-01T00:00:00Z")));
            assertTrue(it.publishedAt().isBefore(Instant.parse("2026-08-03T00:00:00Z")));
        });

        assertTrue(client.newsForNameWindow("Weatherford", null, "nope", "2026-08-03", 10).isEmpty());
        assertTrue(client.newsForNameWindow("Weatherford", null, "2026-08-01", null, 10).isEmpty());
    }

    // ---- the separate broker shelf ----

    @Test
    void brokerReviewCarriesItsRatingAndNeverBecomesAWireItem() throws Exception {
        TradersUnionNewsClient.BrokerReview r = TradersUnionNewsClient.parseBrokerReview(
                "https://tradersunion.com/de/brokers/forex/view/etoro/",
                fixture("tu-broker-etoro.html"));

        assertNotNull(r);
        assertEquals("eToro", r.broker());
        assertEquals(6.9d, r.rating(), 1e-9);
        assertEquals(9.99d, r.bestRating(), 1e-9);
        assertEquals(6.906906906906907d, r.ratingOutOfTen(), 1e-6);
        assertEquals("Oleg Tkachenko", r.author());
        assertEquals(Instant.parse("2023-06-16T00:00:00Z"), r.publishedAt());
        assertNotNull(r.body());

        assertNull(TradersUnionNewsClient.parseBrokerReview("u", "<html>no ld+json</html>"));
    }

    @Test
    void rankingIsReadAsAnOrderedItemList() throws Exception {
        List<TradersUnionNewsClient.RankingEntry> ranking =
                TradersUnionNewsClient.parseRanking(fixture("tu-ranking-bafin.html"));

        assertFalse(ranking.isEmpty());
        assertEquals(1, ranking.get(0).position());
        assertEquals("Fusion Markets", ranking.get(0).broker());
        assertTrue(ranking.get(0).listName().contains("BaFin"));
        for (int i = 1; i < ranking.size(); i++) {
            assertTrue(ranking.get(i).position() > ranking.get(i - 1).position());
        }
        assertTrue(TradersUnionNewsClient.parseRanking("<html>nothing</html>").isEmpty());
    }

    @Test
    void brokerAndRankingUrlsComeOutOfSitemapsOnly() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                "sitemap_brokers_de", fixture("tu-sitemap-brokers-de.xml"),
                "sitemap_ranking_de", fixture("tu-sitemap-ranking-de.xml"),
                "brokers/forex/view/accentforex", fixture("tu-broker-etoro.html")));
        TradersUnionNewsClient client = new TradersUnionNewsClient(fetcher);

        List<String> brokers = client.brokerReviewUrls(5);
        assertEquals(5, brokers.size());
        brokers.forEach(u -> assertTrue(u.startsWith("https://tradersunion.com/de/brokers/"), u));
        brokers.forEach(u -> assertFalse(u.endsWith(".jpeg"),
                "the nested image:loc must not pass as a page url"));

        List<String> rankings = client.rankingUrls(3);
        assertEquals(3, rankings.size());
        rankings.forEach(u -> assertTrue(u.contains("/ranking/")));

        assertEquals(1, client.brokerReviews(1).size(), "reviews are read page by page");
    }
}
