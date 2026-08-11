package de.bsommerfeld.wsbg.terminal.web.impl.sources.welt;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport.FakeWebFetcher;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser, precision and fan tests against live-captured WELT fixtures
 * (2026-08-02). Ported from the module world; the general stream legs did not
 * migrate, the archive/window leg returned as {@code ArchiveSource}.
 */
class WeltNewsClientTest {

    private static String fixture(String name) {
        return SourceTestSupport.fixture("sources/welt/" + name);
    }

    private static FakeWebFetcher fullFetcher() {
        return new FakeWebFetcher()
                .on("section/wirtschaft.rss", fixture("welt-wirtschaft-rss.xml"))
                .on("latest.rss", fixture("welt-latest-rss.xml"))
                .on("/api/search/", fixture("welt-search-rheinmetall.json"));
    }

    private static ResolvedInstrument named(String name) {
        return ResolvedInstrument.ofName(name);
    }

    // ---- feed parser ----

    @Test
    void parsesFeedItemsWithGuidIdentityTeaserAndAgencyPublisher() {
        List<Article> items = WeltNewsClient.parseFeed(fixture("welt-latest-rss.xml"));

        assertFalse(items.isEmpty());
        Article first = items.get(0);
        assertEquals("6a6f91c0ef632fe6ede68d95", first.uuid(),
                "the guid is the identity, not the permalink");
        assertTrue(first.title().startsWith("Koffer-Leiche in Athen"));
        assertEquals("WELT (dpa/ceb)", first.publisher(),
                "dc:source carries the agency chain into the publisher");
        assertFalse(WeltNewsClient.isGated(first));
        assertTrue(first.link().startsWith("https://www.welt.de/"));
        assertNotNull(first.summary());
        assertNotNull(first.imageUrl(), "media:content carries the teaser image");
        assertEquals(Instant.parse("2026-08-02T19:13:41Z"), first.publishedAt());
        assertTrue(first.relatedTickers().isEmpty(), "WELT tags no tickers");
        assertNull(first.isin(), "WELT tags no ISINs");
    }

    @Test
    void premiumFeedItemIsMarkedAsPlusButKeepsItsHeadline() {
        List<Article> items = WeltNewsClient.parseFeed(fixture("welt-wirtschaft-rss.xml"));

        Article gated = items.get(0);
        assertEquals("Chinas Weg zur neuen KI-Weltordnung", gated.title());
        assertEquals(WeltNewsClient.PUBLISHER_GATED, gated.publisher(),
                "welt:premium=true and no dc:source");
        assertTrue(WeltNewsClient.isGated(gated));
        assertTrue(gated.link().contains("/plus"), "gated URLs carry the /plus<id>/ shape");
        assertFalse(gated.title().isBlank(), "the headline survives the wall");
    }

    @Test
    void videoEntriesAreDroppedFromFeeds() {
        String xml = fixture("welt-wirtschaft-rss.xml");
        assertTrue(xml.contains("<welt:subType>video</welt:subType>"),
                "fixture must contain video entries");

        List<Article> items = WeltNewsClient.parseFeed(xml);

        assertEquals(4, items.size(), "2 of the 6 fixture items are video");
        assertTrue(items.stream().noneMatch(it -> it.title().contains("Wasserzeichen")),
                "a player page carries no article text");
    }

    @Test
    void feedGarbageYieldsEmptyListNotException() {
        assertTrue(WeltNewsClient.parseFeed("<rss><channel><item></item>").isEmpty());
        assertTrue(WeltNewsClient.parseFeed("not xml at all").isEmpty());
        assertTrue(WeltNewsClient.parseFeed("").isEmpty());
        assertTrue(WeltNewsClient.parseFeed(null).isEmpty());
    }

    // ---- search parser ----

    @Test
    void parsesSearchResults() {
        List<Article> items =
                WeltNewsClient.parseSearch(fixture("welt-search-rheinmetall.json"));

        assertEquals(8, items.size());
        Article first = items.get(0);
        assertEquals("6a6eeddddd06dd5ef2e41ffd", first.uuid());
        assertNotNull(first.title());
        assertTrue(first.link().startsWith("https://www.welt.de/"));
        assertEquals(Instant.parse("2026-08-02T14:01:56.910Z"), first.publishedAt());
        assertNotNull(first.summary());
        assertNotNull(first.imageUrl());
        assertTrue(items.stream().anyMatch(it -> it.summary() == null),
                "the fixture carries an item without an intro - must not blow up");
    }

