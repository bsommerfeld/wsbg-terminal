package de.bsommerfeld.wsbg.terminal.web.impl.sources.boersenmedien;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser and fan tests against live-captured Börse Online / Der Aktionär
 * fixtures (2026-08-02), trimmed in item count but structurally verbatim.
 * Ported from the module world; the general-stream doors did not migrate, the
 * archive-window/sitemap door returned as the {@code ArchiveSource} leg.
 */
class BoersenmedienNewsClientTest {

    private static String fixture(String name) {
        return SourceTestSupport.fixture("sources/boersenmedien/" + name);
    }

    /** Serves a body per URL via a router function and records every URL. */
    private static final class RoutingFetcher implements WebFetcher {
        final List<String> calls = new ArrayList<>();
        private final Function<String, String> router;

        RoutingFetcher(Function<String, String> router) {
            this.router = router;
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            calls.add(url);
            String body = router.apply(url);
            return body == null
                    ? WebResponse.text(404, "", Map.of())
                    : WebResponse.text(200, body, Map.of());
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

    private static ResolvedInstrument byIsin(String isin) {
        return new ResolvedInstrument(Isin.parse(isin), Optional.empty(), "");
    }

    // ---- Börse Online editorial list ----

    @Test
    void parsesBoerseOnlineEditorialTeasers() {
        List<Article> items =
                BoersenmedienNewsClient.parseBoerseOnlineList(fixture("bo-nachrichten-liste.html"));

        assertEquals(4, items.size());
        Article first = items.get(0);
        assertEquals("Besser als jedes Festgeld: Mit Weltkonzern Siemens zwei Jahre lang "
                + "10,9 Prozent Zinsen einfahren", first.title());
        assertEquals(BoersenmedienNewsClient.PUBLISHER_BO, first.publisher());
        assertTrue(first.link().startsWith("https://www.boerse-online.de/nachrichten/zertifikate/"));
        assertEquals("bm-20405555", first.uuid(), "the CMS id is the identity");
        assertNotNull(first.imageUrl());
        // 2026-07-29 21:40 Europe/Berlin = 19:40 UTC (CEST)
        assertEquals(Instant.parse("2026-07-29T19:40:00Z"), first.publishedAt());
    }

    @Test
    void dataQuoteBadgeCarriesTheArticlesOwnIsin() {
        List<Article> items =
                BoersenmedienNewsClient.parseBoerseOnlineList(fixture("bo-nachrichten-liste.html"));

        assertTrue(items.stream().allMatch(it -> "DE0007236101".equals(it.isin())),
                "the badge ISIN is read straight off data-quote");
    }

    // ---- dpa-AFX wire list ----

    @Test
    void parsesWireTeasersWithoutImageOrTeaser() {
        List<Article> items =
                BoersenmedienNewsClient.parseBoerseOnlineWireList(fixture("bo-dpaafx-liste.html"));

        assertEquals(4, items.size());
        Article first = items.get(0);
        assertEquals("ROUNDUP 2/Schwache Diagnostik-Sparte: Siemens Healthineers senkt Umsatzausblick",
                first.title());
        assertEquals("https://www.boerse-online.de/dpa-afx/roundup-2schwache-diagnostik-sparte-"
                + "siemens-healthineers-senkt-umsatzausblick-542040.html", first.link());
        assertEquals("DE0007236101", first.isin(), "the wire tags the ISIN on article-info");
        assertNull(first.imageUrl());
        assertEquals(Instant.parse("2026-07-31T13:21:00Z"), first.publishedAt());
    }

    // ---- Der Aktionär editorial list + Plus detection ----

    @Test
    void parsesAktionaerTeasersWithGermanDateAndSummary() {
        List<Article> items = BoersenmedienNewsClient.parseAktionaerList(
                fixture("da-artikel-liste.html"), BoersenmedienNewsClient.PUBLISHER_DA);

        assertEquals(4, items.size(), "3 free teasers + 1 Plus teaser in the trimmed capture");
        Article first = items.get(0);
        assertTrue(first.title().startsWith("DAX kommt nicht vom Fleck"));
        assertEquals("DE0006202005", first.isin(),
                "data-quote is the article's PRIMARY instrument, not the queried one");
        assertNotNull(first.summary());
        assertTrue(first.summary().startsWith("Der DAX kommt zum Handelsstart"));
        // 16.07.2026, 09:00 Europe/Berlin = 07:00 UTC (CEST)
        assertEquals(Instant.parse("2026-07-16T07:00:00Z"), first.publishedAt());
    }

    @Test
    void plusArticlesAreMarkedViaPathSegmentAndViaCssClass() {
        String html = fixture("da-artikel-liste.html");
        assertTrue(html.contains("/artikel/deraktionaerplus/"), "fixture carries the path tell");
        assertTrue(html.contains("plus-article"), "fixture carries the CSS tell");

        List<Article> items = BoersenmedienNewsClient.parseAktionaerList(
                html, BoersenmedienNewsClient.PUBLISHER_DA);
        List<Article> plus = items.stream()
                .filter(it -> BoersenmedienNewsClient.PUBLISHER_DA_PLUS.equals(it.publisher()))
                .toList();

        assertEquals(1, plus.size());
        assertFalse(plus.get(0).title().isBlank(), "the headline survives the wall");

        // both tells stand on their own
        assertTrue(BoersenmedienNewsClient.isPlus(
                "https://www.deraktionaer.de/artikel/deraktionaerplus/x-20395447.html"));
        assertTrue(BoersenmedienNewsClient.isPlus(
                "https://www.deraktionaer.de/artikel/der-aktionaer-plus/x-20404557.html"),
                "the sitemap spells the same rubric with hyphens");
        assertFalse(BoersenmedienNewsClient.isPlus(
                "https://www.deraktionaer.de/artikel/aktien/x-20405892.html"));
    }

    @Test
    void jsonLdIsAccessibleForFreeIsTheSecondPlusTell() {
        assertTrue(BoersenmedienNewsClient.isGatedArticleHtml(
                "{\"@type\":\"NewsArticle\",\"isAccessibleForFree\":false}"));
        assertFalse(BoersenmedienNewsClient.isGatedArticleHtml(
                "{\"@type\":\"NewsArticle\",\"isAccessibleForFree\":true}"));
        assertFalse(BoersenmedienNewsClient.isGatedArticleHtml("<html>no flag at all</html>"),
                "Börse Online carries no flag - a missing flag is not a wall");
        assertFalse(BoersenmedienNewsClient.isGatedArticleHtml(null));
    }

    // ---- Der Aktionär wire list ----

    @Test
    void parsesAktionaerWireListWhichCarriesNoDate() {
        List<Article> items =
                BoersenmedienNewsClient.parseAktionaerWireList(fixture("da-nachrichten-liste.html"));

        assertEquals(4, items.size());
        Article first = items.get(0);
        assertEquals("ROUNDUP 2/Schwache Diagnostik-Sparte: Siemens Healthineers senkt Umsatzausblick",
                first.title());
        assertNull(first.publishedAt(), "this list prints no timestamp at all");
        assertEquals("DE0007236101", first.isin(), "taken from the page's symbol header");
        assertEquals("bm-542040", first.uuid());
    }

    // ---- merge / dedupe across the four legs ----

    @Test
    void mergeDedupesTheSameWirePieceAcrossBothHousesAndKeepsTheDatedOne() {
        List<Article> bo =
                BoersenmedienNewsClient.parseBoerseOnlineWireList(fixture("bo-dpaafx-liste.html"));
        List<Article> da =
                BoersenmedienNewsClient.parseAktionaerWireList(fixture("da-nachrichten-liste.html"));

        List<Article> all = new ArrayList<>(bo);
        all.addAll(da);
        List<Article> merged = BoersenmedienNewsClient.dedupe(all);

        assertEquals(bo.size(), merged.size(),
                "identical CMS ids under both roofs collapse into one item");
        assertTrue(merged.stream().allMatch(it -> it.publishedAt() != null),
                "the dated leg is added first, so it wins the merge");
        assertEquals("542040", BoersenmedienNewsClient.articleId(
                "https://www.deraktionaer.de/nachricht/roundup-2-x-542040.html"));
        assertEquals("542040", BoersenmedienNewsClient.articleId(
                "https://www.boerse-online.de/dpa-afx/roundup-2-x-542040.html"));
    }

    @Test
    void isinFanMergesAllFourLegsAndAttributesTheQueriedIsin() {
        String boEditorial = fixture("bo-nachrichten-liste.html");
        String boWire = fixture("bo-dpaafx-liste.html");
        String daEditorial = fixture("da-artikel-liste.html");
        String daWire = fixture("da-nachrichten-liste.html");
        RoutingFetcher fetcher = new RoutingFetcher(url -> {
            if (!url.contains("/1")) return "";            // page 2+ is empty here
            if (url.contains("boerse-online.de/aktie/") && url.contains("/nachrichten/")) return boEditorial;
            if (url.contains("boerse-online.de/aktie/") && url.contains("/dpa-afx/")) return boWire;
            if (url.contains("/aktien/artikel/")) return daEditorial;
            if (url.contains("/aktien/nachrichten/")) return daWire;
            return null;
        });

        List<Article> items =
                new BoersenmedienNewsClient(fetcher).newsFor(byIsin("DE0007236101"), 50);

        assertEquals(12, items.size(), "4 + 4 + 4 + 4 with the 4 wire duplicates collapsed");
        assertTrue(items.stream().allMatch(it -> "DE0007236101".equals(it.isin())),
                "the ADDRESSING ISIN wins over the badge - a round-up must stay attributed");
        assertTrue(fetcher.calls.stream().anyMatch(u -> u.contains("/aktie/x-DE0007236101/nachrichten/1")));
        assertTrue(fetcher.calls.stream().anyMatch(u -> u.contains("/aktie/x-DE0007236101/dpa-afx/1")));
        assertTrue(fetcher.calls.stream().anyMatch(u -> u.contains("/aktien/artikel/x-DE0007236101/1")));
        assertTrue(fetcher.calls.stream().anyMatch(u -> u.contains("/aktien/nachrichten/x-DE0007236101/1")));

        Instant previous = null;
        for (Article it : items) {
            if (it.publishedAt() != null && previous != null) {
                assertFalse(it.publishedAt().isAfter(previous), "freshest first");
            }
            if (it.publishedAt() != null) previous = it.publishedAt();
        }
    }

    @Test
    void paginationStopsOnTheClampedRepeatPage() {
        String page = fixture("bo-dpaafx-liste.html");
        // The live backend re-serves the LAST page forever instead of answering
        // empty - so every page here is identical, exactly like page 2000.
        RoutingFetcher fetcher = new RoutingFetcher(url -> url.contains("/dpa-afx/") ? page : "");

        List<Article> items =
                new BoersenmedienNewsClient(fetcher).newsFor(byIsin("DE0007236101"), 500);

        long wireCalls = fetcher.calls.stream().filter(u -> u.contains("/dpa-afx/")).count();
        assertEquals(2, wireCalls, "page 2 repeats page 1 -> the walk stops instead of looping to the cap");
        assertEquals(4, items.size(), "a clamped repeat must not duplicate items into the result");
    }

    // ---- archive window over the sitemaps ----

    @Test
    void parsesSitemapIndexKeepingOnlyArticleSitemaps() {
        List<String> maps =
                BoersenmedienNewsClient.parseSitemapIndex(fixture("sitemap-index.xml"));

        assertEquals(5, maps.size());
        assertTrue(maps.stream().noneMatch(u -> u.contains("sitemapsites")),
                "the site and symbol sitemaps carry no articles");
        assertTrue(maps.get(0).endsWith("sitemaparticles1.xml"), "oldest first");
        assertTrue(maps.get(maps.size() - 1).endsWith("sitemaparticles131.xml"));
    }

    @Test
    void parsesSitemapArticlesWithPublicationDateAndPlusMarking() {
        List<Article> items = BoersenmedienNewsClient.parseSitemap(
                fixture("sitemap-articles.xml"), BoersenmedienNewsClient.PUBLISHER_DA);

        assertEquals(5, items.size());
        Article first = items.get(0);
        assertTrue(first.title().contains("Siemens Energy"));
        assertEquals(Instant.parse("2026-07-13T05:06:59Z"), first.publishedAt(),
                "news:publication_date is the real date, lastmod is a re-render stamp");
        assertNotNull(first.imageUrl());
        assertTrue(items.stream().anyMatch(
                        it -> BoersenmedienNewsClient.PUBLISHER_DA_PLUS.equals(it.publisher())),
                "the sitemap's der-aktionaer-plus segment marks the wall too");
    }

    @Test
    void nameWindowBinarySearchesTheSitemapsAndCutsToTheWindow() throws Exception {
        String index = fixture("sitemap-index.xml");
        String articles = fixture("sitemap-articles.xml");
        RoutingFetcher fetcher = new RoutingFetcher(url -> {
            if (url.endsWith("/sitemap.xml")) return index;
            if (url.contains("sitemaparticles")) return articles;
            return "";
        });

        List<Article> items = new BoersenmedienNewsClient(fetcher).newsForWindow(
                ResolvedInstrument.ofName("Siemens Energy"),
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-14"), 20);

        assertEquals(2, items.size(), "only the two Siemens Energy pieces inside the window");
        assertTrue(items.stream().allMatch(it -> it.title().contains("Siemens")));
        assertTrue(items.stream().allMatch(
                it -> !it.publishedAt().isBefore(Instant.parse("2026-07-01T00:00:00Z"))
                        && it.publishedAt().isBefore(Instant.parse("2026-07-14T00:00:00Z"))));
        assertTrue(fetcher.calls.stream().anyMatch(u -> u.contains("deraktionaer.de/sitemap.xml")));
        assertTrue(fetcher.calls.stream().anyMatch(u -> u.contains("boerse-online.de/sitemap.xml")),
                "both houses' archives are walked");
    }

    @Test
    void isinWindowWalksTheDatedListsAndAttributesTheQueriedIsin() throws Exception {
        String boWire = fixture("bo-dpaafx-liste.html");
        RoutingFetcher fetcher = new RoutingFetcher(url -> {
            if (url.contains("/dpa-afx/")) return boWire;
            if (url.endsWith("/sitemap.xml")) return "";
            return "";
        });

        List<Article> items = new BoersenmedienNewsClient(fetcher).newsForWindow(
                byIsin("DE0007236101"),
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-08-01"), 20);

        assertFalse(items.isEmpty(), "the dated wire pieces of July land in a July window");
        assertTrue(items.stream().allMatch(it -> "DE0007236101".equals(it.isin())),
                "the ADDRESSING ISIN wins over the badge in the window fan too");
        assertTrue(fetcher.calls.stream().noneMatch(u -> u.contains("/aktien/nachrichten/")),
                "the undated Der Aktionär wire list cannot honour a floor and stays out");
    }

    @Test
    void nameWindowRejectsAnUnusableWindow() throws Exception {
        RoutingFetcher fetcher = new RoutingFetcher(u -> "");
        BoersenmedienNewsClient client = new BoersenmedienNewsClient(fetcher);
        assertTrue(client.newsForWindow(ResolvedInstrument.ofName("Siemens"),
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-07-01"), 10).isEmpty(),
                "an inverted window is not a window");
        assertTrue(client.newsForWindow(ResolvedInstrument.ofName("Siemens"),
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-08-01"), 0).isEmpty());
        assertTrue(fetcher.calls.isEmpty(), "a rejected window must not fetch");
    }

    // ---- the name door ----

    @Test
    void nameFanReadsTheBoerseOnlineSearchPage() {
        String search = fixture("bo-suche.html");
        RoutingFetcher fetcher = new RoutingFetcher(url -> search);

        List<Article> hits = new BoersenmedienNewsClient(fetcher)
                .newsFor(ResolvedInstrument.ofName("Siemens"), 10);

        assertFalse(hits.isEmpty());
        hits.forEach(it -> assertTrue(it.title().toLowerCase().contains("siemens"),
                "the title-precision cut applies on the search page: " + it.title()));
        assertTrue(fetcher.calls.get(0).contains("/suchen?q=Siemens"));
    }

    @Test
    void symbolOnlyStaysSilent() {
        RoutingFetcher fetcher = new RoutingFetcher(u -> "");
        assertTrue(new BoersenmedienNewsClient(fetcher).newsFor(new ResolvedInstrument(
                Optional.empty(), Ticker.parse("SIE"), ""), 10).isEmpty());
        assertTrue(fetcher.calls.isEmpty(),
                "both houses address instruments by ISIN and WKN only");
    }

    // ---- helpers ----

    @Test
    void parsesBothDateShapesInTheHouseTimezone() {
        assertEquals(Instant.parse("2026-07-31T13:21:00Z"),
                BoersenmedienNewsClient.parseListDate("2026-07-31 15:21"));
        assertNull(BoersenmedienNewsClient.parseListDate("Heute"));
        assertNull(BoersenmedienNewsClient.parseListDate(null));
        assertEquals(Instant.parse("2026-07-16T07:00:00Z"),
                BoersenmedienNewsClient.parseGermanDate("<small>16.07.2026, 09:00 ‧ Autor</small>"));
        assertNull(BoersenmedienNewsClient.parseGermanDate("<small>gestern</small>"));
    }

    @Test
    void nameMatchingDropsLegalFormsAndDemandsWholeWords() {
        assertEquals(Set.of("siemens", "healthineers"),
                BoersenmedienNewsClient.significantWords("Siemens Healthineers AG"));
        assertTrue(BoersenmedienNewsClient.significantWords("AG SE").isEmpty());
        Set<String> words = BoersenmedienNewsClient.significantWords("Rheinmetall");
        assertTrue(BoersenmedienNewsClient.titleMatches("Rheinmetall hebt Prognose", words));
        assertFalse(BoersenmedienNewsClient.titleMatches("Der Rheinpegel steigt", words));
        assertEquals("DE0007236101", BoersenmedienNewsClient.normalizeIsin(" de0007236101 "));
        assertNull(BoersenmedienNewsClient.normalizeIsin("SIE"));
    }

    @Test
    void garbageYieldsEmptyListsNotExceptions() {
        assertTrue(BoersenmedienNewsClient.parseBoerseOnlineList(null).isEmpty());
        assertTrue(BoersenmedienNewsClient.parseBoerseOnlineList("<html>nope</html>").isEmpty());
        assertTrue(BoersenmedienNewsClient.parseBoerseOnlineWireList(null).isEmpty());
        assertTrue(BoersenmedienNewsClient
                .parseAktionaerList(null, BoersenmedienNewsClient.PUBLISHER_DA).isEmpty());
        assertTrue(BoersenmedienNewsClient.parseAktionaerWireList(null).isEmpty());
        assertNull(BoersenmedienNewsClient.articleId("https://x/kein-id.html"));
        assertNull(BoersenmedienNewsClient.clean("<span>  </span>"));
    }
}
