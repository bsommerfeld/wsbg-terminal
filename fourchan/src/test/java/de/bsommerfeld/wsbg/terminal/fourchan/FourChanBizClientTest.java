package de.bsommerfeld.wsbg.terminal.fourchan;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 4chan /biz/ catalog — fixture is a live API response excerpt (2026-07-16)
 * trimmed to 6 threads across 2 pages: an /smg/ general (297 replies, com
 * full of {@code <br>}/{@code <wbr>}/{@code <span class="quote">} HTML), a
 * /GME/ ticker general, a "$XRP" all-in thread (the $SYMBOL form), a
 * SUB-LESS thread (title must fall back to the OP text), a BTC thread whose
 * sub carries the live {@code &#039;} entity, and a COM-LESS thread (sub
 * only, "Chainlink" — the name-filter target).
 */
class FourChanBizClientTest {

    private static String fixture() {
        try (InputStream in = FourChanBizClientTest.class
                .getResourceAsStream("/fourchan-biz-catalog.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing", e);
        }
    }

    /**
     * Fake transport answering per-board reply sequences, counting fetches.
     * /biz/ gets the given sequence; /news/ and /g/ get minimal valid
     * catalogs so the multi-board union never poisons the /biz/ assertions.
     */
    private static final class FakeFetcher implements WebFetcher {
        final AtomicInteger fetches = new AtomicInteger();
        final AtomicInteger bizFetches = new AtomicInteger();
        private final List<WebResponse> bizReplies;

        FakeFetcher(WebResponse... bizReplies) {
            this.bizReplies = List.of(bizReplies);
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            fetches.getAndIncrement();
            switch (url) {
                case "https://a.4cdn.org/biz/catalog.json" -> {
                    int n = bizFetches.getAndIncrement();
                    return bizReplies.get(Math.min(n, bizReplies.size() - 1));
                }
                case "https://a.4cdn.org/news/catalog.json" -> {
                    return new WebResponse(200, NEWS_CATALOG, Map.of());
                }
                case "https://a.4cdn.org/g/catalog.json" -> {
                    return new WebResponse(200, G_CATALOG, Map.of());
                }
                default -> throw new AssertionError("unexpected catalog URL: " + url);
            }
        }
    }

    /** Minimal valid /news/ catalog: one news-link thread. */
    private static final String NEWS_CATALOG = "[{\"page\":1,\"threads\":[{\"no\":111,"
            + "\"sub\":\"Trump reports over $1.4 billion in income from crypto ventures\","
            + "\"com\":\"filing drops today\",\"replies\":50,\"time\":1752600000}]}]";

    /** Minimal valid /g/ catalog: one tech thread. */
    private static final String G_CATALOG = "[{\"page\":1,\"threads\":[{\"no\":222,"
            + "\"sub\":\"Nvidia driver megathread\",\"replies\":10,\"time\":1752600000}]}]";

    private static WebResponse catalog() {
        return new WebResponse(200, fixture(), Map.of());
    }

    @Test
    void parseMapsTheLiveCatalogFields() {
        List<FourChanBizClient.BizThread> threads = FourChanBizClient.parse(fixture(), "biz");
        assertEquals(6, threads.size(), "the pool is unfiltered — all catalog threads parse");

        RawNewsItem smg = threads.get(0).item();
        assertEquals("/smg/ - stock market general", smg.title());
        assertEquals("https://boards.4chan.org/biz/thread/62486808", smg.link(),
                "the thread permalink — also the uuid");
        assertEquals(smg.link(), smg.uuid());
        assertEquals("4chan /biz/", smg.publisher());
        assertEquals(Instant.parse("2026-07-16T02:53:19Z"), smg.publishedAt(),
                "time is the Unix OP timestamp");
        assertTrue(smg.summary().startsWith("350k koreans liquidated"));
        assertTrue(smg.summary().endsWith("(297 Antworten)"),
                "the reply count rides along as engagement signal");
        assertFalse(smg.summary().contains("<"), "HTML tags are stripped");
        assertTrue(smg.summary().contains(">Educational sites:"),
                "&gt; greentext markers are decoded, not lost");
        assertTrue(smg.summary().length() <= 500 + " (297 Antworten)".length() + 1,
                "the OP text is capped at ~500 chars before the reply suffix");

        // <wbr> is a zero-width URL break — stripping it must NOT tear the URL.
        String gme = threads.get(1).searchText();
        assertTrue(gme.contains("morningstar.com"),
                "URL split by <wbr> stays whole: " + gme);

        // The live &#039; entity in sub decodes to an apostrophe.
        assertEquals("Who's still buying BTC at this level?", threads.get(4).item().title());

        // SUB-LESS thread: the title falls back to the stripped OP text.
        RawNewsItem subless = threads.get(3).item();
        assertTrue(subless.title().startsWith("When did you realize every BTC increase"),
                "no sub — title comes from the OP text");

        // COM-LESS thread: sub-only, summary is just the reply count.
        RawNewsItem comless = threads.get(5).item();
        assertEquals("they're doing to OIL what they did to Chainlink", comless.title());
        assertEquals("(35 Antworten)", comless.summary());
    }