    @Test
    void searchPremiumIsDetectedOverBothSignals() {
        List<Article> items =
                WeltNewsClient.parseSearch(fixture("welt-search-rheinmetall.json"));

        List<Article> gated = items.stream().filter(WeltNewsClient::isGated).toList();
        assertEquals(3, gated.size(), "3 of the 8 fixture items are WELT Plus");
        gated.forEach(it -> {
            assertTrue(it.link().contains("/plus"), "flag and URL shape agree: " + it.link());
            assertFalse(it.title().isBlank(), "the headline survives the wall");
        });
        items.stream().filter(it -> !WeltNewsClient.isGated(it))
                .forEach(it -> assertTrue(it.link().contains("/article"),
                        "free pieces carry /article<id>/: " + it.link()));
    }

    @Test
    void premiumFlagAndPlusUrlAreIndependentSignals() {
        String plus = "https://www.welt.de/wirtschaft/plus6a6dd2ccef632fe6ede67ae0/chinas-weg.html";
        String free = "https://www.welt.de/wirtschaft/article6a6c7acf/rheinmetall-fregatte.html";

        assertTrue(WeltNewsClient.isPremiumUrl(plus));
        assertFalse(WeltNewsClient.isPremiumUrl(free));
        assertFalse(WeltNewsClient.isPremiumUrl(null));

        assertTrue(WeltNewsClient.isPremium("true", free), "the flag alone is enough");
        assertTrue(WeltNewsClient.isPremium("false", plus), "the URL alone is enough");
        assertTrue(WeltNewsClient.isPremium(null, plus));
        assertFalse(WeltNewsClient.isPremium("false", free));

        assertEquals("WELT Plus", WeltNewsClient.publisherFor(true, null));
        assertEquals("WELT (dpa/ceb)", WeltNewsClient.publisherFor(false, "dpa/ceb"));
        assertEquals("WELT Plus (Reuters)", WeltNewsClient.publisherFor(true, "Reuters"));
    }

    @Test
    void advertorialsSurviveButAreFlaggedSponsored() {
        List<Article> items =
                WeltNewsClient.parseSearch(fixture("welt-search-rheinmetall.json"));

        assertTrue(items.stream().anyMatch(Article::sponsored),
                "subType advertorial must reach the pool as paid placement");
    }

    @Test
    void searchGarbageYieldsEmptyListNotException() {
        assertTrue(WeltNewsClient.parseSearch("{\"items\":\"nope\"}").isEmpty());
        assertTrue(WeltNewsClient.parseSearch("{\"error\":\"invalid request\"}").isEmpty());
        assertTrue(WeltNewsClient.parseSearch("<html>403</html>").isEmpty());
        assertTrue(WeltNewsClient.parseSearch("").isEmpty());
        assertTrue(WeltNewsClient.parseSearch(null).isEmpty());
    }

    // ---- dates ----

    @Test
    void parsesBothDateShapes() {
        assertEquals(Instant.parse("2026-08-02T15:52:58Z"),
                WeltNewsClient.parseRssDate("Sun, 02 Aug 2026 15:52:58 GMT"));
        assertEquals(Instant.parse("2026-08-02T14:01:56.910Z"),
                WeltNewsClient.parseSearchDate("2026-08-02T14:01:56.910Z"));
        assertNull(WeltNewsClient.parseRssDate("nonsense"));
        assertNull(WeltNewsClient.parseSearchDate("nonsense"));
        assertNull(WeltNewsClient.parseSearchDate(null));
    }

    // ---- precision filter ----

    @Test
    void significantWordsDropLegalFormsAndShortTokens() {
        assertEquals(Set.of("siemens", "energy"),
                WeltNewsClient.significantWords("Siemens Energy AG"));
        assertEquals(Set.of("rheinmetall"), WeltNewsClient.significantWords("Rheinmetall AG"));
        assertTrue(WeltNewsClient.significantWords("AG SE").isEmpty());
        assertTrue(WeltNewsClient.significantWords(null).isEmpty());
    }

