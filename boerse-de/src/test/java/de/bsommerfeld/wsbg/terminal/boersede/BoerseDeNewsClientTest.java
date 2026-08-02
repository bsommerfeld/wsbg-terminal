package de.bsommerfeld.wsbg.terminal.boersede;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixture-driven tests for the boerse.de news legs (fixtures curled 2026-08-02). */
class BoerseDeNewsClientTest {

    private static final String P1 = "news-isin-page1.html";
    private static final String P2 = "news-isin-page2.html";
    private static final String GLOBAL = "news-global.html";

    // ------------------------------------------------------------- list parsing

    @Test
    @DisplayName("list page: lead teaser plus every bordered row becomes an item")
    void parsesLeadAndRows() {
        List<RawNewsItem> items = BoerseDeNewsClient.parseNewsList(Fixtures.load(P1));

        assertEquals(7, items.size(), "1 lead teaser + 6 list rows");
        RawNewsItem lead = items.get(0);
        assertEquals("38494412", lead.uuid());
        assertEquals("BOTSI®-Advisor Abstufung SAP von Rang 245 auf ...", lead.title());
        assertEquals("boerse.de", lead.publisher());
        assertNotNull(lead.summary(), "the lead is the only item carrying teaser text");
        assertTrue(lead.summary().startsWith("SAP ist Teil des 250 Titel"));

        RawNewsItem row = items.get(1);
        assertEquals("38492358", row.uuid());
        assertTrue(row.title().startsWith("EQS-PVR: SAP SE: Korrektur einer Veröffentlichung"));
        assertNull(row.summary(), "list rows carry no body");
    }

    @Test
    @DisplayName("list page: German dd.MM.yy dates land on the Berlin trading day")
    void parsesGermanShortDates() {
        List<RawNewsItem> items = BoerseDeNewsClient.parseNewsList(Fixtures.load(P1));

        Instant expected = LocalDate.of(2026, 7, 31)
                .atStartOfDay(BoerseDeHtml.ZONE).toInstant();
        assertEquals(expected, items.get(1).publishedAt());
        assertEquals(LocalDate.of(2026, 7, 30).atStartOfDay(BoerseDeHtml.ZONE).toInstant(),
                items.get(3).publishedAt());
    }

    @Test
    @DisplayName("list page: entities and soft hyphens are decoded out of titles")
    void decodesEntitiesInTitles() {
        List<RawNewsItem> items = BoerseDeNewsClient.parseNewsList(Fixtures.load(P2));

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
        Fixtures.FakeFetcher net = new Fixtures.FakeFetcher()
                .on("_seite,2", P2)
                .on("/nachrichten/x/DE0007164600", P1);
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);

        List<RawNewsItem> items = client.newsForIsin("DE0007164600", 50);

