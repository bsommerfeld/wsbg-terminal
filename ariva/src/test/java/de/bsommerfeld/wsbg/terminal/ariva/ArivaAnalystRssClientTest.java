package de.bsommerfeld.wsbg.terminal.ariva;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ariva dpa-AFX-Analyser feed — fixture is a live response excerpt
 * (2026-07-16) trimmed to 3 ratings: Berenberg/Richemont (CH ISIN),
 * JPMorgan/Ahold Delhaize (NL) and JPMorgan/Airbus (NL) — each link
 * carrying the covered instrument's ISIN in {@code utm_content}.
 */
class ArivaAnalystRssClientTest {

    private static String fixture() {
        try (InputStream in = ArivaAnalystRssClientTest.class
                .getResourceAsStream("/ariva-analysen.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing", e);
        }
    }

    /** Fake transport answering a fixed sequence of bodies, counting fetches. */
    private static final class FakeFetcher implements WebFetcher {
        final AtomicInteger fetches = new AtomicInteger();
        private final List<WebResponse> replies;

        FakeFetcher(WebResponse... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            assertEquals("https://www.ariva.de/news/analysen/rss", url,
                    "ONE analyst feed URL — this source is a firehose, never a search");
            int n = fetches.getAndIncrement();
            return replies.get(Math.min(n, replies.size() - 1));
        }
    }

    private static WebResponse rss() {
        return new WebResponse(200, fixture(), Map.of());
    }

    @Test
    void parseMapsTheLiveFeedFields() {
        List<RawNewsItem> items = ArivaAnalystRssClient.parse(fixture());
        assertEquals(3, items.size(), "the pool is unfiltered — all feed items parse");

        RawNewsItem richemont = items.get(0);
        assertEquals("Berenberg hebt Ziel für Richemont auf 170 Franken - 'Hold'",
                richemont.title());
        assertEquals("https://www.ariva.de/aktien/cie-financiere-richemont-sa-aktie/news/"
                        + "berenberg-hebt-ziel-fuer-richemont-auf-170-franken-hold-12070782",
                richemont.link(),
                "the utm tracking query is cut — the clean article URL is link and uuid");
        assertEquals(richemont.link(), richemont.uuid());
        assertEquals("CH0210483332", richemont.isin(),
                "the covered instrument's ISIN is mined from utm_content BEFORE the cut");
        assertEquals(Instant.parse("2026-07-16T11:13:08Z"), richemont.publishedAt());
        assertEquals("dpa-AFX Analyser", richemont.publisher());
        assertTrue(richemont.imageUrl().endsWith(
                "berenberg-hebt-ziel-fr-richemont-auf-170-franken-hold-12070782.jpg"),
                "the enclosure image rides along");

        assertTrue(richemont.summary().startsWith("HAMBURG (dpa-AFX Analyser)"),
                richemont.summary());
        assertFalse(richemont.summary().contains("[weiter]"),
                "the trailing read-more anchor is stripped from the summary");
        assertFalse(richemont.summary().contains("<"), "HTML tags are stripped");

        assertEquals("NL0011794037", items.get(1).isin(), "Ahold Delhaize");
        assertEquals("NL0000235190", items.get(2).isin(), "Airbus");
    }

    @Test
    void parseToleratesGarbageAndHtmlAnswers() {
        assertTrue(ArivaAnalystRssClient.parse("<html><body>404</body></html>").isEmpty());
        assertTrue(ArivaAnalystRssClient.parse("not xml at all").isEmpty());
        assertTrue(ArivaAnalystRssClient.parse("").isEmpty());
        assertTrue(ArivaAnalystRssClient.parse(null).isEmpty());
        String torn = fixture().substring(0, fixture().indexOf("<pubDate>") + 20);
        assertTrue(ArivaAnalystRssClient.parse(torn).isEmpty());
    }

    @Test
    void isinLegMatchesExactlyAndNameLegIsPrecisionOverRecall() {
        ArivaAnalystRssClient client = new ArivaAnalystRssClient(new FakeFetcher(rss()));

        List<RawNewsItem> byIsin = client.newsForIsin("CH0210483332", 10);
        assertEquals(1, byIsin.size());
        assertTrue(byIsin.get(0).title().contains("Richemont"));
        assertTrue(client.newsForIsin("DE0007664039", 10).isEmpty(),
                "an instrument the feed doesn't cover yields nothing");

        assertEquals(1, client.newsForName("Cie Financière Richemont SA", 10).size(),
                "the accent-bearing legal name still matches via 'richemont'");
        assertEquals(1, client.newsForName("Airbus Group", 10).size(),
                "generic legal words (Group) never carry the match — 'Airbus' does");
        assertTrue(client.newsForName("Rheinmetall AG", 10).isEmpty(),
                "a company the feed doesn't carry yields nothing");
        assertTrue(client.newsForName("AG", 10).isEmpty(),
                "a name with ONLY generic words must never flood the pool");

        assertTrue(client.newsFor("CFR", 10).isEmpty(), "symbol leg is a no-op");
    }

    @Test
    void poolCacheAnswersABurstWithOneFetch() {
        FakeFetcher fetcher = new FakeFetcher(rss());
        ArivaAnalystRssClient client = new ArivaAnalystRssClient(fetcher);

        assertEquals(1, client.newsForIsin("CH0210483332", 10).size());
        assertEquals(1, client.newsForName("Ahold Delhaize", 10).size());
        assertEquals(1, client.newsForIsin("NL0000235190", 10).size());
        assertEquals(1, fetcher.fetches.get(),
                "the POOL is cached — a burst of mixed queries makes ONE request");
    }

    @Test
    void softTwoHundredTrapIsAMissAndNeverPoisonsThePool() {
        FakeFetcher fetcher = new FakeFetcher(
                new WebResponse(200, "<!doctype html><html><body>shell</body></html>",
                        Map.of()),
                rss());
        ArivaAnalystRssClient client = new ArivaAnalystRssClient(fetcher);

        assertTrue(client.newsForIsin("CH0210483332", 10).isEmpty(),
                "an HTML 200 is a miss, not a feed");
        assertEquals(1, client.newsForIsin("CH0210483332", 10).size(),
                "the miss was NOT cached — the next call refetches and succeeds");
        assertEquals(2, fetcher.fetches.get());
    }

    @Test
    void isinExtractionAndLinkCleaningAreExact() {
        assertEquals("CH0210483332", ArivaAnalystRssClient.extractIsin(
                "https://x.de/a?utm_medium=referral&utm_content=CH0210483332"));
        assertEquals("CH0210483332", ArivaAnalystRssClient.extractIsin(
                "https://x.de/a?utm_content=CH0210483332&utm_medium=referral"));
        assertNull(ArivaAnalystRssClient.extractIsin("https://x.de/a?utm_content=notanisin"));
        assertNull(ArivaAnalystRssClient.extractIsin("https://x.de/a"));
        assertNull(ArivaAnalystRssClient.extractIsin(null));
        assertEquals("https://x.de/a", ArivaAnalystRssClient.stripQuery("https://x.de/a?u=1"));
        assertEquals("https://x.de/a", ArivaAnalystRssClient.stripQuery("https://x.de/a"));
    }
}