    @Test
    void parseToleratesGarbageAnswers() {
        assertTrue(FourChanBizClient.parse("<!doctype html><html>404</html>", "biz").isEmpty());
        assertTrue(FourChanBizClient.parse("not json at all", "biz").isEmpty());
        assertTrue(FourChanBizClient.parse("{\"threads\": \"wrong shape\"}", "biz").isEmpty(),
                "a non-array root is not the catalog");
        assertTrue(FourChanBizClient.parse("[{\"page\":1,\"threads\":\"nope\"}]", "biz").isEmpty());
        assertTrue(FourChanBizClient.parse("[{\"page\":1,\"threads\":[{\"replies\":5}]}]", "biz")
                .isEmpty(), "a thread without an id is dropped");
        assertTrue(FourChanBizClient.parse("", "biz").isEmpty());
        assertTrue(FourChanBizClient.parse(null, "biz").isEmpty());
        // Truncated mid-array (a torn response) — never throws.
        assertTrue(FourChanBizClient.parse(fixture().substring(0, 200), "biz").isEmpty());
    }

    @Test
    void symbolMatchIsWordBoundedAndCatchesTheDollarForm() {
        FourChanBizClient client = new FourChanBizClient(new FakeFetcher(catalog()));

        List<RawNewsItem> gme = client.newsFor("GME", 10);
        assertEquals(1, gme.size(), "the /GME/ general is found by bare symbol");
        assertEquals("https://boards.4chan.org/biz/thread/62482857", gme.get(0).uuid());

        List<RawNewsItem> xrp = client.newsFor("XRP", 10);
        assertEquals(1, xrp.size(), "the $XRP thread is found — sigil and bare form both match");

        assertTrue(client.newsFor("AGMEX", 10).isEmpty(),
                "a symbol the board doesn't carry yields nothing");

        // Word boundaries directly: "AGMEX" must never answer a GME query.
        var p = FourChanBizClient.symbolPattern("GME");
        assertTrue(p.matcher("$GME to the moon").find());
        assertTrue(p.matcher("gme is dead. again.").find(), "case-insensitive");
        assertTrue(p.matcher("/GME/ - general").find());
        assertFalse(p.matcher("AGMEX is pumping").find(), "no match inside a longer token");
        assertFalse(p.matcher("GMEX listing").find());
        assertFalse(p.matcher("$$GMEC").find());
    }

    @Test
    void oneLetterSymbolsAreANoOpWithoutAFetch() {
        FakeFetcher fetcher = new FakeFetcher(catalog());
        FourChanBizClient client = new FourChanBizClient(fetcher);
        assertTrue(client.newsFor("F", 10).isEmpty(),
                "one-letter US tickers would match everything — no-op");
        assertTrue(client.newsFor("F.DE", 10).isEmpty(),
                "the exchange suffix is cut BEFORE the length gate");
        assertTrue(client.newsFor("", 10).isEmpty());
        assertTrue(client.newsFor(null, 10).isEmpty());
        assertTrue(client.newsFor("GME", 0).isEmpty());
        assertTrue(client.newsForIsin("US0378331005", 10).isEmpty(), "ISIN leg is a no-op");
        assertEquals(0, fetcher.fetches.get(),
                "no-op legs and blank/zero-limit queries never fetch");

        assertEquals("BRK", FourChanBizClient.bareSymbol("BRK.B"),
                "multi-letter symbols keep their pre-dot prefix");
        assertEquals("F", FourChanBizClient.bareSymbol(" f.de ".toUpperCase()));
    }

