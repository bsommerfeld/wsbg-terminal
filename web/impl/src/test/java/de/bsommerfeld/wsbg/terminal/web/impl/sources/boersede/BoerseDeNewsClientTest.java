package de.bsommerfeld.wsbg.terminal.web.impl.sources.boersede;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport.FakeWebFetcher;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import org.junit.jupiter.api.DisplayName;
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
 * Fixture-driven tests for the boerse.de news legs (fixtures curled
 * 2026-08-02). Ported from the module world; the general stream doors did not
 * migrate, the archive-window door returned as {@code ArchiveSource}.
 */
class BoerseDeNewsClientTest {

    private static final String P1 = "news-isin-page1.html";
    private static final String P2 = "news-isin-page2.html";
    private static final String GLOBAL = "news-global.html";

    private static String fixture(String name) {
        return SourceTestSupport.fixture("sources/boersede/" + name);
    }

    private static FakeWebFetcher fetcher410() {
        FakeWebFetcher f = new FakeWebFetcher();
        f.missStatus = 410; // boerse.de answers 410 for unknown surfaces
        return f;
    }

    private static ResolvedInstrument byIsin(String isin) {
        return new ResolvedInstrument(Isin.parse(isin), Optional.empty(), "");
    }

    // ------------------------------------------------------------- list parsing

    @Test
    @DisplayName("list page: lead teaser plus every bordered row becomes an item")
    void parsesLeadAndRows() {
        List<Article> items = BoerseDeNewsClient.parseNewsList(fixture(P1));

        assertEquals(7, items.size(), "1 lead teaser + 6 list rows");
        Article lead = items.get(0);
        assertEquals("38494412", lead.uuid());
        assertEquals("BOTSI®-Advisor Abstufung SAP von Rang 245 auf ...", lead.title());
        assertEquals("boerse.de", lead.publisher());
        assertNotNull(lead.summary(), "the lead is the only item carrying teaser text");
        assertTrue(lead.summary().startsWith("SAP ist Teil des 250 Titel"));

        Article row = items.get(1);
        assertEquals("38492358", row.uuid());
        assertTrue(row.title().startsWith("EQS-PVR: SAP SE: Korrektur einer Veröffentlichung"));
        assertNull(row.summary(), "list rows carry no body");
    }

    @Test
    @DisplayName("list page: German dd.MM.yy dates land on the Berlin trading day")
    void parsesGermanShortDates() {
        List<Article> items = BoerseDeNewsClient.parseNewsList(fixture(P1));

        Instant expected = LocalDate.of(2026, 7, 31)
                .atStartOfDay(BoerseDeHtml.ZONE).toInstant();
        assertEquals(expected, items.get(1).publishedAt());
        assertEquals(LocalDate.of(2026, 7, 30).atStartOfDay(BoerseDeHtml.ZONE).toInstant(),
                items.get(3).publishedAt());
    }

    @Test
    @DisplayName("list page: entities and soft hyphens are decoded out of titles")
    void decodesEntitiesInTitles() {
        List<Article> items = BoerseDeNewsClient.parseNewsList(fixture(P2));

        assertTrue(items.stream().anyMatch(i -> i.title().equals("SAP-Aktie über 38-Tage-Linie")),
                "&uuml; must be decoded, not passed through");
        assertTrue(items.stream().noneMatch(i -> i.title().contains("&")));
    }

    @Test
    @DisplayName("list page: garbage in, empty list out - never an exception")
    void toleratesGarbage() {
        assertTrue(BoerseDeNewsClient.parseNewsList(null).isEmpty());
        assertTrue(BoerseDeNewsClient.parseNewsList("").isEmpty());
        assertTrue(BoerseDeNewsClient.parseNewsList("<html><body>nope</body></html>").isEmpty());
        assertTrue(BoerseDeNewsClient.parseNewsList("<div class=\"row row-bordered\">").isEmpty());
    }

    // ---------------------------------------------------------------- pagination

    @Test
    @DisplayName("ISIN leg: walks rel=next instead of composing _seite,N onto /x/")
    void paginatesViaRelNext() {
        FakeWebFetcher net = fetcher410()
                .on("_seite,2", fixture(P2))
                .on("/nachrichten/x/DE0007164600", fixture(P1));
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);

