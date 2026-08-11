package de.bsommerfeld.wsbg.terminal.web.impl.sources.tradingview;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradingView Minds — fixture is a live response excerpt (2026-07-16)
 * trimmed to 5 minds: four NASDAQ:AAPL posts (one with a url node — a
 * chart snapshot link — and one entry-plan post long enough to exercise
 * the title cut) plus one XETR:RHM post whose flat {@code text} carries
 * the live quirk (symbol node rendered as a quoted {@code "XETR:RHM"}) —
 * why the clear text comes from {@code text_ast}, not from {@code text}.
 * The pinned miss shape (wrong exchange / unknown symbol) is a 200 with
 * empty {@code results}, never an error status. Ported from the module
 * world.
 */
class TradingViewMindsSourceTest {

    private static final String API = "https://www.tradingview.com/api/v1/minds/?symbol=";
    private static final String EMPTY_MISS = "{\"results\":[],\"meta\":{\"symbols_info\":{}}}";

    private static String fixture() {
        try (InputStream in = TradingViewMindsSourceTest.class
                .getResourceAsStream("/tradingview-minds.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing", e);
        }
    }

    private static ResolvedInstrument byTicker(String symbol) {
        return new ResolvedInstrument(Optional.empty(), Ticker.parse(symbol), "");
    }

    /** Fake transport answering per-URL reply sequences, recording every fetched URL. */
    private static final class FakeFetcher implements WebFetcher {
        final List<String> fetched = new ArrayList<>();
        private final Map<String, List<WebResponse>> replies = new LinkedHashMap<>();

        FakeFetcher on(String tvSymbol, WebResponse... rs) {
            replies.put(API + tvSymbol.replace(":", "%3A"), new ArrayList<>(List.of(rs)));
            return this;
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            fetched.add(url);
            List<WebResponse> rs = replies.get(url);
            if (rs == null) throw new AssertionError("unexpected fetch: " + url);
            return rs.size() > 1 ? rs.remove(0) : rs.get(0);
        }

