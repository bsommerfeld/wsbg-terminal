package de.bsommerfeld.wsbg.terminal.finanzennet;

import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetNewsClient.AnalystStudy;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RSS, analyser and fan tests against live-captured fixtures (2026-08-02). */
class FinanzenNetNewsClientTest {

    private static Map<String, String> allFeeds() throws Exception {
        Map<String, String> m = new HashMap<>();
        m.put("/rss/analysen", Fixtures.load("fn-rss-analysen.xml"));
        m.put("/rss/news", Fixtures.load("fn-rss-news.xml"));
        m.put("-rss-feed", Fixtures.load("fn-rss-sap.xml"));
        m.put("SearchController_Suggest", Fixtures.load("fn-suggest-sap.txt"));
        m.put("/analyse/", Fixtures.load("fn-analyse-saint-gobain.html"));
        return m;
    }

    private static FinanzenNetNewsClient client(Fixtures.FakeFetcher fetcher) {
        return new FinanzenNetNewsClient(fetcher, new FinanzenNetResolver(fetcher));
    }

    // ------------------------------------------------------------ RSS parser

    @Test
    void parsesTheAnalyserFeedWithTeaserAndTimestamp() throws Exception {
        List<RawNewsItem> items =
                FinanzenNetNewsClient.parseFeed(Fixtures.load("fn-rss-analysen.xml"));

        assertFalse(items.isEmpty());
        RawNewsItem first = items.get(0);
        assertEquals("PUMA SE Neutral", first.title());
        assertEquals(FinanzenNetNewsClient.PUBLISHER, first.publisher());
        assertTrue(first.link().startsWith("https://www.finanzen.net/analyse/"));
        assertEquals(first.link(), first.uuid(), "the permalink guid is the identity");
        assertEquals(Instant.parse("2026-07-31T19:31:14Z"), first.publishedAt());
        assertNotNull(first.summary());
        assertTrue(first.summary().contains("dpa-AFX Analyser"));
        assertFalse(first.summary().contains("<p>"), "HTML is stripped from the teaser");
        assertFalse(first.sponsored());
    }

    @Test
    void parsesTheNewsTickerAndUnescapesEntitiesInTitles() throws Exception {
        List<RawNewsItem> items =
                FinanzenNetNewsClient.parseFeed(Fixtures.load("fn-rss-news.xml"));

        assertFalse(items.isEmpty());
        assertTrue(items.get(0).link().contains("/nachricht/"));
        assertTrue(items.stream().allMatch(i -> i.publishedAt() != null));

        List<RawNewsItem> instrument =
                FinanzenNetNewsClient.parseFeed(Fixtures.load("fn-rss-sap.xml"));
        assertTrue(instrument.stream().anyMatch(i -> i.title().contains("&")),
                "&amp; must come back as '&'");
        assertTrue(instrument.stream().noneMatch(i -> i.title().contains("&amp;")));
    }

    @Test
    void feedGarbageYieldsEmptyListNotException() {
        assertTrue(FinanzenNetNewsClient.parseFeed("<rss><channel><item></item>").isEmpty());
        assertTrue(FinanzenNetNewsClient.parseFeed("not xml at all").isEmpty());
        assertTrue(FinanzenNetNewsClient.parseFeed("").isEmpty());
        assertTrue(FinanzenNetNewsClient.parseFeed(null).isEmpty());
        assertNull(FinanzenNetNewsClient.parseRssDate("nonsense"));
        assertEquals(Instant.parse("2026-08-02T21:34:39Z"),
                FinanzenNetNewsClient.parseRssDate("Sun, 02 Aug 2026 23:34:39 +0200"));
    }

    // ----------------------------------------------------------- instrument

