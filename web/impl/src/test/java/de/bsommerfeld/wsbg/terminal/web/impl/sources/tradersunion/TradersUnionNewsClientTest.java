package de.bsommerfeld.wsbg.terminal.web.impl.sources.tradersunion;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport.FakeWebFetcher;
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
 * Parser and fan tests against live-captured Traders Union fixtures
 * (2026-08-02). Ported from the module world; the general stream and the
 * broker shelf did not migrate, the archive window returned as the
 * {@code ArchiveSource} leg.
 */
class TradersUnionNewsClientTest {

    private static String fixture(String name) {
        return SourceTestSupport.fixture("sources/tradersunion/" + name);
    }

    private static FakeWebFetcher sitemapFetcher() {
        return new FakeWebFetcher()
                .on("news-sitemap-de", fixture("tu-news-sitemap-de.xml"))
                .on("sitemap_news_de", fixture("tu-news-index-de.xml"));
    }

    private static ResolvedInstrument named(String name) {
        return ResolvedInstrument.ofName(name);
    }

    // ---- sitemap parsing ----

    @Test
    void parsesGoogleNewsSitemapIntoTitledItems() {
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
    void parsesWideIndexSitemapWithoutTitles() {
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
    }

    // ---- the segment gate ----

    @Test
    void tickSegmentsAreMarkedAndEditorialSegmentsAreNot() {
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
    void tickCopyIsMarkedByPublisherNotDroppedAndBrokerPrIsSponsored() {
        List<TradersUnionNewsClient.NewsUrl> urls =
                TradersUnionNewsClient.parseSitemap(fixture("tu-news-sitemap-de.xml"));
        List<Article> items = urls.stream()
                .map(TradersUnionNewsClient.NewsUrl::asNewsItem)
                .filter(java.util.Objects::nonNull)
                .toList();

        List<Article> tick = items.stream()
                .filter(it -> TradersUnionNewsClient.PUBLISHER_TICK.equals(it.publisher()))
                .toList();
        List<Article> editorial = items.stream()
                .filter(it -> TradersUnionNewsClient.PUBLISHER.equals(it.publisher()))
                .toList();

        assertFalse(tick.isEmpty(), "the fixture carries companies/crypto move pieces");
        assertFalse(editorial.isEmpty(), "and editors-picks/financial-news pieces");
        assertEquals(items.size(), tick.size() + editorial.size(), "nothing is thrown away");

        List<Article> sponsored = items.stream().filter(Article::sponsored).toList();
        assertFalse(sponsored.isEmpty(), "/news/brokers-news/ is paid placement");
        sponsored.forEach(it -> assertTrue(it.link().contains("/news/brokers-news/")));
    }

    // ---- full text ----

    @Test
    void readsArticleBodyIdentityAndLeadTicker() {
        TradersUnionNewsClient.ReadArticle a = TradersUnionNewsClient.parseArticle(
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
    void editorialArticleStaysUnmarked() {
        TradersUnionNewsClient.ReadArticle a = TradersUnionNewsClient.parseArticle(
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

    // ---- instrument fan ----

    @Test
    void symbolOnlyStaysSilentBecauseArticlesTagNoInstrument() {
        FakeWebFetcher fetcher = new FakeWebFetcher();
        assertTrue(new TradersUnionNewsClient(fetcher).newsFor(new ResolvedInstrument(
                Optional.empty(), Ticker.parse("PATH"), ""), 5).isEmpty());
        assertEquals(0, fetcher.total());
    }

    @Test
    void nameFanMatchesTitlesAndEnglishSlugsWithoutDuplicates() {
        TradersUnionNewsClient client = new TradersUnionNewsClient(sitemapFetcher()
                .on("2864188-uipath", fixture("tu-article-stocks.html")));

        List<Article> hits = client.newsFor(named("UiPath"), 20);

        assertFalse(hits.isEmpty(), "the German title never says UiPath, the English slug does");
        assertEquals(hits.size(), hits.stream().map(Article::link).distinct().count());
        hits.forEach(it -> assertTrue(it.link().contains("uipath"), it.link()));
        assertEquals(TradersUnionNewsClient.PUBLISHER_TICK, hits.get(0).publisher());
    }

    @Test
    void aFiveHundredNeverKillsTheFetch() {
        FakeWebFetcher fetcher = new FakeWebFetcher()
                .on("news-sitemap-de", fixture("tu-news-sitemap-de.xml"));
        fetcher.missStatus = 500; // the wide sitemap and every article page 500
        TradersUnionNewsClient client = new TradersUnionNewsClient(fetcher);

        List<Article> hits = client.newsFor(named("OKX"), 10);
        assertFalse(hits.isEmpty(), "the healthy headline leg still answers");
    }

    // ---- archive window ----

    @Test
    void archiveWindowKeepsOnlyItemsInsideTheWindow() {
        TradersUnionNewsClient client = new TradersUnionNewsClient(sitemapFetcher());

        // OKX sits in the headline pool at 2026-08-03T00:43:16Z.
        List<Article> inside = client.newsForWindow(named("OKX"),
                LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-04"), 20);
        assertFalse(inside.isEmpty(), "the pool piece inside the window answers");
        inside.forEach(it -> {
            assertFalse(it.publishedAt().isBefore(Instant.parse("2026-08-03T00:00:00Z")));
            assertTrue(it.publishedAt().isBefore(Instant.parse("2026-08-04T00:00:00Z")));
        });

        List<Article> before = client.newsForWindow(named("OKX"),
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-08-03"), 20);
        assertTrue(before.isEmpty(), "the same piece falls outside an earlier window");
    }

    @Test
    void archiveWindowGatesIndexFetchesOnTheWindowBeforeSpendingThem() {
        // Weatherford lives only in the wide index (lastmod 2026-08-02T09:24:20Z);
        // no article fixture is wired, so a fetch attempt would answer 404.
        FakeWebFetcher fetcher = sitemapFetcher();
        TradersUnionNewsClient client = new TradersUnionNewsClient(fetcher);

        assertTrue(client.newsForWindow(named("Weatherford"),
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-07-01"), 20).isEmpty());
        assertTrue(fetcher.calls.stream().noneMatch(u -> u.contains("weatherford")),
                "an index hit outside the window must not cost a page fetch");

        client.newsForWindow(named("Weatherford"),
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-03"), 20);
        assertTrue(fetcher.calls.stream().anyMatch(u -> u.contains("weatherford")),
                "inside the window the slug hit is read in full");
    }

    @Test
    void archiveWindowRejectsAnEmptyName() {
        FakeWebFetcher fetcher = new FakeWebFetcher();
        assertTrue(new TradersUnionNewsClient(fetcher).newsForWindow(named(""),
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-03"), 10).isEmpty());
        assertEquals(0, fetcher.total(), "a nameless window must not fetch");
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
}