        @Override
        public WebResponse fetchBinary(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
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

    private static WebResponse ok(String body) {
        return WebResponse.text(200, body, Map.of());
    }

    @Test
    void parseMapsTheLiveMindsFields() {
        List<Article> items = TradingViewMindsSource.parse(fixture());
        assertEquals(5, items.size());

        Article first = items.get(0);
        assertEquals("UY7zjRYCRG2B6-tEFPkNnQ", first.uuid(), "uuid is the mind's uid");
        assertEquals("TradingView Minds (sunnynirmaan)", first.publisher());
        assertEquals("https://www.tradingview.com/symbols/NASDAQ-AAPL/minds/"
                        + "?mind=UY7zjRYCRG2B6-tEFPkNnQ", first.link(),
                "the link is the result's own url field — the direct post permalink");
        assertEquals(Instant.parse("2026-07-16T02:21:52.942629Z"), first.publishedAt(),
                "created is ISO with microseconds and a +00:00 offset");
        assertEquals("AAPL now everyone want a piece when the move is almost done,"
                + " you are late", first.summary());
        assertEquals(first.summary(), first.title(), "short post: title = full clear text");

        // The url node (a chart snapshot link) renders as the URL itself.
        assertTrue(items.get(3).summary()
                        .endsWith("\n\nhttps://www.tradingview.com/x/MxNdNiQ5/"),
                "url nodes surface as their target, newlines survive in the summary");

        // The XETR post: the symbol node renders as the bare ticker — NOT the
        // quoted "XETR:RHM" garble the flat text field carries live.
        assertEquals("RHM How many times need to try to break it xD",
                items.get(4).summary());
        assertEquals("TradingView Minds (dexterica)", items.get(4).publisher());
    }

    @Test
    void longPostsGetAWordBoundaryTitleCut() {
        List<Article> items = TradingViewMindsSource.parse(fixture());
        Article plan = items.get(2); // the multi-line entry-plan post
        assertEquals("AAPL AAPL – BUY Entry: 327.40-327.50 TP1: 331.00 TP2: 335.00"
                        + " TP3: 340.00 SL: 323.00 Reason: AAPL holding above 326.00…",
                plan.title(), "newlines collapsed, cut at a word boundary, ellipsis");
        assertTrue(plan.summary().contains("\nEntry: 327.40-327.50\n"),
                "the summary keeps the full text including newlines");
        assertEquals("kurz und gut", TradingViewMindsSource.makeTitle("kurz \n und\tgut"),
                "whitespace collapses, short text passes through untouched");
        assertEquals(121, TradingViewMindsSource.makeTitle("y".repeat(300)).length(),
                "one giant token gets a hard cut at 120 plus the ellipsis");
    }

    @Test
    void dotDeMapsToXetr() throws Exception {
        FakeFetcher fetcher = new FakeFetcher().on("XETR:SAP", ok(fixture()));
        TradingViewMindsSource source = new TradingViewMindsSource(fetcher);
        assertEquals(5, source.newsFor(byTicker("SAP.DE"), 10).size());
        assertEquals(List.of(API + "XETR%3ASAP"), fetcher.fetched,
                ".DE maps to XETR: — ONE fetch, no exchange guessing");
    }

    @Test
    void nakedSymbolFallsBackNasdaqToNyseAndRemembersTheVenue() throws Exception {
        FakeFetcher fetcher = new FakeFetcher()
                .on("NASDAQ:KO", ok(EMPTY_MISS)) // the pinned miss: 200, empty results
                .on("NYSE:KO", ok(fixture()));
        TradingViewMindsSource source = new TradingViewMindsSource(fetcher);

        assertEquals(5, source.newsFor(byTicker("KO"), 10).size());
        assertEquals(List.of(API + "NASDAQ%3AKO", API + "NYSE%3AKO"), fetcher.fetched,
                "NASDAQ first, NYSE on the empty-results miss");

        assertEquals(5, source.newsFor(byTicker("KO"), 10).size());
        assertEquals(2, fetcher.fetched.size(),
                "within the TTL the cache answers — the fallback never re-fires");
    }

    @Test
    void nasdaqHitNeverTriesNyse() throws Exception {
        FakeFetcher fetcher = new FakeFetcher().on("NASDAQ:AAPL", ok(fixture()));
        TradingViewMindsSource source = new TradingViewMindsSource(fetcher);
        assertEquals(2, source.newsFor(byTicker("AAPL"), 2).size(), "limit caps the answer");
        assertEquals(List.of(API + "NASDAQ%3AAAPL"), fetcher.fetched);
    }

    @Test
    void aBurstOnOneSymbolMakesOneFetch() throws Exception {
        FakeFetcher fetcher = new FakeFetcher().on("NASDAQ:AAPL", ok(fixture()));
        TradingViewMindsSource source = new TradingViewMindsSource(fetcher);
        assertEquals(5, source.newsFor(byTicker("AAPL"), 10).size());
        assertEquals(5, source.newsFor(byTicker("aapl"), 10).size(), "symbol is case-normalised");
        assertEquals(5, source.newsFor(byTicker("AAPL"), 10).size());
        assertEquals(1, fetcher.fetched.size(), "the per-symbol cache answers the burst");
    }

    @Test
    void unknownVenueSuffixIsANoOpWithoutAFetch() throws Exception {
        FakeFetcher fetcher = new FakeFetcher();
        TradingViewMindsSource source = new TradingViewMindsSource(fetcher);
        assertTrue(source.newsFor(byTicker("AIR.PA"), 10).isEmpty(), ".PA is unproven — no-op");
        assertTrue(source.newsFor(byTicker("RR.L"), 10).isEmpty(), ".L is unproven — no-op");
        assertTrue(source.newsFor(null, 10).isEmpty());
        assertTrue(source.newsFor(byTicker("AAPL"), 0).isEmpty());
        assertTrue(source.newsFor(ResolvedInstrument.ofName("Apple"), 10).isEmpty(),
                "minds are symbol-addressed — a bare name is a no-op");
        assertTrue(source.newsFor(new ResolvedInstrument(
                        Isin.parse("US0378331005"), Optional.empty(), ""), 10).isEmpty(),
                "the endpoint has no ISIN surface — a bare ISIN is a no-op");
        assertEquals(0, fetcher.fetched.size(),
                "no-op legs and unproven venues never touch the network");
    }

    @Test
    void garbageAnswersAreAMissAndNeverPoisonTheCache() throws Exception {
        assertNull(TradingViewMindsSource.parse("<html><body>challenge</body></html>"),
                "an HTML shell is not a minds answer");
        assertNull(TradingViewMindsSource.parse("{\"torn\": tru"));
        assertNull(TradingViewMindsSource.parse("{\"no\":\"results array\"}"));
        assertNull(TradingViewMindsSource.parse(""));
        assertNull(TradingViewMindsSource.parse(null));
        assertTrue(TradingViewMindsSource.parse(EMPTY_MISS).isEmpty(),
                "the pinned miss shape parses as a clean empty answer");
        // Torn results (a mind without uid/url/text) are skipped, never a crash.
        assertTrue(TradingViewMindsSource.parse(
                "{\"results\":[{\"foo\":1},{\"uid\":\"x\"}]}").isEmpty());

        // A garbage 200 on both venues: nothing cached, the next call refetches.
        FakeFetcher fetcher = new FakeFetcher()
                .on("NASDAQ:KO", ok("<html>cloudflare</html>"), ok(fixture()))
                .on("NYSE:KO", WebResponse.text(503, "", Map.of()));
        TradingViewMindsSource source = new TradingViewMindsSource(fetcher);
        assertTrue(source.newsFor(byTicker("KO"), 10).isEmpty(), "garbage answers yield nothing");
        assertEquals(5, source.newsFor(byTicker("KO"), 10).size(),
                "the failure was NOT cached — the retry succeeds on NASDAQ");
    }

    @Test
    void aCleanDoubleMissIsCachedSoTheTtlPacesTheRetry() throws Exception {
        FakeFetcher fetcher = new FakeFetcher()
                .on("NASDAQ:ZZZQ", ok(EMPTY_MISS))
                .on("NYSE:ZZZQ", ok(EMPTY_MISS));
        TradingViewMindsSource source = new TradingViewMindsSource(fetcher);
        assertTrue(source.newsFor(byTicker("ZZZQ"), 10).isEmpty());
        assertTrue(source.newsFor(byTicker("ZZZQ"), 10).isEmpty());
        assertEquals(2, fetcher.fetched.size(),
                "a clean empty miss IS cached — no re-hammering within the TTL");
    }

    @Test
    void symbolCandidateMapping() {
        assertEquals(List.of("XETR:RHM"),
                TradingViewMindsSource.tvSymbolCandidates("RHM.DE"));
        assertEquals(List.of("NASDAQ:KO", "NYSE:KO"),
                TradingViewMindsSource.tvSymbolCandidates("KO"));
        assertEquals(List.of("XETR:SAP"),
                TradingViewMindsSource.tvSymbolCandidates("XETR:SAP"),
                "an already exchange-qualified symbol passes through");
        assertTrue(TradingViewMindsSource.tvSymbolCandidates("AIR.PA").isEmpty());
        assertTrue(TradingViewMindsSource.tvSymbolCandidates(".DE").isEmpty(),
                "a bare suffix has no base symbol");
    }

    @Test
    void nonEquityShapesNeverGetAnEquityVenue() {
        // Indices, FX pairs, futures and crypto pairs cannot sit on NASDAQ/NYSE —
        // asking anyway is a guaranteed 422 per symbol, per caller.
        for (String house : List.of("^DJI", "^TECDAX", "^N225", "JPY=X", "USD=X",
                "JPYUSD=X", "CL=F", "BZ=F", "BTC-USD", "ETH-EUR")) {
            assertTrue(TradingViewMindsSource.tvSymbolCandidates(house).isEmpty(), house);
            assertFalse(TradingViewMindsSource.isEquityShaped(house), house);
        }
        // A US share class carries a one-letter tail and stays eligible.
        assertEquals(List.of("NASDAQ:BRK-B", "NYSE:BRK-B"),
                TradingViewMindsSource.tvSymbolCandidates("BRK-B"));
        assertTrue(TradingViewMindsSource.isEquityShaped("KO"));
        assertTrue(TradingViewMindsSource.isEquityShaped("RHM.DE"));
    }

    @Test
    void createdParsingIsTolerant() {
        assertEquals(Instant.parse("2026-07-16T02:21:52.942629Z"),
                TradingViewMindsSource.parseCreated("2026-07-16T02:21:52.942629+00:00"));
        assertEquals(Instant.parse("2026-07-16T00:00:00Z"),
                TradingViewMindsSource.parseCreated("2026-07-16T02:00:00+02:00"));
        assertNull(TradingViewMindsSource.parseCreated("gestern"));
        assertNull(TradingViewMindsSource.parseCreated(""));
        assertNull(TradingViewMindsSource.parseCreated(null));
    }

    @Test
    void theSourceDeclaresItselfAsRoomChatter() {
        TradingViewMindsSource source = new TradingViewMindsSource(new FakeFetcher());
        assertEquals("tradingview-minds", source.sourceName());
        assertTrue(source.socialSentiment(), "room opinion rides the sentiment fan");
    }
}