    @Test
    void symbolFanGoesThroughTheResolverToThePerInstrumentFeed() throws Exception {
        Fixtures.FakeFetcher fetcher = new Fixtures.FakeFetcher(allFeeds());

        List<RawNewsItem> hits = client(fetcher).newsFor("SAP", 10);

        assertFalse(hits.isEmpty());
        assertEquals(1, fetcher.count("SearchController_Suggest"), "resolve once");
        assertEquals(1, fetcher.count("/rss/sap-rss-feed"), "then read the instrument feed");
    }

    @Test
    void isinFanUsesTheStrictResolverPath() throws Exception {
        Fixtures.FakeFetcher fetcher = new Fixtures.FakeFetcher(allFeeds());
        FinanzenNetNewsClient client = client(fetcher);

        assertFalse(client.newsForIsin("DE0007164600", 5).isEmpty());
        assertTrue(client.newsForIsin("DE0007164601", 5).isEmpty(),
                "a wrong ISIN must not fall through to a near match");
        assertTrue(client.newsForIsin("junk", 5).isEmpty());
    }

    @Test
    void nameFanMergesInstrumentFeedAndPoolWithoutDuplicates() throws Exception {
        List<RawNewsItem> hits = client(new Fixtures.FakeFetcher(allFeeds()))
                .newsForName("SAP SE", 50);

        assertFalse(hits.isEmpty());
        assertEquals(hits.size(), hits.stream().map(RawNewsItem::link).distinct().count(),
                "merge must dedupe by link");
        Instant previous = null;
        for (RawNewsItem it : hits) {
            if (previous != null && it.publishedAt() != null) {
                assertFalse(it.publishedAt().isAfter(previous), "not newest-first");
            }
            if (it.publishedAt() != null) previous = it.publishedAt();
        }
    }

    @Test
    void titlePrecisionFilterRejectsAStemOverlap() {
        Set<String> words = FinanzenNetNewsClient.significantWords("Rheinmetall AG");

        assertEquals(Set.of("rheinmetall"), words);
        assertTrue(FinanzenNetNewsClient.titleMatches("Rheinmetall hebt die Prognose", words));
        assertFalse(FinanzenNetNewsClient.titleMatches("Der Rheinpegel steigt", words));
        assertTrue(FinanzenNetNewsClient.significantWords("AG SE Holding").isEmpty(),
                "legal forms alone can never carry a match");
    }

    @Test
    void archiveWindowStaysSilentBecauseTheNewsArchiveIsFlat() throws Exception {
        FinanzenNetNewsClient client = client(new Fixtures.FakeFetcher(allFeeds()));

        assertTrue(client.newsForNameWindow("SAP", "DE0007164600",
                        "2020-01-01", "2021-01-01", 50).isEmpty(),
                "/news/<slug>-news does not paginate - no window may be faked over it");
    }

    // -------------------------------------------------------- general layer

    @Test
    void latestPoolsTheNewsTickerAndTheAnalyserFeedNewestFirst() throws Exception {
        Fixtures.FakeFetcher fetcher = new Fixtures.FakeFetcher(allFeeds());
        FinanzenNetNewsClient client = client(fetcher);

        List<RawNewsItem> latest = client.latest(50);

        assertTrue(latest.stream().anyMatch(i -> i.link().contains("/nachricht/")));
        assertTrue(latest.stream().anyMatch(i -> i.link().contains("/analyse/")));
        Instant previous = null;
        for (RawNewsItem it : latest) {
            if (previous != null) assertFalse(it.publishedAt().isAfter(previous));
            previous = it.publishedAt();
        }
    }

    @Test
    void latestRidesTheSamePoolAsTheNameFan() throws Exception {
        Fixtures.FakeFetcher fetcher = new Fixtures.FakeFetcher(allFeeds());
        FinanzenNetNewsClient client = client(fetcher);

        client.newsForName("SAP SE", 5);
        int afterName = fetcher.count("/rss/news");
        client.latest(10);

        assertEquals(afterName, fetcher.count("/rss/news"), "the general stream costs no fetch");
    }