    @Test
    void titleMatchNeedsAWholeWord() {
        Set<String> rheinmetall = WeltNewsClient.significantWords("Rheinmetall");
        assertTrue(WeltNewsClient.titleMatches(
                "Rheinmetall modernisiert Fregatte „Bayern“", rheinmetall));
        assertFalse(WeltNewsClient.titleMatches(
                "Historischer Tiefstand - Rheinpegel in Köln sinkt auf 67 Zentimeter",
                rheinmetall),
                "WELT's stem search drags Rheinpegel in - the cut must throw it out");

        Set<String> bayer = WeltNewsClient.significantWords("Bayer");
        assertTrue(WeltNewsClient.titleMatches("Bayer hebt die Prognose an", bayer));
        assertFalse(WeltNewsClient.titleMatches("Bayern gewinnt gegen Schalke", bayer));
    }

    // ---- headers ----

    @Test
    void searchHeadersCarryTheCallSiteHalfOfTheAkamaiHandshake() {
        Map<String, String> h = new WeltNewsClient(new FakeWebFetcher()).searchHeaders();

        assertEquals("empty", h.get("Sec-Fetch-Dest"));
        assertEquals("cors", h.get("Sec-Fetch-Mode"));
        assertEquals("same-origin", h.get("Sec-Fetch-Site"));
        assertTrue(h.containsKey("Accept-Language"));
        assertTrue(h.get("Accept").startsWith("application/json"));
        assertEquals("https://www.welt.de/suche/", h.get("Referer"));
        // The identity half (User-Agent, sec-ch-ua, Accept-Encoding) is the
        // transport's job now - it must NOT be duplicated at the call site.
        assertFalse(h.containsKey("User-Agent"));
        assertFalse(h.containsKey("sec-ch-ua"));
        assertFalse(h.containsKey("Accept-Encoding"));
    }

    // ---- fan ----

    @Test
    void hardKeysAloneStaySilent() {
        FakeWebFetcher fetcher = fullFetcher();
        WeltNewsClient client = new WeltNewsClient(fetcher);

        ResolvedInstrument hardOnly = new ResolvedInstrument(
                Isin.parse("DE0007030009"), Ticker.parse("RHM.DE"), "");
        assertTrue(client.newsFor(hardOnly, 5).isEmpty(),
                "WELT's ISIN answer is the same filler a nonsense term produces");
        assertEquals(0, fetcher.total());
    }

    @Test
    void nameFanCutsWeltsFuzzyPaddingAndDedupes() throws Exception {
        WeltNewsClient client = new WeltNewsClient(fullFetcher());

        List<Article> hits = client.newsFor(named("Rheinmetall"), 50);

        assertFalse(hits.isEmpty());
        assertEquals(hits.size(), hits.stream().map(Article::link).distinct().count(),
                "merge must dedupe by link");
        hits.forEach(it -> assertTrue(
                (it.title() + " " + (it.summary() == null ? "" : it.summary()))
                        .toLowerCase().contains("rheinmetall"),
                "precision filter let padding through: " + it.title()));
        assertTrue(hits.stream().noneMatch(it -> it.title().contains("Rente mit 63")),
                "WELT prepends today's unrelated articles to every query");
    }

    @Test
    void nameFanSortsNewestFirst() throws Exception {
        List<Article> hits =
                new WeltNewsClient(fullFetcher()).newsFor(named("Rheinmetall"), 50);

        Instant previous = null;
        for (Article it : hits) {
            if (previous != null && it.publishedAt() != null) {
                assertFalse(it.publishedAt().isAfter(previous), "not newest-first");
            }
            if (it.publishedAt() != null) previous = it.publishedAt();
        }
    }

    @Test
    void emptyNameNeverReachesTheNetwork() throws Exception {
        FakeWebFetcher fetcher = fullFetcher();
        WeltNewsClient client = new WeltNewsClient(fetcher);

        assertTrue(client.newsFor(named("  "), 5).isEmpty());
        assertTrue(client.newsFor(named("AG"), 5).isEmpty(), "no significant word left");
        assertEquals(0, fetcher.total());
    }