        assertEquals(12, items.size(), "7 items on page 1 + 5 on page 2");
        assertEquals(List.of(
                        "https://www.boerse.de/nachrichten/x/DE0007164600",
                        "https://www.boerse.de/nachrichten/SAP-Aktie/DE0007164600_seite,2,anzahl,20",
                        "https://www.boerse.de/nachrichten/SAP-Aktie/DE0007164600_seite,3,anzahl,20"),
                net.requested,
                "page 1 is addressed by the bare /x/<ISIN> form; deeper pages come "
                        + "from the page's own rel=next");
    }

    @Test
    @DisplayName("ISIN leg: every item carries the queried ISIN (no data-quote in the markup)")
    void stampsQueriedIsin() {
        Fixtures.FakeFetcher net = new Fixtures.FakeFetcher()
                .on("/nachrichten/x/DE0007164600", P1);
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);

        List<RawNewsItem> items = client.newsForIsin("de0007164600", 5);

        assertEquals(5, items.size());
        assertTrue(items.stream().allMatch(i -> "DE0007164600".equals(i.isin())),
                "the PAGE is ISIN-addressed, so the ISIN is stamped on every row");
        assertFalse(Fixtures.load(P1).contains("data-quote"),
                "guard: the 2026-08-02 note expected data-quote, the live markup has none");
    }

    @Test
    @DisplayName("ISIN leg: an unknown ISIN (410) yields empty, and blanks fetch nothing")
    void unknownIsinYieldsEmpty() {
        Fixtures.FakeFetcher net = new Fixtures.FakeFetcher();
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);

        assertTrue(client.newsForIsin("DE000INVALID", 10).isEmpty());
        assertTrue(client.newsForIsin("  ", 10).isEmpty());
        assertTrue(client.newsForIsin(null, 10).isEmpty());
        assertEquals(1, net.requested.size(), "only the real ISIN costs a request");
    }

    @Test
    @DisplayName("ISIN leg: no ticker addressing - newsFor stays a no-op")
    void newsForSymbolIsNoop() {
        Fixtures.FakeFetcher net = new Fixtures.FakeFetcher();
        assertTrue(new BoerseDeNewsClient(net).newsFor("SAP", 10).isEmpty());
        assertTrue(net.requested.isEmpty(), "a symbol must not trigger a fetch");
        assertEquals("boerse-de", new BoerseDeNewsClient(net).sourceName());
    }

    // ------------------------------------------------------------------ sitemap

    @Test
    @DisplayName("sitemap index: the four child sitemaps are read off the index")
    void readsSitemapIndex() {
        List<String> children =
                BoerseDeNewsClient.sitemapChildren(Fixtures.load("sitemap-index.xml"));

        assertEquals(4, children.size());
        assertEquals("https://www.boerse.de/sitemap_news_0.xml", children.get(0));
        assertTrue(BoerseDeNewsClient.sitemapChildren("<xml/>").isEmpty());
        assertTrue(BoerseDeNewsClient.sitemapChildren(null).isEmpty());
    }

    @Test
    @DisplayName("sitemap child: news:title + lastmod become a dated item with an id")
    void parsesNewsSitemap() {
        List<RawNewsItem> items =
                BoerseDeNewsClient.parseNewsSitemap(Fixtures.load("sitemap-news-0.xml"));

        assertEquals(5, items.size());
        RawNewsItem first = items.get(0);
        assertEquals("38496476", first.uuid());
        assertEquals("Feuer: Tausende sollen Zuhause in US-Stadt Spokane verlassen",
                first.title());
        assertEquals(Instant.parse("2026-08-02T21:38:06Z"), first.publishedAt(),
                "lastmod is a real clock, unlike the day-granular list pages");
        assertTrue(BoerseDeNewsClient.parseNewsSitemap("<urlset/>").isEmpty());
    }

    @Test
    @DisplayName("firehose: index and children are fetched once, then served from cache")
    void sitemapFirehoseIsCached() {
        Fixtures.FakeFetcher net = new Fixtures.FakeFetcher()
                .on("sitemap/sitemap_news.xml", "sitemap-index.xml")
                .on("sitemap_news_0.xml", "sitemap-news-0.xml");
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);

        assertEquals(5, client.sitemapLatest(20).size());
        int afterFirst = net.requested.size();
        assertEquals(5, afterFirst, "1 index + 4 children");

        client.sitemapLatest(20);
        assertEquals(afterFirst, net.requested.size(), "second call must cost no fetch");
        assertTrue(client.sitemapLatest(0).isEmpty());
    }

    // ------------------------------------------------------------- general legs

    @Test
    @DisplayName("general leg: latest() merges section pool and firehose, de-duped by article id")
    void latestMergesPoolAndFirehose() {
        Fixtures.FakeFetcher net = new Fixtures.FakeFetcher()
                .on("sitemap/sitemap_news.xml", "sitemap-index.xml")
                .on("sitemap_news_0.xml", "sitemap-news-0.xml")
                .on("/nachrichten/", GLOBAL);
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);

        List<RawNewsItem> items = client.latest(50);

        // The same five pieces appear on the section list AND in the sitemap under
        // a different slug, so the merge must key on the article id, not the link.
        assertEquals(1, items.stream().filter(i -> i.uuid().equals("38496476")).count());
        assertEquals(6, items.size(), "6 section items + 5 sitemap items, 5 of them the same");
        Instant previous = null;
        for (RawNewsItem item : items) {
            if (item.publishedAt() == null) continue;
            assertTrue(previous == null || !previous.isBefore(item.publishedAt()), "newest first");
            previous = item.publishedAt();
        }
    }

    @Test
    @DisplayName("general leg: section() resolves the known keys and ignores unknown ones")
    void sectionResolvesKnownKeys() {
        Fixtures.FakeFetcher net = new Fixtures.FakeFetcher().on("/marktberichte/", GLOBAL);
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);

        assertEquals(3, client.section("marktberichte", 3).size());
        assertTrue(net.requested.get(0).endsWith("/marktberichte/"));

        int before = net.requested.size();
        assertTrue(client.section("gibtsnicht", 5).isEmpty());
        assertTrue(client.section(null, 5).isEmpty());
        assertEquals(before, net.requested.size(), "an unknown section costs no fetch");
        assertTrue(BoerseDeNewsClient.SECTIONS.containsKey("adhoc"));
        assertEquals(8, BoerseDeNewsClient.SECTIONS.size());
    }

    // ------------------------------------------------------- precision + windows

    @Test
    @DisplayName("name leg: the title-precision cut keeps the named company, drops the rest")
    void nameLegAppliesPrecisionCut() {
        Fixtures.FakeFetcher net = new Fixtures.FakeFetcher()
                .on("sitemap/sitemap_news.xml", "sitemap-index.xml")
                .on("sitemap_news_0.xml", "sitemap-news-0.xml")
                .on("/nachrichten/", GLOBAL);
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);

        List<RawNewsItem> hits = client.newsForName("Odessa Holding AG", 10);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).title().contains("Odessa"));

        assertTrue(client.newsForName("Nichtvorhanden Technologies", 10).isEmpty());
        assertTrue(client.newsForName("AG", 10).isEmpty(),
                "a name that is nothing but generic words must not match everything");
        assertTrue(client.newsForName("Odessa", 0).isEmpty());
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

    @Test
    @DisplayName("archive window: the ISIN list is clipped to [from, to)")
    void archiveWindowClipsByDate() {
        Fixtures.FakeFetcher net = new Fixtures.FakeFetcher()
                .on("_seite,2", P2)
                .on("/nachrichten/x/DE0007164600", P1);
        BoerseDeNewsClient client = new BoerseDeNewsClient(net);

        // Window bounds are Berlin days, matching how the list pages date items -
        // a UTC bound would shift every row two hours and lose the whole day.
        List<RawNewsItem> july30 = client.newsForNameWindow(
                "SAP", "DE0007164600", "2026-07-30", "2026-07-31", 20);
        assertEquals(4, july30.size());
        assertTrue(july30.stream().allMatch(i -> i.isin().equals("DE0007164600")));

        assertTrue(client.newsForNameWindow("SAP", "DE0007164600", "bogus", "2026-07-31", 5)
                .isEmpty());
        assertTrue(client.newsForNameWindow("SAP", "DE0007164600", "2026-07-30", "2026-07-31", 0)
                .isEmpty());
    }
}
