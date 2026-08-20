package de.bsommerfeld.wsbg.terminal.web.impl.sources.fourchan;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
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
 * /GME/ ticker general, a "$XRP" all-in thread, a SUB-LESS thread (title must
 * fall back to the OP text), a BTC thread whose sub carries the live
 * {@code &#039;} entity, and a COM-LESS thread (sub only, "Chainlink").
 * Ported from the module world; the collector delivers every thread
 * unfiltered — pool caching, symbol and name matching left with the old
 * NewsSource fan, and the local token bucket fell to the house fetcher's
 * pacing.
 */
class FourChanBizSourceTest {

    private static String fixture() {
        try (InputStream in = FourChanBizSourceTest.class
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
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            fetches.getAndIncrement();
            switch (url) {
                case "https://a.4cdn.org/biz/catalog.json" -> {
                    int n = bizFetches.getAndIncrement();
                    return bizReplies.get(Math.min(n, bizReplies.size() - 1));
                }
                case "https://a.4cdn.org/news/catalog.json" -> {
                    return WebResponse.text(200, NEWS_CATALOG, Map.of());
                }
                case "https://a.4cdn.org/g/catalog.json" -> {
                    return WebResponse.text(200, G_CATALOG, Map.of());
                }
                default -> throw new AssertionError("unexpected catalog URL: " + url);
            }
        }

        @Override
        public WebResponse fetchBinary(String url, Map<String, String> headers,
                Duration timeout, FetchUtil... modes) {
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

    /** Minimal valid /news/ catalog: one news-link thread. */
    private static final String NEWS_CATALOG = "[{\"page\":1,\"threads\":[{\"no\":111,"
            + "\"sub\":\"Trump reports over $1.4 billion in income from crypto ventures\","
            + "\"com\":\"filing drops today\",\"replies\":50,\"time\":1752600000}]}]";

    /** Minimal valid /g/ catalog: one tech thread. */
    private static final String G_CATALOG = "[{\"page\":1,\"threads\":[{\"no\":222,"
            + "\"sub\":\"Nvidia driver megathread\",\"replies\":10,\"time\":1752600000}]}]";

    private static WebResponse catalog() {
        return WebResponse.text(200, fixture(), Map.of());
    }

    @Test
    void parseMapsTheLiveCatalogFields() {
        List<Article> threads = FourChanBizSource.parse(fixture(), "biz");
        assertEquals(6, threads.size(), "the answer is unfiltered — all catalog threads parse");

        Article smg = threads.get(0);
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

        // The live &#039; entity in sub decodes to an apostrophe.
        assertEquals("Who's still buying BTC at this level?", threads.get(4).title());

        // SUB-LESS thread: the title falls back to the stripped OP text.
        Article subless = threads.get(3);
        assertTrue(subless.title().startsWith("When did you realize every BTC increase"),
                "no sub — title comes from the OP text");

        // COM-LESS thread: sub-only, summary is just the reply count.
        Article comless = threads.get(5);
        assertEquals("they're doing to OIL what they did to Chainlink", comless.title());
        assertEquals("(35 Antworten)", comless.summary());
    }

    @Test
    void parseToleratesGarbageAnswers() {
        assertTrue(FourChanBizSource.parse("<!doctype html><html>404</html>", "biz").isEmpty());
        assertTrue(FourChanBizSource.parse("not json at all", "biz").isEmpty());
        assertTrue(FourChanBizSource.parse("{\"threads\": \"wrong shape\"}", "biz").isEmpty(),
                "a non-array root is not the catalog");
        assertTrue(FourChanBizSource.parse("[{\"page\":1,\"threads\":\"nope\"}]", "biz").isEmpty());
        assertTrue(FourChanBizSource.parse("[{\"page\":1,\"threads\":[{\"replies\":5}]}]", "biz")
                .isEmpty(), "a thread without an id is dropped");
        assertTrue(FourChanBizSource.parse("", "biz").isEmpty());
        assertTrue(FourChanBizSource.parse(null, "biz").isEmpty());
        // Truncated mid-array (a torn response) — never throws.
        assertTrue(FourChanBizSource.parse(fixture().substring(0, 200), "biz").isEmpty());
    }

    @Test
    void collectUnitesAllThreeBoards() {
        FakeFetcher fetcher = new FakeFetcher(catalog());
        FourChanBizSource source = new FourChanBizSource(fetcher);

        List<Article> all = source.collect();
        assertEquals(8, all.size(), "6 /biz/ + 1 /news/ + 1 /g/ — one unioned pass");
        assertEquals(3, fetcher.fetches.get(), "ONE request per board per pass");
        assertEquals(1, fetcher.bizFetches.get());

        assertTrue(all.stream().anyMatch(a -> "4chan /news/".equals(a.publisher())),
                "a /news/ thread carries its board as publisher");
        assertTrue(all.stream().anyMatch(a -> "4chan /g/".equals(a.publisher())),
                "/g/ by-catch rides the same union");
    }

    @Test
    void garbageAnswersAreAMissNotAnEmptyBoard() {
        FakeFetcher fetcher = new FakeFetcher(
                WebResponse.text(200, "<!doctype html><html><body>4chan - banned"
                        + "</body></html>", Map.of()),
                catalog());
        FourChanBizSource source = new FourChanBizSource(fetcher);

        assertEquals(2, source.collect().size(),
                "the HTML 200 on /biz/ is a miss — the other boards still deliver");
        assertEquals(8, source.collect().size(),
                "nothing was cached — the next pass refetches /biz/ and succeeds");
        assertEquals(2, fetcher.bizFetches.get());
    }

    @Test
    void sourceSelfDescription() {
        FourChanBizSource source = new FourChanBizSource(new FakeFetcher(catalog()));
        assertEquals("fourchan-biz", source.sourceName());
        assertTrue(source.socialSentiment(),
                "room opinion rides the sentiment fan, never the press loom");
        assertEquals(FetchUtil.DIRECT, source.mode()[0], "direct-first transport");
    }

    @Test
    void entityDecodingHandlesTheLiveSetAndNumericReferences() {
        assertEquals("Who's still buying?",
                FourChanBizSource.decodeEntities("Who&#039;s still buying?"));
        assertEquals("A & B > \"C\"",
                FourChanBizSource.decodeEntities("A &amp; B &gt; &quot;C&quot;"));
        assertEquals("€", FourChanBizSource.decodeEntities("&#8364;"));
        assertEquals("€", FourChanBizSource.decodeEntities("&#x20AC;"));
        assertEquals("&gt;", FourChanBizSource.decodeEntities("&amp;gt;"),
                "double-escaped stays literal — &amp; decodes LAST");

        assertEquals("a b", FourChanBizSource.stripHtml("a<br><br>b"));
        assertEquals("morningstar.com", FourChanBizSource.stripHtml("morningstar.c<wbr>om"),
                "<wbr> vanishes without a space so URLs stay whole");
        assertEquals(">quote", FourChanBizSource.stripHtml(
                "<span class=\"quote\">&gt;quote</span>"));
    }
}
