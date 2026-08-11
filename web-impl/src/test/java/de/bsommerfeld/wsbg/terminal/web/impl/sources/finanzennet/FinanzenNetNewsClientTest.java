package de.bsommerfeld.wsbg.terminal.web.impl.sources.finanzennet;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport.FakeWebFetcher;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RSS and fan tests against live-captured fixtures (2026-08-02). Ported from
 * the module world; the analyser-study reader, the general streams and the
 * (deliberately absent) archive window did not migrate.
 */
class FinanzenNetNewsClientTest {

    private static String fixture(String name) {
        return SourceTestSupport.fixture("sources/finanzennet/" + name);
    }

    private static FakeWebFetcher allFeeds() {
        return new FakeWebFetcher()
                .on("/rss/analysen", fixture("fn-rss-analysen.xml"))
                .on("/rss/news", fixture("fn-rss-news.xml"))
                .on("-rss-feed", fixture("fn-rss-sap.xml"))
                .on("SearchController_Suggest", fixture("fn-suggest-sap.txt"));
    }

    private static FinanzenNetNewsClient client(FakeWebFetcher fetcher) {
        return new FinanzenNetNewsClient(fetcher, new FinanzenNetResolver(fetcher));
    }

    // ------------------------------------------------------------ RSS parser

    @Test
    void parsesTheAnalyserFeedWithTeaserAndTimestamp() {
        List<Article> items =
                FinanzenNetNewsClient.parseFeed(fixture("fn-rss-analysen.xml"));

        assertFalse(items.isEmpty());
        Article first = items.get(0);
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
    void parsesTheNewsTickerAndUnescapesEntitiesInTitles() {
        List<Article> items =
                FinanzenNetNewsClient.parseFeed(fixture("fn-rss-news.xml"));

        assertFalse(items.isEmpty());
        assertTrue(items.get(0).link().contains("/nachricht/"));
        assertTrue(items.stream().allMatch(i -> i.publishedAt() != null));

        List<Article> instrument =
                FinanzenNetNewsClient.parseFeed(fixture("fn-rss-sap.xml"));
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
    void symbolKeyGoesThroughTheResolverToThePerInstrumentFeed() {
        FakeWebFetcher fetcher = allFeeds();

        List<Article> hits = client(fetcher).newsFor(new ResolvedInstrument(
                Optional.empty(), Ticker.parse("SAP"), ""), 10);

        assertFalse(hits.isEmpty());
        assertEquals(1, fetcher.count("SearchController_Suggest"), "resolve once");
        assertEquals(1, fetcher.count("/rss/sap-rss-feed"), "then read the instrument feed");
    }

    @Test
    void isinKeyUsesTheStrictResolverPath() {
        FakeWebFetcher fetcher = allFeeds();
        FinanzenNetNewsClient client = client(fetcher);

        assertFalse(client.newsFor(new ResolvedInstrument(
                Isin.parse("DE0007164600"), Optional.empty(), ""), 5).isEmpty());
        assertTrue(client.newsFor(new ResolvedInstrument(
                        Isin.parse("DE0007236101"), Optional.empty(), ""), 5).isEmpty(),
                "a wrong ISIN must not fall through to a near match");
    }

    @Test
    void nameKeyMergesInstrumentFeedAndPoolWithoutDuplicates() {
        List<Article> hits = client(allFeeds())
                .newsFor(ResolvedInstrument.ofName("SAP SE"), 50);

        assertFalse(hits.isEmpty());
        assertEquals(hits.size(), hits.stream().map(Article::link).distinct().count(),
                "merge must dedupe by link");
        Instant previous = null;
        for (Article it : hits) {
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
    void deadFeedsAreNeverRequested() {
        FakeWebFetcher fetcher = allFeeds();
        FinanzenNetNewsClient client = client(fetcher);

        client.newsFor(ResolvedInstrument.ofName("SAP SE"), 10);

        for (String dead : FinanzenNetNewsClient.DEAD_FEEDS) {
            assertTrue(fetcher.calls.stream().noneMatch(u -> u.endsWith("/rss/" + dead)),
                    "/rss/" + dead + " answers 200 with zero items");
        }
    }

    @Test
    void emptyKeysNeverReachTheNetwork() {
        FakeWebFetcher fetcher = allFeeds();
        FinanzenNetNewsClient client = client(fetcher);

        assertTrue(client.newsFor(ResolvedInstrument.ofName("  "), 5).isEmpty());
        assertTrue(client.newsFor(ResolvedInstrument.ofName("SAP"), 0).isEmpty());
        assertEquals(0, fetcher.total());
        assertEquals("finanzen-net", client.sourceName());
        assertFalse(client.socialSentiment());
    }
}