        List<Article> items = client.newsFor(byIsin("DE0007164600"), 50);

        assertEquals(12, items.size(), "7 items on page 1 + 5 on page 2");
        assertEquals(List.of(
                        "https://www.boerse.de/nachrichten/x/DE0007164600",
                        "https://www.boerse.de/nachrichten/SAP-Aktie/DE0007164600_seite,2,anzahl,20",
                        "https://www.boerse.de/nachrichten/SAP-Aktie/DE0007164600_seite,3,anzahl,20"),
                net.calls,
                "page 1 is addressed by the bare /x/<ISIN> form; deeper pages come "
                        + "from the page's own rel=next");
    }

    @Test
    @DisplayName("ISIN leg: every item carries the queried ISIN (no data-quote in the markup)")
    void stampsQueriedIsin() {
        FakeWebFetcher net = fetcher410()
                .on("/nachrichten/x/DE0007164600", fixture(P1));
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);

        List<Article> items = client.newsFor(byIsin("DE0007164600"), 5);

        assertEquals(5, items.size());
        assertTrue(items.stream().allMatch(i -> "DE0007164600".equals(i.isin())),
                "the PAGE is ISIN-addressed, so the ISIN is stamped on every row");
        assertFalse(fixture(P1).contains("data-quote"),
                "guard: the 2026-08-02 note expected data-quote, the live markup has none");
    }

    @Test
    @DisplayName("ISIN leg: an unknown ISIN (410) yields empty")
    void unknownIsinYieldsEmpty() {
        FakeWebFetcher net = fetcher410();
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);

        assertTrue(client.newsFor(byIsin("DE0005140008"), 10).isEmpty());
        assertEquals(1, net.total(), "only one request - 410 is definitive");
    }

    @Test
    @DisplayName("no ticker addressing - a symbol-only instrument stays silent")
    void symbolOnlyIsSilent() {
        FakeWebFetcher net = fetcher410();
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);
        assertTrue(client.newsFor(new ResolvedInstrument(
                Optional.empty(), Ticker.parse("SAP"), ""), 10).isEmpty());
        assertTrue(net.calls.isEmpty(), "a symbol must not trigger a fetch");
        assertEquals("boerse-de", client.sourceName());
    }

    // ------------------------------------------------------------------ sitemap

    @Test
    @DisplayName("sitemap index: the four child sitemaps are read off the index")
    void readsSitemapIndex() {
        List<String> children =
                BoerseDeNewsClient.sitemapChildren(fixture("sitemap-index.xml"));

        assertEquals(4, children.size());
        assertEquals("https://www.boerse.de/sitemap_news_0.xml", children.get(0));
        assertTrue(BoerseDeNewsClient.sitemapChildren("<xml/>").isEmpty());
        assertTrue(BoerseDeNewsClient.sitemapChildren(null).isEmpty());
    }

    @Test
    @DisplayName("sitemap child: news:title + lastmod become a dated item with an id")
    void parsesNewsSitemap() {
        List<Article> items =
                BoerseDeNewsClient.parseNewsSitemap(fixture("sitemap-news-0.xml"));

        assertEquals(5, items.size());
        Article first = items.get(0);
        assertEquals("38496476", first.uuid());
        assertEquals("Feuer: Tausende sollen Zuhause in US-Stadt Spokane verlassen",
                first.title());
        assertEquals(Instant.parse("2026-08-02T21:38:06Z"), first.publishedAt(),
                "lastmod is a real clock, unlike the day-granular list pages");
        assertTrue(BoerseDeNewsClient.parseNewsSitemap("<urlset/>").isEmpty());
    }

    // ------------------------------------------------------- precision + fan

    @Test
    @DisplayName("name leg: the title-precision cut keeps the named company, drops the rest")
    void nameLegAppliesPrecisionCut() {
        FakeWebFetcher net = fetcher410()
                .on("sitemap/sitemap_news.xml", fixture("sitemap-index.xml"))
                .on("sitemap_news_0.xml", fixture("sitemap-news-0.xml"))
                .on("/nachrichten/", fixture(GLOBAL));
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);

        List<Article> hits = client.newsFor(
                ResolvedInstrument.ofName("Odessa Holding AG"), 10);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).title().contains("Odessa"));

        assertTrue(client.newsFor(
                ResolvedInstrument.ofName("Nichtvorhanden Technologies"), 10).isEmpty());
        assertTrue(client.newsFor(ResolvedInstrument.ofName("AG"), 10).isEmpty(),
                "a name that is nothing but generic words must not match everything");
        assertTrue(client.newsFor(ResolvedInstrument.ofName("Odessa"), 0).isEmpty());
    }

    @Test
    @DisplayName("fan: the same piece under two slugs collapses onto the article id")
    void fanDedupesByArticleId() {
        FakeWebFetcher net = fetcher410()
                .on("sitemap/sitemap_news.xml", fixture("sitemap-index.xml"))
                .on("sitemap_news_0.xml", fixture("sitemap-news-0.xml"))
                .on("/nachrichten/", fixture(GLOBAL));
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);

        List<Article> hits = client.newsFor(
                ResolvedInstrument.ofName("Spokane Feuer Zuhause"), 20);

        assertEquals(1, hits.stream().filter(i -> i.uuid().equals("38496476")).count(),
                "the section list and the sitemap print different slugs for one id");
    }

    @Test
    @DisplayName("archive window: the ISIN list is clipped to [from, to)")
    void archiveWindowClipsByDate() throws Exception {
        FakeWebFetcher net = fetcher410()
                .on("_seite,2", fixture(P2))
                .on("/nachrichten/x/DE0007164600", fixture(P1));
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);

        // Window bounds are Berlin days, matching how the list pages date items -
        // a UTC bound would shift every row two hours and lose the whole day.
        ResolvedInstrument sap = new ResolvedInstrument(
                Isin.parse("DE0007164600"), Optional.empty(), "SAP");
        List<Article> july30 = client.newsForWindow(sap,
                LocalDate.parse("2026-07-30"), LocalDate.parse("2026-07-31"), 20);
        assertEquals(4, july30.size());
        assertTrue(july30.stream().allMatch(i -> i.isin().equals("DE0007164600")));

        assertTrue(client.newsForWindow(sap,
                LocalDate.parse("2026-07-30"), LocalDate.parse("2026-07-31"), 0).isEmpty());
    }

    @Test
    @DisplayName("archive window: without an ISIN the pooled name cut is clipped instead")
    void archiveWindowDegradesToTheNameCut() throws Exception {
        // Sitemap only: the section rows are day-less (times only), so a merged
        // duplicate would win the dedup undated and fall out of every window.
        FakeWebFetcher net = fetcher410()
                .on("sitemap/sitemap_news.xml", fixture("sitemap-index.xml"))
                .on("sitemap_news_0.xml", fixture("sitemap-news-0.xml"));
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);

        List<Article> hits = client.newsForWindow(
                ResolvedInstrument.ofName("Odessa Holding AG"),
                LocalDate.parse("2026-08-02"), LocalDate.parse("2026-08-03"), 10);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).title().contains("Odessa"));

        assertTrue(client.newsForWindow(ResolvedInstrument.ofName("Odessa Holding AG"),
                        LocalDate.parse("2020-01-01"), LocalDate.parse("2020-01-02"), 10)
                .isEmpty(), "the window cut applies to the name leg too");
    }

    @Test
    @DisplayName("precision filter: generic legal-form words never carry a match alone")
    void significantWordsDropGenerics() {
        assertEquals(Set.of("siemens", "energy"),
                BoerseDeNewsClient.significantWords("Siemens Energy AG"));
        assertEquals(Set.of("muenchener", "rueck"),
                BoerseDeNewsClient.significantWords("Münchener Rück"));
        assertTrue(BoerseDeNewsClient.significantWords("Die Holding Group").isEmpty());
        assertTrue(BoerseDeNewsClient.significantWords(null).isEmpty());

        Set<String> words = BoerseDeNewsClient.significantWords("SAP SE");
        assertTrue(BoerseDeNewsClient.titleMatches("SAP-Aktie über 38-Tage-Linie", words));
        assertFalse(BoerseDeNewsClient.titleMatches("Saphir glänzt", words),
                "word-boundary match, not substring");
        assertFalse(BoerseDeNewsClient.titleMatches(null, words));
    }
}