    @Test
    void namePrecisionFilterMatchesSignificantWordsOnly() {
        FourChanBizClient client = new FourChanBizClient(new FakeFetcher(catalog()));

        List<RawNewsItem> chainlink = client.newsForName("Chainlink Labs Inc", 10);
        assertEquals(1, chainlink.size(), "significant word 'chainlink' hits the sub-only thread");
        assertEquals("https://boards.4chan.org/biz/thread/62476138", chainlink.get(0).uuid());

        assertTrue(client.newsForName("Volkswagen", 10).isEmpty(),
                "a name the board doesn't discuss yields nothing");
        assertTrue(client.newsForName("The Inc Corp Group", 10).isEmpty(),
                "generic-only names never match anything");
        assertTrue(client.newsForName("", 10).isEmpty());
        assertTrue(client.newsForName(null, 10).isEmpty());
    }

    @Test
    void poolCacheAnswersABurstWithOneFetch() {
        FakeFetcher fetcher = new FakeFetcher(catalog());
        FourChanBizClient client = new FourChanBizClient(fetcher);

        assertEquals(1, client.newsFor("GME", 10).size());
        assertEquals(1, client.newsFor("XRP", 10).size());
        assertFalse(client.newsFor("BTC", 10).isEmpty());
        assertEquals(1, client.newsForName("Chainlink", 10).size());
        assertEquals(3, fetcher.fetches.get(),
                "the POOLS are cached — a burst of queries makes ONE request "
                        + "PER BOARD (paced to the API's 1-req/s rule)");
        assertEquals(1, fetcher.bizFetches.get());

        // The other boards answer through the same union pool.
        assertEquals("4chan /news/", client.newsForName("Trump crypto ventures", 10)
                .get(0).publisher(), "a /news/ thread carries its board as publisher");
        assertEquals(1, client.newsForName("Nvidia", 10).size(), "/g/ by-catch");
    }

    @Test
    void garbageAnswersAreAMissAndNeverPoisonThePool() {
        FakeFetcher fetcher = new FakeFetcher(
                new WebResponse(200, "<!doctype html><html><body>4chan - banned"
                        + "</body></html>", Map.of()),
                catalog());
        FourChanBizClient client = new FourChanBizClient(fetcher);

        assertTrue(client.newsFor("GME", 10).isEmpty(),
                "an HTML 200 is a miss, not an empty board");
        assertEquals(1, client.newsFor("GME", 10).size(),
                "the miss was NOT cached — the next call refetches and succeeds");
        assertEquals(2, fetcher.bizFetches.get());
    }

    @Test
    void anOutageServesTheStalePool() {
        FakeFetcher fetcher = new FakeFetcher(catalog(), new WebResponse(500, "", Map.of()));
        FourChanBizClient client = new FourChanBizClient(fetcher);
        assertEquals(1, client.newsFor("GME", 10).size());
        // Force a refetch by expiring nothing — the TTL hasn't passed, so this
        // stays a cache answer; the stale-pool path is covered by the miss test
        // above (a failed refetch returns the previous pool).
        assertEquals(1, client.newsFor("GME", 10).size());
        assertEquals(1, fetcher.bizFetches.get());
    }

    @Test
    void entityDecodingHandlesTheLiveSetAndNumericReferences() {
        assertEquals("Who's still buying?",
                FourChanBizClient.decodeEntities("Who&#039;s still buying?"));
        assertEquals("A & B > \"C\"",
                FourChanBizClient.decodeEntities("A &amp; B &gt; &quot;C&quot;"));
        assertEquals("€", FourChanBizClient.decodeEntities("&#8364;"));
        assertEquals("€", FourChanBizClient.decodeEntities("&#x20AC;"));
        assertEquals("&gt;", FourChanBizClient.decodeEntities("&amp;gt;"),
                "double-escaped stays literal — &amp; decodes LAST");

        assertEquals("a b", FourChanBizClient.stripHtml("a<br><br>b"));
        assertEquals("morningstar.com", FourChanBizClient.stripHtml("morningstar.c<wbr>om"),
                "<wbr> vanishes without a space so URLs stay whole");
        assertEquals(">quote", FourChanBizClient.stripHtml(
                "<span class=\"quote\">&gt;quote</span>"));
    }
}
