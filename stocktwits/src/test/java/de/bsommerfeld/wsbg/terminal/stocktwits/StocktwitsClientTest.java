package de.bsommerfeld.wsbg.terminal.stocktwits;

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
 * Stocktwits per-symbol stream — the fixture is a REAL archived answer
 * (Wayback snapshot 2025-05-26 of {@code streams/symbol/AAPL.json}; the live
 * wall blocks bare probes), trimmed to 4 messages: one Bullish, one Bearish,
 * one with an HTML entity in the body, one untagged.
 */
class StocktwitsClientTest {

    private static String fixture() {
        try (InputStream in = StocktwitsClientTest.class
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
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            assertTrue(url.startsWith("https://api.stocktwits.com/api/2/streams/symbol/")
                    && url.endsWith(".json"), "stream URL shape: " + url);
            int n = fetches.getAndIncrement();
            return replies.get(Math.min(n, replies.size() - 1));
        }
    }

    private static WebResponse stream() {
        return new WebResponse(200, fixture(), Map.of());
    }

    @Test
    void parseMapsThePinnedArchivedFields() {
        List<RawNewsItem> items = StocktwitsClient.parse(fixture());
        assertEquals(4, items.size(), "all fixture messages parse");

        RawNewsItem bullish = items.get(0);
        assertTrue(bullish.title().startsWith("(Bullish) "),
                "the user's own mood tag LEADS the text: " + bullish.title());
        assertEquals("Stocktwits (@PatriciaGaddya)", bullish.publisher());
        assertEquals("stocktwits:615858587", bullish.uuid());
        assertEquals("https://stocktwits.com/PatriciaGaddya/message/615858587",
                bullish.link(), "permalink = user + message id");
        assertTrue(bullish.publishedAt().isAfter(Instant.parse("2025-05-26T00:00:00Z")));

        assertTrue(items.get(1).summary().startsWith("(Bearish) "), items.get(1).summary());

        RawNewsItem entity = items.get(2);
        assertFalse(entity.summary().startsWith("("), "untagged posts carry no mood prefix");
        assertTrue(entity.summary().contains("It's best to avoid him"),
                "&#39; decodes to an apostrophe: " + entity.summary());
    }

    @Test
    void parseReturnsNullForNonStreamBodiesAndEmptyForEmptyStream() {
        // The Cloudflare challenge page is a 200-shaped HTML — NOT a stream.
        assertNull(StocktwitsClient.parse("<!doctype html><html>Just a moment...</html>"));
        assertNull(StocktwitsClient.parse("not json"));
        assertNull(StocktwitsClient.parse(""));
        assertNull(StocktwitsClient.parse(null));
        assertNull(StocktwitsClient.parse("{\"response\":{\"status\":200}}"),
                "a JSON body without messages[] is not the stream");
        assertEquals(0, StocktwitsClient.parse(
                "{\"messages\":[],\"response\":{\"status\":200}}").size(),
                "an empty messages[] is a VALID empty, not a miss");
    }

    @Test
    void symbolGateIsPrecisionOverRecall() {
        assertEquals("AAPL", StocktwitsClient.streamSymbol("AAPL"));
        assertEquals("AAPL", StocktwitsClient.streamSymbol(" aapl "));
        assertEquals("BRK.B", StocktwitsClient.streamSymbol("BRK-B"),
                "Yahoo's class separator maps to Stocktwits' dot");
        assertNull(StocktwitsClient.streamSymbol("RHM.DE"),
                "an exchange suffix is NEVER cut — same letters, wrong US ticker");
        assertNull(StocktwitsClient.streamSymbol("^GDAXI"));
        assertNull(StocktwitsClient.streamSymbol(""));
        assertNull(StocktwitsClient.streamSymbol(null));

        FakeFetcher fetcher = new FakeFetcher(stream());
        StocktwitsClient client = new StocktwitsClient(fetcher);
        assertTrue(client.newsFor("RHM.DE", 10).isEmpty());
        assertTrue(client.newsForName("Apple", 10).isEmpty(), "name leg is a no-op");
        assertTrue(client.newsForIsin("US0378331005", 10).isEmpty(), "ISIN leg is a no-op");
        assertEquals(0, fetcher.fetches.get(), "gated queries never fetch");
    }

    @Test
    void cacheAnswersABurstWithOneFetchAndCapsAtLimit() {
        FakeFetcher fetcher = new FakeFetcher(stream());
        StocktwitsClient client = new StocktwitsClient(fetcher);
        assertEquals(4, client.newsFor("AAPL", 10).size());
        assertEquals(2, client.newsFor("AAPL", 2).size(), "limit caps the stream");
        assertEquals(1, fetcher.fetches.get(), "the stream is cached per symbol");
    }

    @Test
    void challengeShellIsAMissAndNeverPoisonsTheCache() {
        FakeFetcher fetcher = new FakeFetcher(
                new WebResponse(200, "<!doctype html><html><head><title>Just a moment..."
                        + "</title></head></html>", Map.of()),
                stream());
        StocktwitsClient client = new StocktwitsClient(fetcher);
        assertTrue(client.newsFor("AAPL", 10).isEmpty(),
                "a challenge shell 200 is a miss, not an empty stream");
        assertEquals(4, client.newsFor("AAPL", 10).size(),
                "the miss was NOT cached — the next call refetches and succeeds");
        assertEquals(2, fetcher.fetches.get());
    }

    @Test
    void fourOhFourIsADefinitiveEmptyAndIsCached() {
        FakeFetcher fetcher = new FakeFetcher(new WebResponse(404, "", Map.of()));
        StocktwitsClient client = new StocktwitsClient(fetcher);
        assertTrue(client.newsFor("QQXYZ", 10).isEmpty());
        assertTrue(client.newsFor("QQXYZ", 10).isEmpty());
        assertEquals(1, fetcher.fetches.get(),
                "404 = no such symbol, a definitive empty that IS cached");
    }

    @Test
    void anOutageServesTheStaleStream() {
        FakeFetcher fetcher = new FakeFetcher(stream(), new WebResponse(500, "", Map.of()));
        StocktwitsClient client = new StocktwitsClient(fetcher);
        assertEquals(4, client.newsFor("AAPL", 10).size());
        assertEquals(4, client.newsFor("AAPL", 10).size(), "TTL hit — still one fetch");
        assertEquals(1, fetcher.fetches.get());
    }
}