    @Test
    void analystCallsServeTheRatingTapeOnItsOwn() throws Exception {
        Fixtures.FakeFetcher fetcher = new Fixtures.FakeFetcher(allFeeds());

        List<RawNewsItem> calls = client(fetcher).analystCalls(3);

        assertEquals(3, calls.size());
        calls.forEach(c -> assertTrue(c.link().contains("/analyse/")));
        assertEquals(0, fetcher.count("/rss/news"), "the analyser leg is fetched alone");
    }

    @Test
    void deadFeedsAreNeverRequested() throws Exception {
        Fixtures.FakeFetcher fetcher = new Fixtures.FakeFetcher(allFeeds());
        FinanzenNetNewsClient client = client(fetcher);

        client.latest(10);
        client.newsTicker(5);
        client.newsForSlug("sap", 5);

        for (String dead : FinanzenNetNewsClient.DEAD_FEEDS) {
            assertTrue(fetcher.calls.stream().noneMatch(u -> u.endsWith("/rss/" + dead)),
                    "/rss/" + dead + " answers 200 with zero items");
        }
    }

    @Test
    void generalAccessorsRejectEmptyInput() throws Exception {
        FinanzenNetNewsClient client = client(new Fixtures.FakeFetcher(allFeeds()));

        assertTrue(client.latest(0).isEmpty());
        assertTrue(client.analystCalls(-1).isEmpty());
        assertTrue(client.newsTicker(0).isEmpty());
        assertTrue(client.newsForSlug("  ", 5).isEmpty());
        assertTrue(client.newsFor(null, 5).isEmpty());
        assertTrue(client.newsForName("SAP", 0).isEmpty());
        assertEquals("finanzen-net", client.sourceName());
        assertFalse(client.socialSentiment());
    }

    // ------------------------------------------------------- analyser study

    @Test
    void readsTheAnalyserFullTextWithRatingBeforeAndAfter() throws Exception {
        Optional<AnalystStudy> study = FinanzenNetNewsClient.parseAnalysis(
                Fixtures.load("fn-analyse-saint-gobain.html"),
                "https://www.finanzen.net/analyse/saint_gobain_outperform-rbc_capital_markets_1096443");

        assertTrue(study.isPresent());
        AnalystStudy s = study.get();
        assertTrue(s.company().startsWith("Saint-Gobain"));
        assertEquals("RBC Capital Markets", s.analystHouse());
        assertEquals("Oliver Dyson", s.analystName());
        assertEquals("Outperform", s.ratingNow());
        assertEquals("Outperform", s.ratingBefore());
        assertEquals(97.0, s.priceTarget(), 1e-9);
        assertEquals("EUR", s.currency());
        assertEquals(94.78, s.consensusTarget(), 1e-9);
        assertEquals(18.93, s.upsidePercent(), 1e-9);
        assertNotNull(s.body());
        assertTrue(s.body().contains("dpa-AFX Analyser"));
    }

    @Test
    void analysisRefusesNonAnalyserUrlsAndUnparseablePages() throws Exception {
        FinanzenNetNewsClient client = client(new Fixtures.FakeFetcher(allFeeds()));

        assertTrue(client.analysis(null).isEmpty());
        assertTrue(client.analysis("https://example.com/whatever").isEmpty());
        assertTrue(FinanzenNetNewsClient.parseAnalysis("<html>403</html>", "u").isEmpty());
        assertTrue(FinanzenNetNewsClient.parseAnalysis(null, "u").isEmpty());
        assertTrue(client.analysis(
                "https://www.finanzen.net/analyse/puma_se_neutral-jp_morgan_1096444").isPresent());
    }

    @Test
    void anOutageKeepsThePreviousPoolInsteadOfEmptyingTheWire() throws Exception {
        Fixtures.FakeFetcher fetcher = new Fixtures.FakeFetcher(allFeeds());
        FinanzenNetNewsClient client = client(fetcher);
        assertFalse(client.latest(10).isEmpty());

        fetcher.failAll = true;
        assertFalse(client.latest(10).isEmpty(), "the cached pool answers through an outage");
    }
}
