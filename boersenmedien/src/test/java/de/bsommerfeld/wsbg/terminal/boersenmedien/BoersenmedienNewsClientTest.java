package de.bsommerfeld.wsbg.terminal.boersenmedien;

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
import java.util.List;
import java.util.Map;
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
 */
class BoersenmedienNewsClientTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = BoersenmedienNewsClientTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Serves a body per URL and records every URL asked for. */
    private static final class FakeFetcher implements WebFetcher {
        final List<String> calls = new ArrayList<>();
        private final Function<String, String> router;

        FakeFetcher(Function<String, String> router) {
            this.router = router;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            calls.add(url);
            String body = router.apply(url);
            return body == null
                    ? new WebResponse(404, "", Map.of())
                    : new WebResponse(200, body, Map.of());
        }
    }

    // ---- Börse Online editorial list ----

    @Test
    void parsesBoerseOnlineEditorialTeasers() throws Exception {
        List<RawNewsItem> items =
                BoersenmedienNewsClient.parseBoerseOnlineList(fixture("bo-nachrichten-liste.html"));

        assertEquals(4, items.size());
        RawNewsItem first = items.get(0);
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
    void dataQuoteBadgeCarriesTheArticlesOwnIsin() throws Exception {
        List<RawNewsItem> items =
                BoersenmedienNewsClient.parseBoerseOnlineList(fixture("bo-nachrichten-liste.html"));

        assertTrue(items.stream().allMatch(it -> "DE0007236101".equals(it.isin())),
                "the badge ISIN is read straight off data-quote");
    }

    // ---- dpa-AFX wire list ----

    @Test
    void parsesWireTeasersWithoutImageOrTeaser() throws Exception {
        List<RawNewsItem> items =
                BoersenmedienNewsClient.parseBoerseOnlineWireList(fixture("bo-dpaafx-liste.html"));

        assertEquals(4, items.size());
        RawNewsItem first = items.get(0);
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
    void parsesAktionaerTeasersWithGermanDateAndSummary() throws Exception {
        List<RawNewsItem> items = BoersenmedienNewsClient.parseAktionaerList(
                fixture("da-artikel-liste.html"), BoersenmedienNewsClient.PUBLISHER_DA);

        assertEquals(4, items.size(), "3 free teasers + 1 Plus teaser in the trimmed capture");
        RawNewsItem first = items.get(0);
        assertTrue(first.title().startsWith("DAX kommt nicht vom Fleck"));
        assertEquals("DE0006202005", first.isin(),
                "data-quote is the article's PRIMARY instrument, not the queried one");
        assertNotNull(first.summary());
        assertTrue(first.summary().startsWith("Der DAX kommt zum Handelsstart"));
        // 16.07.2026, 09:00 Europe/Berlin = 07:00 UTC (CEST)
        assertEquals(Instant.parse("2026-07-16T07:00:00Z"), first.publishedAt());
    }

    @Test
    void plusArticlesAreMarkedViaPathSegmentAndViaCssClass() throws Exception {
        String html = fixture("da-artikel-liste.html");
        assertTrue(html.contains("/artikel/deraktionaerplus/"), "fixture carries the path tell");
        assertTrue(html.contains("plus-article"), "fixture carries the CSS tell");

        List<RawNewsItem> items = BoersenmedienNewsClient.parseAktionaerList(
                html, BoersenmedienNewsClient.PUBLISHER_DA);
        List<RawNewsItem> plus = items.stream()
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
    void parsesAktionaerWireListWhichCarriesNoDate() throws Exception {
        List<RawNewsItem> items =
                BoersenmedienNewsClient.parseAktionaerWireList(fixture("da-nachrichten-liste.html"));

        assertEquals(4, items.size());
        RawNewsItem first = items.get(0);
        assertEquals("ROUNDUP 2/Schwache Diagnostik-Sparte: Siemens Healthineers senkt Umsatzausblick",
                first.title());
        assertNull(first.publishedAt(), "this list prints no timestamp at all");
        assertEquals("DE0007236101", first.isin(), "taken from the page's symbol header");
        assertEquals("bm-542040", first.uuid());
    }

    // ---- merge / dedupe across the four legs ----

    @Test
    void mergeDedupesTheSameWirePieceAcrossBothHousesAndKeepsTheDatedOne() throws Exception {
        List<RawNewsItem> bo =
                BoersenmedienNewsClient.parseBoerseOnlineWireList(fixture("bo-dpaafx-liste.html"));
        List<RawNewsItem> da =
                BoersenmedienNewsClient.parseAktionaerWireList(fixture("da-nachrichten-liste.html"));

        List<RawNewsItem> all = new ArrayList<>(bo);
        all.addAll(da);
        List<RawNewsItem> merged = BoersenmedienNewsClient.dedupe(all);

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
    void isinFanMergesAllFourLegsAndAttributesTheQueriedIsin() throws Exception {
        String boEditorial = fixture("bo-nachrichten-liste.html");
        String boWire = fixture("bo-dpaafx-liste.html");
        String daEditorial = fixture("da-artikel-liste.html");
        String daWire = fixture("da-nachrichten-liste.html");
        FakeFetcher fetcher = new FakeFetcher(url -> {
            if (!url.contains("/1")) return "";            // page 2+ is empty here
            if (url.contains("boerse-online.de/aktie/") && url.contains("/nachrichten/")) return boEditorial;
            if (url.contains("boerse-online.de/aktie/") && url.contains("/dpa-afx/")) return boWire;
            if (url.contains("/aktien/artikel/")) return daEditorial;
            if (url.contains("/aktien/nachrichten/")) return daWire;
            return null;
        });

        List<RawNewsItem> items =
                new BoersenmedienNewsClient(fetcher).newsForIsin("DE0007236101", 50);

        assertEquals(12, items.size(), "4 + 4 + 4 + 4 with the 4 wire duplicates collapsed");
        assertTrue(items.stream().allMatch(it -> "DE0007236101".equals(it.isin())),
                "the ADDRESSING ISIN wins over the badge - a round-up must stay attributed");
        assertTrue(fetcher.calls.stream().anyMatch(u -> u.contains("/aktie/x-DE0007236101/nachrichten/1")));
        assertTrue(fetcher.calls.stream().anyMatch(u -> u.contains("/aktie/x-DE0007236101/dpa-afx/1")));
        assertTrue(fetcher.calls.stream().anyMatch(u -> u.contains("/aktien/artikel/x-DE0007236101/1")));
        assertTrue(fetcher.calls.stream().anyMatch(u -> u.contains("/aktien/nachrichten/x-DE0007236101/1")));

        Instant previous = null;
        for (RawNewsItem it : items) {
            if (it.publishedAt() != null && previous != null) {
                assertFalse(it.publishedAt().isAfter(previous), "freshest first");
            }
            if (it.publishedAt() != null) previous = it.publishedAt();
        }
    }

    @Test
    void paginationStopsOnTheClampedRepeatPage() throws Exception {
        String page = fixture("bo-dpaafx-liste.html");
        // The live backend re-serves the LAST page forever instead of answering
        // empty - so every page here is identical, exactly like page 2000.
        FakeFetcher fetcher = new FakeFetcher(url -> url.contains("/dpa-afx/") ? page : "");

        List<RawNewsItem> items =
                new BoersenmedienNewsClient(fetcher).newsForIsin("DE0007236101", 500);

        long wireCalls = fetcher.calls.stream().filter(u -> u.contains("/dpa-afx/")).count();
        assertEquals(2, wireCalls, "page 2 repeats page 1 -> the walk stops instead of looping to the cap");
        assertEquals(4, items.size(), "a clamped repeat must not duplicate items into the result");
    }

    // ---- sitemaps ----

    @Test
    void parsesSitemapIndexKeepingOnlyArticleSitemaps() throws Exception {
        List<String> maps =
                BoersenmedienNewsClient.parseSitemapIndex(fixture("sitemap-index.xml"));

        assertEquals(5, maps.size());
        assertTrue(maps.stream().noneMatch(u -> u.contains("sitemapsites")),
                "the site and symbol sitemaps carry no articles");
        assertTrue(maps.get(0).endsWith("sitemaparticles1.xml"), "oldest first");
        assertTrue(maps.get(maps.size() - 1).endsWith("sitemaparticles131.xml"));
    }

    @Test
    void parsesSitemapArticlesWithPublicationDateAndPlusMarking() throws Exception {
        List<RawNewsItem> items = BoersenmedienNewsClient.parseSitemap(
                fixture("sitemap-articles.xml"), BoersenmedienNewsClient.PUBLISHER_DA);

        assertEquals(5, items.size());
        RawNewsItem first = items.get(0);
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
        FakeFetcher fetcher = new FakeFetcher(url -> {
            if (url.endsWith("/sitemap.xml")) return index;
            if (url.contains("sitemaparticles")) return articles;
            return "";
        });

        List<RawNewsItem> items = new BoersenmedienNewsClient(fetcher)
                .newsForNameWindow("Siemens Energy", null, "2026-07-01", "2026-07-14", 20);

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
    void nameWindowRejectsAnUnusableWindow() {
        BoersenmedienNewsClient client = new BoersenmedienNewsClient(new FakeFetcher(u -> ""));
        assertTrue(client.newsForNameWindow("Siemens", null, "nonsense", "2026-08-01", 10).isEmpty());
        assertTrue(client.newsForNameWindow("Siemens", null, "2026-08-01", "2026-07-01", 10).isEmpty(),
                "an inverted window is not a window");
        assertTrue(client.newsForNameWindow("Siemens", null, "2026-07-01", "2026-08-01", 0).isEmpty());
    }

    // ---- general stream ----

    @Test
    void sectionServesTheGeneralListAndCachesIt() throws Exception {
        String page = fixture("bo-nachrichten-liste.html");
        FakeFetcher fetcher = new FakeFetcher(url -> page);
        BoersenmedienNewsClient client = new BoersenmedienNewsClient(fetcher);

        List<RawNewsItem> first = client.section("bo-alle", 10);
        List<RawNewsItem> again = client.section("bo-alle", 10);

        assertEquals(4, first.size());
        assertEquals(first, again);
        assertEquals(1, fetcher.calls.size(), "the second call is served from the TTL pool");
        assertEquals("https://www.boerse-online.de/nachrichten/1", fetcher.calls.get(0));
        assertTrue(client.section("gibt-es-nicht", 10).isEmpty(),
                "an unknown section key must not guess a URL");
    }

    @Test
    void latestPoolsBothHousesAndTheWire() throws Exception {
        String boEditorial = fixture("bo-nachrichten-liste.html");
        String boWire = fixture("bo-dpaafx-liste.html");
        String daEditorial = fixture("da-artikel-liste.html");
        FakeFetcher fetcher = new FakeFetcher(url -> {
            if (url.contains("/nachrichten/dpa-afx/")) return boWire;
            if (url.contains("boerse-online.de/nachrichten/")) return boEditorial;
            if (url.contains("deraktionaer.de/artikel/aktien/")) return daEditorial;
            return null;
        });
        BoersenmedienNewsClient client = new BoersenmedienNewsClient(fetcher);

        List<RawNewsItem> items = client.latest(100);

        assertEquals(12, items.size(), "4 + 4 + 4, nothing shared between these three lists");
        assertTrue(items.stream().anyMatch(
                it -> BoersenmedienNewsClient.PUBLISHER_BO.equals(it.publisher())));
        assertTrue(items.stream().anyMatch(
                it -> BoersenmedienNewsClient.PUBLISHER_DA.equals(it.publisher())));
        assertEquals(3, fetcher.calls.size(), "one fetch per pooled section, no more");

        client.latest(100);
        assertEquals(3, fetcher.calls.size(), "a second pass rides the same pool");
    }

    @Test
    void everySectionKeyResolvesToItsHouseAndParser() {
        assertEquals(16, BoersenmedienNewsClient.SECTIONS.size());
        assertTrue(BoersenmedienNewsClient.SECTIONS.values().stream()
                .allMatch(u -> u.startsWith(BoersenmedienNewsClient.HOST_BO)
                        || u.startsWith(BoersenmedienNewsClient.HOST_DA)));
        assertTrue(BoersenmedienNewsClient.LATEST_SECTIONS.stream()
                .allMatch(BoersenmedienNewsClient.SECTIONS::containsKey));
    }

    @Test
    void sitemapFirehoseIsReachableFromOutside() throws Exception {
        String index = fixture("sitemap-index.xml");
        String articles = fixture("sitemap-articles.xml");
        BoersenmedienNewsClient client = new BoersenmedienNewsClient(new FakeFetcher(
                url -> url.endsWith("/sitemap.xml") ? index : articles));

        List<String> maps = client.sitemapIndex(BoersenmedienNewsClient.URL_DA_SITEMAP_INDEX);
        assertEquals(5, maps.size());
        assertEquals(5, client.articlesFromSitemap(maps.get(0)).size());
    }

    // ---- name search ----

    @Test
    void nameFanReadsTheBoerseOnlineSearchPage() throws Exception {
        String search = fixture("bo-suche.html");
        FakeFetcher fetcher = new FakeFetcher(url -> search);

        List<RawNewsItem> items = new BoersenmedienNewsClient(fetcher).newsForName("Siemens", 10);

        assertFalse(items.isEmpty());
        assertTrue(items.stream().allMatch(it -> it.title().toLowerCase().contains("siemens")),
                "the title-precision cut keeps the fan honest");
        assertEquals("https://www.boerse-online.de/suchen?q=Siemens", fetcher.calls.get(0));
        assertTrue(items.stream().noneMatch(it -> it.link().contains("/aktie/")),
                "the instrument hits on the same page are not headlines");
    }

    /**
     * The Der Aktionär story typeahead is documented but not wired (it needs a
     * POST body, the transport seam is GET-only). Its fragment uses the same
     * teaser shape as the lists, so the day a POST joins the seam this parser
     * already reads it - guarded here so a markup drift is caught meanwhile.
     */
    @Test
    void theUnwiredStorySearchFragmentIsAlreadyParseable() throws Exception {
        List<RawNewsItem> items = BoersenmedienNewsClient.parseAktionaerList(
                fixture("da-searchstories.html"), BoersenmedienNewsClient.PUBLISHER_DA);

        assertEquals(3, items.size(), "the typeahead answers a handful, not a page");
        assertTrue(items.get(0).title().startsWith("DAX nimmt Kurs aufs Rekordhoch"),
                "the fragment titles its teasers with <strong>, not <h2>");
        assertEquals("DE0006969603", items.get(0).isin());
        assertEquals(Instant.parse("2026-07-31T07:00:00Z"), items.get(0).publishedAt());
        assertTrue(BoersenmedienNewsClient.URL_DA_SEARCH_STORIES.contains("/api/remote/searchStories"));
    }

    @Test
    void symbolFanStaysSilent() {
        assertTrue(new BoersenmedienNewsClient(new FakeFetcher(u -> ""))
                .newsFor("SIE", 10).isEmpty(), "no ticker-addressed URL exists on either house");
    }

    // ---- robustness ----

    @Test
    void garbageYieldsEmptyListsNotExceptions() {
        for (String junk : new String[]{null, "", "   ", "<html>totally different</html>",
                "{\"not\":\"html\"}", "<article class=\"article-list-item\">unclosed"}) {
            assertTrue(BoersenmedienNewsClient.parseBoerseOnlineList(junk).isEmpty());
            assertTrue(BoersenmedienNewsClient.parseBoerseOnlineWireList(junk).isEmpty());
            assertTrue(BoersenmedienNewsClient.parseAktionaerList(
                    junk, BoersenmedienNewsClient.PUBLISHER_DA).isEmpty());
            assertTrue(BoersenmedienNewsClient.parseAktionaerWireList(junk).isEmpty());
            assertTrue(BoersenmedienNewsClient.parseSitemapIndex(junk).isEmpty());
            assertTrue(BoersenmedienNewsClient.parseSitemap(
                    junk, BoersenmedienNewsClient.PUBLISHER_BO).isEmpty());
        }
        assertTrue(BoersenmedienNewsClient.dedupe(List.of()).isEmpty());
    }

    @Test
    void nonIsinInputNeverReachesTheNetwork() {
        FakeFetcher fetcher = new FakeFetcher(u -> "");
        BoersenmedienNewsClient client = new BoersenmedienNewsClient(fetcher);

        assertTrue(client.newsForIsin("SIE", 10).isEmpty());
        assertTrue(client.newsForIsin("DE00072361", 10).isEmpty(), "too short");
        assertTrue(client.newsForIsin(null, 10).isEmpty());
        assertTrue(fetcher.calls.isEmpty(), "a malformed ISIN must not cost a fetch");
        assertEquals("DE0007236101", BoersenmedienNewsClient.normalizeIsin(" de0007236101 "));
    }

    // ---- date + text helpers ----

    @Test
    void parsesBothDateShapesInTheHouseTimezone() {
        assertEquals(Instant.parse("2026-07-31T13:21:00Z"),
                BoersenmedienNewsClient.parseListDate("2026-07-31 15:21"),
                "the <time datetime> attribute is local Berlin time (CEST)");
        assertEquals(Instant.parse("2026-01-15T08:21:00Z"),
                BoersenmedienNewsClient.parseListDate("2026-01-15 09:21"),
                "and CET in winter");
        assertEquals(Instant.parse("2026-07-16T07:00:00Z"),
                BoersenmedienNewsClient.parseGermanDate("<small>16.07.2026, 09:00 &#8231; Autor</small>"),
                "Der Aktionär prints dd.MM.yyyy");
        assertEquals(Instant.parse("2026-07-13T05:06:59Z"),
                BoersenmedienNewsClient.parseIsoDate("2026-07-13T05:06:59+00:00"));
        assertNull(BoersenmedienNewsClient.parseListDate("Heute"),
                "the visible label is unusable - only the attribute counts");
        assertNull(BoersenmedienNewsClient.parseGermanDate("<small>irgendwann</small>"));
        assertNull(BoersenmedienNewsClient.parseIsoDate(null));
    }

    @Test
    void cleanFoldsTheHousesTypographyBackIntoPlainText() {
        assertEquals("Siemens-Aktie Prognose",
                BoersenmedienNewsClient.clean("Siemens&amp;#8209;Aktie   Prognose"),
                "the non-breaking hyphen in company names must not defeat a match");
        assertEquals("Über 100 Jahre",
                BoersenmedienNewsClient.clean("&#xDC;ber 100 Jahre"));
        assertEquals("A B", BoersenmedienNewsClient.clean("<strong>A</strong> B"));
        assertNull(BoersenmedienNewsClient.clean("   "));
    }

    @Test
    void nameMatchingDropsLegalFormsAndDemandsWholeWords() {
        assertEquals(Set.of("siemens", "energy"),
                BoersenmedienNewsClient.significantWords("Siemens Energy AG"));
        assertTrue(BoersenmedienNewsClient.significantWords("AG SE Aktie").isEmpty());

        Set<String> words = BoersenmedienNewsClient.significantWords("Rheinmetall");
        assertTrue(BoersenmedienNewsClient.titleMatches("Rheinmetall hebt Prognose", words));
        assertFalse(BoersenmedienNewsClient.titleMatches("Der Rheinpegel steigt", words),
                "stem overlap must not count as a hit");
        assertFalse(BoersenmedienNewsClient.titleMatches(null, words));
    }
}
