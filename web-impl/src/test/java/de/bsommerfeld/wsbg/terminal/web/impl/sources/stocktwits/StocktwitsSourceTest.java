package de.bsommerfeld.wsbg.terminal.web.impl.sources.stocktwits;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stocktwits per-symbol stream — the fixture is a REAL archived answer
 * (Wayback snapshot 2025-05-26 of {@code streams/symbol/AAPL.json}; the live
 * wall blocks bare probes), trimmed to 4 messages: one Bullish, one Bearish,
 * one with an HTML entity in the body, one untagged. Ported 1:1 from the
 * module world.
 */
class StocktwitsSourceTest {

    private static String fixture() {
        try (InputStream in = StocktwitsSourceTest.class
                .getResourceAsStream("/stocktwits-symbol-stream.json")) {
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
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            assertTrue(url.startsWith("https://api.stocktwits.com/api/2/streams/symbol/")
                    && url.endsWith(".json"), "stream URL shape: " + url);
            assertEquals(FetchUtil.BROWSER, modes[0],
                    "the joker leads — Cloudflare walls the bare client");
            int n = fetches.getAndIncrement();
            return replies.get(Math.min(n, replies.size() - 1));
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

    private static WebResponse stream() {
        return WebResponse.text(200, fixture(), Map.of());
    }

    private static ResolvedInstrument withTicker(String symbol) {
        return new ResolvedInstrument(Optional.empty(), Ticker.parse(symbol), "");
    }

    @Test
    void parseMapsThePinnedArchivedFields() {
        List<Article> items = StocktwitsSource.parse(fixture());
        assertEquals(4, items.size(), "all fixture messages parse");

        Article bullish = items.get(0);
        assertTrue(bullish.title().startsWith("(Bullish) "),
                "the user's own mood tag LEADS the text: " + bullish.title());
        assertEquals("Stocktwits (@PatriciaGaddya)", bullish.publisher());
        assertEquals("stocktwits:615858587", bullish.uuid());
        assertEquals("https://stocktwits.com/PatriciaGaddya/message/615858587",
                bullish.link(), "permalink = user + message id");
        assertTrue(bullish.publishedAt().isAfter(Instant.parse("2025-05-26T00:00:00Z")));

        assertTrue(items.get(1).summary().startsWith("(Bearish) "), items.get(1).summary());

        Article entity = items.get(2);
        assertFalse(entity.summary().startsWith("("), "untagged posts carry no mood prefix");
        assertTrue(entity.summary().contains("It's best to avoid him"),
                "&#39; decodes to an apostrophe: " + entity.summary());
    }

    @Test
    void parseReturnsNullForNonStreamBodiesAndEmptyForEmptyStream() {
        // The Cloudflare challenge page is a 200-shaped HTML — NOT a stream.
        assertNull(StocktwitsSource.parse("<!doctype html><html>Just a moment...</html>"));
        assertNull(StocktwitsSource.parse("not json"));
        assertNull(StocktwitsSource.parse(""));
        assertNull(StocktwitsSource.parse(null));
        assertNull(StocktwitsSource.parse("{\"response\":{\"status\":200}}"),
                "a JSON body without messages[] is not the stream");
        assertEquals(0, StocktwitsSource.parse(
                "{\"messages\":[],\"response\":{\"status\":200}}").size(),
                "an empty messages[] is a VALID empty, not a miss");
    }

    @Test
    void symbolGateIsPrecisionOverRecall() {
        assertEquals("AAPL", StocktwitsSource.streamSymbol("AAPL"));
        assertEquals("AAPL", StocktwitsSource.streamSymbol(" aapl "));
        assertEquals("BRK.B", StocktwitsSource.streamSymbol("BRK-B"),
                "Yahoo's class separator maps to Stocktwits' dot");
        assertNull(StocktwitsSource.streamSymbol("RHM.DE"),
                "an exchange suffix is NEVER cut — same letters, wrong US ticker");
        assertNull(StocktwitsSource.streamSymbol("^GDAXI"));
        assertNull(StocktwitsSource.streamSymbol(""));
        assertNull(StocktwitsSource.streamSymbol(null));

        FakeFetcher fetcher = new FakeFetcher(stream());
        StocktwitsSource source = new StocktwitsSource(fetcher);
        assertTrue(source.newsFor(withTicker("RHM.DE"), 10).isEmpty(),
                "a suffixed home symbol has no US address here");
        assertTrue(source.newsFor(ResolvedInstrument.ofName("Apple"), 10).isEmpty(),
                "no ticker resolved = no address — the name never carries the query");
        assertTrue(source.newsFor(new ResolvedInstrument(
                Isin.parse("US0378331005"), Optional.empty(), ""), 10).isEmpty(),
                "an ISIN alone has no address — Stocktwits knows tickers only");
        assertEquals(0, fetcher.fetches.get(), "gated queries never fetch");
    }

    @Test
    void cacheAnswersABurstWithOneFetchAndCapsAtLimit() {
        FakeFetcher fetcher = new FakeFetcher(stream());
        StocktwitsSource source = new StocktwitsSource(fetcher);
        assertEquals(4, source.newsFor(withTicker("AAPL"), 10).size());
        assertEquals(2, source.newsFor(withTicker("AAPL"), 2).size(), "limit caps the stream");
        assertEquals(1, fetcher.fetches.get(), "the stream is cached per symbol");
    }

    @Test
    void challengeShellIsAMissAndNeverPoisonsTheCache() {
        FakeFetcher fetcher = new FakeFetcher(
                WebResponse.text(200, "<!doctype html><html><head><title>Just a moment..."
                        + "</title></head></html>", Map.of()),
                stream());
        StocktwitsSource source = new StocktwitsSource(fetcher);
        assertTrue(source.newsFor(withTicker("AAPL"), 10).isEmpty(),
                "a challenge shell 200 is a miss, not an empty stream");
        assertEquals(4, source.newsFor(withTicker("AAPL"), 10).size(),
                "the miss was NOT cached — the next call refetches and succeeds");
        assertEquals(2, fetcher.fetches.get());
    }

    @Test
    void fourOhFourIsADefinitiveEmptyAndIsCached() {
        FakeFetcher fetcher = new FakeFetcher(WebResponse.text(404, "", Map.of()));
        StocktwitsSource source = new StocktwitsSource(fetcher);
        assertTrue(source.newsFor(withTicker("QQXYZ"), 10).isEmpty());
        assertTrue(source.newsFor(withTicker("QQXYZ"), 10).isEmpty());
        assertEquals(1, fetcher.fetches.get(),
                "404 = no such symbol, a definitive empty that IS cached");
    }

    @Test
    void anOutageServesTheStaleStream() {
        FakeFetcher fetcher = new FakeFetcher(stream(), WebResponse.text(500, "", Map.of()));
        StocktwitsSource source = new StocktwitsSource(fetcher);
        assertEquals(4, source.newsFor(withTicker("AAPL"), 10).size());
        assertEquals(4, source.newsFor(withTicker("AAPL"), 10).size(), "TTL hit — still one fetch");
        assertEquals(1, fetcher.fetches.get());
    }
}
