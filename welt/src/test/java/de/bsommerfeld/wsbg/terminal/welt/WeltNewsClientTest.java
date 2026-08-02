package de.bsommerfeld.wsbg.terminal.welt;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser, precision and fan tests against live-captured WELT fixtures (2026-08-02). */
class WeltNewsClientTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = WeltNewsClientTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- feed parser ----

    @Test
    void parsesFeedItemsWithGuidIdentityTeaserAndAgencyPublisher() throws Exception {
        List<RawNewsItem> items = WeltNewsClient.parseFeed(fixture("welt-latest-rss.xml"));

        assertFalse(items.isEmpty());
        RawNewsItem first = items.get(0);
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
    void premiumFeedItemIsMarkedAsPlusButKeepsItsHeadline() throws Exception {
        List<RawNewsItem> items = WeltNewsClient.parseFeed(fixture("welt-wirtschaft-rss.xml"));

        RawNewsItem gated = items.get(0);
        assertEquals("Chinas Weg zur neuen KI-Weltordnung", gated.title());
        assertEquals(WeltNewsClient.PUBLISHER_GATED, gated.publisher(),
                "welt:premium=true and no dc:source");
        assertTrue(WeltNewsClient.isGated(gated));
        assertTrue(gated.link().contains("/plus"), "gated URLs carry the /plus<id>/ shape");
        assertFalse(gated.title().isBlank(), "the headline survives the wall");
    }

    @Test
    void videoEntriesAreDroppedFromFeeds() throws Exception {
        String xml = fixture("welt-wirtschaft-rss.xml");
        assertTrue(xml.contains("<welt:subType>video</welt:subType>"),
                "fixture must contain video entries");

        List<RawNewsItem> items = WeltNewsClient.parseFeed(xml);

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
    void parsesSearchResults() throws Exception {
        List<RawNewsItem> items =
                WeltNewsClient.parseSearch(fixture("welt-search-rheinmetall.json"));

        assertEquals(8, items.size());
        RawNewsItem first = items.get(0);
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
    void searchPremiumIsDetectedOverBothSignals() throws Exception {
        List<RawNewsItem> items =
                WeltNewsClient.parseSearch(fixture("welt-search-rheinmetall.json"));

        List<RawNewsItem> gated = items.stream().filter(WeltNewsClient::isGated).toList();
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
    void advertorialsSurviveButAreFlaggedSponsored() throws Exception {
        List<RawNewsItem> items =
                WeltNewsClient.parseSearch(fixture("welt-search-rheinmetall.json"));

        assertTrue(items.stream().anyMatch(RawNewsItem::sponsored),
                "subType advertorial must reach the aggregator as paid placement");
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
        assertNull(WeltNewsClient.startOfDay("2026-13-99"));
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
                "beyond y5 the full archive is the only option");
        assertNull(WeltNewsClient.restrictByFor(null));
        assertTrue(WeltNewsClient.RESTRICT_BY.containsAll(
                List.of("d1", "w1", "m1", "y1", "y2", "y5")));
    }

    // ---- headers ----

    @Test
    void searchHeadersCarryTheFullChromeSet() {
        Map<String, String> h = new WeltNewsClient(new FakeFetcher(Map.of())).searchHeaders();

        assertTrue(h.containsKey("Sec-Fetch-Dest"));
        assertTrue(h.containsKey("Sec-Fetch-Mode"));
        assertTrue(h.containsKey("Sec-Fetch-Site"));
        assertTrue(h.containsKey("Accept-Language"));
        assertEquals("identity", h.get("Accept-Encoding"),
                "Akamai 403s without Accept-Encoding, and the JDK client decompresses nothing");
        assertTrue(h.containsKey("sec-ch-ua-mobile"));
        assertTrue(h.containsKey("sec-ch-ua-platform"));

        String ua = h.get("User-Agent");
        assertTrue(ua.contains("Chrome/"), "sec-ch-ua only exists on Chromium: " + ua);
        String major = ua.replaceAll(".*Chrome/(\\d+).*", "$1");
        assertTrue(h.get("sec-ch-ua").contains("\"Google Chrome\";v=\"" + major + "\""),
                "an inconsistent header set is a fingerprint again: " + h.get("sec-ch-ua"));
        assertEquals(ua.contains("Windows") ? "\"Windows\"" : "\"macOS\"",
                h.get("sec-ch-ua-platform"));
    }

    // ---- fans ----

    @Test
    void symbolAndIsinFansStaySilent() {
        WeltNewsClient client = new WeltNewsClient(new FakeFetcher(Map.of()));
        assertTrue(client.newsFor("RHM.DE", 5).isEmpty());
        assertTrue(client.newsForIsin("DE0007030009", 5).isEmpty(),
                "WELT's ISIN answer is the same filler a nonsense term produces");
    }

    @Test
    void nameFanCutsWeltsFuzzyPaddingAndDedupes() throws Exception {
        WeltNewsClient client = new WeltNewsClient(fullFetcher());

        List<RawNewsItem> hits = client.newsForName("Rheinmetall", 50);

        assertFalse(hits.isEmpty());
        assertEquals(hits.size(), hits.stream().map(RawNewsItem::link).distinct().count(),
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
        List<RawNewsItem> hits =
                new WeltNewsClient(fullFetcher()).newsForName("Rheinmetall", 50);

        Instant previous = null;
        for (RawNewsItem it : hits) {
            if (previous != null && it.publishedAt() != null) {
                assertFalse(it.publishedAt().isAfter(previous), "not newest-first");
            }
            if (it.publishedAt() != null) previous = it.publishedAt();
        }
    }

    @Test
    void emptyNameNeverReachesTheNetwork() throws Exception {
        FakeFetcher fetcher = fullFetcher();
        WeltNewsClient client = new WeltNewsClient(fetcher);

        assertTrue(client.newsForName("  ", 5).isEmpty());
        assertTrue(client.newsForName("AG", 5).isEmpty(), "no significant word left");
        assertEquals(0, fetcher.total());
    }

    @Test
    void poolIsFetchedOncePerTtl() throws Exception {
        FakeFetcher fetcher = fullFetcher();
        WeltNewsClient client = new WeltNewsClient(fetcher);

        client.newsForName("Rheinmetall", 5);
        int afterFirst = fetcher.count("/feeds/");
        client.newsForName("Siemens", 5);

        assertEquals(afterFirst, fetcher.count("/feeds/"), "second call must reuse the pool");
        assertEquals(WeltNewsClient.SECTION_FEEDS.size(), afterFirst,
                "one fetch per wired feed");
    }

    @Test
    void archiveWindowKeepsOnlyItemsInsideTheWindow() throws Exception {
        WeltNewsClient client = new WeltNewsClient(fullFetcher());

        List<RawNewsItem> hits =
                client.newsForNameWindow("Rheinmetall", null, "2013-01-01", "2020-01-01", 20);

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
    void archiveWindowRejectsBadBounds() {
        WeltNewsClient client = new WeltNewsClient(new FakeFetcher(Map.of()));
        assertTrue(client.newsForNameWindow("Rheinmetall", null, "nope", "2026-08-01", 10).isEmpty());
        assertTrue(client.newsForNameWindow("Rheinmetall", null, "2026-07-01", null, 10).isEmpty());
        assertTrue(client.newsForNameWindow("Rheinmetall", null, "2026-08-01", "2026-07-01", 10)
                .isEmpty(), "inverted window");
    }

    @Test
    void archiveWalksEveryReachableOffsetAndNeverPastTheWall() throws Exception {
        FakeFetcher fetcher = fullFetcher();
        new WeltNewsClient(fetcher)
                .newsForNameWindow("Rheinmetall", null, "2013-01-01", "2020-01-01", 20);

        List<String> searches = fetcher.callsContaining("/api/search/");
        assertEquals(WeltNewsClient.ARCHIVE_OFFSETS.length, searches.size());
        searches.forEach(u -> {
            int off = Integer.parseInt(u.replaceAll(".*offset=(\\d+).*", "$1"));
            assertTrue(off <= WeltNewsClient.MAX_OFFSET, "offset 195+ answers HTTP 400: " + u);
        });
    }

    @Test
    void outageKeepsThePreviousPool() throws Exception {
        FakeFetcher fetcher = fullFetcher();
        WeltNewsClient client = new WeltNewsClient(fetcher);
        assertFalse(client.latest(50).isEmpty());
        int warm = client.latest(50).size();

        fetcher.failAll = true;
        assertEquals(warm, client.latest(50).size(), "a stale pool beats an empty one");
    }

    // ---- general news stream ----

    @Test
    void latestServesTheGeneralStreamNewestFirst() throws Exception {
        List<RawNewsItem> stream = new WeltNewsClient(fullFetcher()).latest(50);

        assertFalse(stream.isEmpty());
        assertEquals(stream.size(), stream.stream().map(RawNewsItem::link).distinct().count(),
                "the same piece appears in several feeds - dedupe by link");
        Instant previous = null;
        for (RawNewsItem it : stream) {
            if (previous != null && it.publishedAt() != null) {
                assertFalse(it.publishedAt().isAfter(previous));
            }
            if (it.publishedAt() != null) previous = it.publishedAt();
        }
        assertTrue(stream.stream().anyMatch(it -> it.title().contains("Chinas Weg")));
        assertEquals(3, new WeltNewsClient(fullFetcher()).latest(3).size(), "limit honoured");
    }

    @Test
    void sectionServesOneNamedFeedAndSharesThePoolCache() throws Exception {
        FakeFetcher fetcher = fullFetcher();
        WeltNewsClient client = new WeltNewsClient(fetcher);

        List<RawNewsItem> wirtschaft = client.section("section/wirtschaft", 10);
        assertEquals(4, wirtschaft.size(), "the wirtschaft fixture minus its video items");
        assertEquals(1, fetcher.count("section/wirtschaft.rss"));

        client.latest(50); // warms the whole pool
        client.section("section/wirtschaft", 10);
        assertEquals(1, fetcher.count("section/wirtschaft.rss"),
                "the general stream must not re-fetch what the section leg already holds");

        assertTrue(client.section("", 5).isEmpty());
        assertTrue(client.section("section/wirtschaft", 0).isEmpty());
    }

    @Test
    void sectionFeedsAndSearchSectionsArePubliclyEnumerable() {
        assertTrue(WeltNewsClient.SECTION_FEEDS.containsKey("section/finanzen"));
        assertTrue(WeltNewsClient.SECTION_FEEDS.containsKey("latest"));
        assertEquals(5, WeltNewsClient.SECTION_FEEDS.size());
        assertTrue(WeltNewsClient.SEARCH_SECTIONS.contains("wirtschaft"));
        assertFalse(WeltNewsClient.SEARCH_SECTIONS.contains("finanzen"),
                "the search enum has no finanzen member - WELT 400s on it");
    }

    @Test
    void publicSearchDoorCapsOffsetAndDropsInvalidFilters() throws Exception {
        FakeFetcher fetcher = fullFetcher();
        WeltNewsClient client = new WeltNewsClient(fetcher);

        List<RawNewsItem> hits = client.search("Rheinmetall", 9999, "h1", "finanzen", 3);

        assertEquals(3, hits.size(), "no precision cut here - the caller owns the term");
        String url = fetcher.callsContaining("/api/search/").get(0);
        assertTrue(url.contains("offset=190"), "offset 195+ answers HTTP 400: " + url);
        assertFalse(url.contains("restrictBy"), "h1 is not in the enum: " + url);
        assertFalse(url.contains("section="), "finanzen is not in the enum: " + url);
        assertTrue(url.contains("type=article"));
    }

    @Test
    void publicSearchDoorPassesValidFilters() throws Exception {
        FakeFetcher fetcher = fullFetcher();
        WeltNewsClient client = new WeltNewsClient(fetcher);

        client.search("Zinswende", 50, "m1", "wirtschaft", 10);

        String url = fetcher.callsContaining("/api/search/").get(0);
        assertTrue(url.contains("offset=50"));
        assertTrue(url.contains("restrictBy=m1"));
        assertTrue(url.contains("section=wirtschaft"));
        assertTrue(client.search("", 0, null, null, 5).isEmpty());
        assertTrue(client.search("Zinswende", 0, null, null, 0).isEmpty());
    }

    @Test
    void sourceNameIsStable() {
        assertEquals("welt", new WeltNewsClient(new FakeFetcher(Map.of())).sourceName());
        assertFalse(new WeltNewsClient(new FakeFetcher(Map.of())).socialSentiment());
    }

    // ---- harness ----

    private static FakeFetcher fullFetcher() throws IOException {
        return new FakeFetcher(Map.of(
                "section/wirtschaft.rss", fixture("welt-wirtschaft-rss.xml"),
                "latest.rss", fixture("welt-latest-rss.xml"),
                "/api/search/", fixture("welt-search-rheinmetall.json")));
    }

    /** Serves fixtures by URL fragment and records calls. */
    private static final class FakeFetcher implements WebFetcher {
        private final Map<String, String> byFragment;
        private final List<String> calls = new ArrayList<>();
        boolean failAll;

        FakeFetcher(Map<String, String> byFragment) {
            this.byFragment = byFragment;
        }

        int count(String fragment) {
            return callsContaining(fragment).size();
        }

        int total() {
            return calls.size();
        }

        List<String> callsContaining(String fragment) {
            return calls.stream().filter(u -> u.contains(fragment)).toList();
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