    @Test
    void searchOffsetNeverPassesTheWall() throws Exception {
        FakeWebFetcher fetcher = fullFetcher();
        new WeltNewsClient(fetcher).newsFor(named("Rheinmetall"), 5);

        List<String> searches = fetcher.callsContaining("/api/search/");
        assertEquals(1, searches.size());
        int off = Integer.parseInt(searches.get(0).replaceAll(".*offset=(\\d+).*", "$1"));
        assertTrue(off <= WeltNewsClient.MAX_OFFSET, "offset 195+ answers HTTP 400");
        assertTrue(searches.get(0).contains("type=article"));
    }

    // ---- archive window ----

    @Test
    void restrictByPicksTheNarrowestCoveringWindow() {
        Instant now = Instant.now();
        assertEquals("d1", WeltNewsClient.restrictByFor(now.minus(Duration.ofHours(5))));
        assertEquals("w1", WeltNewsClient.restrictByFor(now.minus(Duration.ofDays(4))));
        assertEquals("m1", WeltNewsClient.restrictByFor(now.minus(Duration.ofDays(20))));
        assertEquals("y1", WeltNewsClient.restrictByFor(now.minus(Duration.ofDays(200))));
        assertEquals("y2", WeltNewsClient.restrictByFor(now.minus(Duration.ofDays(500))));
        assertEquals("y5", WeltNewsClient.restrictByFor(now.minus(Duration.ofDays(1500))));
        assertNull(WeltNewsClient.restrictByFor(now.minus(Duration.ofDays(4000))),
                "a window beyond y5 walks the unrestricted archive");
        assertNull(WeltNewsClient.restrictByFor(null));
    }

    @Test
    void archiveWindowKeepsOnlyItemsInsideTheWindow() throws Exception {
        WeltNewsClient client = new WeltNewsClient(fullFetcher());

        List<Article> hits = client.newsForWindow(named("Rheinmetall"),
                LocalDate.parse("2013-01-01"), LocalDate.parse("2020-01-01"), 20);

        assertEquals(2, hits.size(), "the 2019 and the 2013 piece");
        hits.forEach(it -> {
            assertTrue(it.publishedAt().isBefore(Instant.parse("2020-01-01T00:00:00Z")));
            assertFalse(it.publishedAt().isBefore(Instant.parse("2013-01-01T00:00:00Z")));
            assertTrue(it.title().contains("Rheinmetall"));
        });
        assertTrue(hits.get(0).publishedAt().isAfter(hits.get(1).publishedAt()),
                "newest first");
    }

    @Test
    void archiveWindowRejectsBadBounds() throws Exception {
        FakeWebFetcher fetcher = new FakeWebFetcher();
        WeltNewsClient client = new WeltNewsClient(fetcher);
        assertTrue(client.newsForWindow(named("Rheinmetall"),
                null, LocalDate.parse("2026-08-01"), 10).isEmpty());
        assertTrue(client.newsForWindow(named("Rheinmetall"),
                LocalDate.parse("2026-07-01"), null, 10).isEmpty());
        assertTrue(client.newsForWindow(named("Rheinmetall"),
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-07-01"), 10)
                .isEmpty(), "inverted window");
        assertEquals(0, fetcher.total(), "a broken window costs no fetch");
    }

    @Test
    void archiveWalksEveryReachableOffsetAndNeverPastTheWall() throws Exception {
        FakeWebFetcher fetcher = fullFetcher();
        new WeltNewsClient(fetcher).newsForWindow(named("Rheinmetall"),
                LocalDate.parse("2013-01-01"), LocalDate.parse("2020-01-01"), 20);

        List<String> searches = fetcher.callsContaining("/api/search/");
        assertEquals(WeltNewsClient.ARCHIVE_OFFSETS.length, searches.size());
        searches.forEach(u -> {
            int off = Integer.parseInt(u.replaceAll(".*offset=(\\d+).*", "$1"));
            assertTrue(off <= WeltNewsClient.MAX_OFFSET, "offset 195+ answers HTTP 400: " + u);
            assertFalse(u.contains("restrictBy"),
                    "a pre-2013 lower bound is beyond y5 - the walk is unrestricted");
        });
    }

    @Test
    void sourceNameIsStable() {
        WeltNewsClient client = new WeltNewsClient(new FakeWebFetcher());
        assertEquals("welt", client.sourceName());
        assertFalse(client.socialSentiment());
        assertEquals("DE", client.origin().sphere());
    }
}
