package de.bsommerfeld.wsbg.terminal.web.impl.sources.tradingview;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradingView news mediator - fixtures are live excerpts (2026-08-02).
 * {@code tradingview-news-flow.json} is the German wire trimmed to six items
 * (three paywalled: Reuters with {@code permission: headline}, two dpa-AFX;
 * three free: GlobeNewswire, EQS, and a pressetext item that is
 * {@code paywall:false} but {@code permission: preview} - the case that proves
 * the gate is not the paywall flag alone). {@code tradingview-news-symbol.json}
 * is the {@code NASDAQ:NVDA} answer (two free, two Dow Jones items behind the
 * wall), {@code tradingview-story.json} the free Invezz story with its
 * {@code astDescription} tree. Ported from the module world.
 */
class TradingViewNewsSourceTest {

    private static final String FLOW = "news-flow/v2/news";
    private static final String SYMBOL_NEWS = "public/view/v1/symbol";
    private static final String STORY = "v2/story";
    private static final String SEARCH = "symbol_search/v3/";

    private static TradingViewNewsSource source(FakeTradingViewFetcher fetcher) {
        return new TradingViewNewsSource(fetcher, new TradingViewSymbolSearch(fetcher));
    }

    private static ResolvedInstrument byTicker(String symbol) {
        return new ResolvedInstrument(Optional.empty(), Ticker.parse(symbol), "");
    }

    private static ResolvedInstrument byIsin(String isin) {
        return new ResolvedInstrument(Isin.parse(isin), Optional.empty(), "");
    }

    @Test
    void parseReadsTheGermanWireAndMarksEveryGatedItemOnThePublisher() {
        List<Article> items = TradingViewNewsSource.parseItems(
                FakeTradingViewFetcher.load("tradingview-news-flow.json"));

        assertNotNull(items);
        assertEquals(6, items.size());

        Article reuters = items.get(0);
        assertEquals("tag:reuters.com,2026:newsml_L8N43Z08X:0", reuters.uuid());
        assertEquals("Reuters (paywall)", reuters.publisher(),
                "licensed agency copy is marked, CNBC-Pro style");
        assertTrue(TradingViewNewsSource.isGated(reuters.publisher()));
        assertTrue(reuters.title().contains("Prysmian"));
        assertEquals(Instant.ofEpochSecond(1785701692), reuters.publishedAt());

        Article free = items.get(1);
        assertEquals("GlobeNewswire", free.publisher(), "a free item keeps the bare provider name");
        assertFalse(TradingViewNewsSource.isGated(free.publisher()));
    }

    @Test
    void permissionPreviewCountsAsGatedEvenWhenThePaywallFlagIsFalse() {
        List<Article> items = TradingViewNewsSource.parseItems(
                FakeTradingViewFetcher.load("tradingview-news-flow.json"));

        Article pressetext = items.get(5);
        assertEquals("pressetext (paywall)", pressetext.publisher(),
                "'preview' means TradingView may show a stub - the body is not ours to take");
    }

    @Test
    void anItemWithoutItsOwnLinkFallsBackToTheTradingViewStoryPermalink() {
        List<Article> items = TradingViewNewsSource.parseItems(
                FakeTradingViewFetcher.load("tradingview-news-flow.json"));

        assertTrue(items.get(0).link().startsWith("https://www.tradingview.com/news/"),
                "agency items carry no provider URL - storyPath is the permalink");
        assertTrue(items.get(1).link().startsWith("https://www.globenewswire.com/"),
                "where the provider gives its own URL, that one wins");
    }

    @Test
    void relatedSymbolsBecomeHouseTickersAndUnprovenVenuesAreDropped() {
        List<Article> items = TradingViewNewsSource.parseItems(
                FakeTradingViewFetcher.load("tradingview-news-flow.json"));

        assertEquals(List.of("ATKR"), items.get(0).relatedTickers(),
                "NYSE:ATKR maps, MIL:PRY is an unproven venue and is dropped");
        assertEquals(List.of("SNG.DE"), items.get(5).relatedTickers(), "XETR maps to the .DE suffix");
        assertEquals(List.of(), items.get(1).relatedTickers(), "ASX is unproven - never guessed");

        assertEquals("SIE.DE", TradingViewNewsSource.houseSymbol("XETR:SIE"));
        assertEquals("NVDA", TradingViewNewsSource.houseSymbol("NASDAQ:NVDA"));
        assertNull(TradingViewNewsSource.houseSymbol("MIL:PRY"));
        assertNull(TradingViewNewsSource.houseSymbol(null));
    }

    @Test
    void perSymbolAnswerParsesWithTheSameShapeAsTheWire() {
        List<Article> items = TradingViewNewsSource.parseItems(
                FakeTradingViewFetcher.load("tradingview-news-symbol.json"));

        assertEquals(4, items.size());
        assertEquals("Invezz", items.get(0).publisher());
        assertEquals("Dow Jones Newswires (paywall)", items.get(2).publisher());
        assertTrue(items.get(0).relatedTickers().containsAll(List.of("NVDA", "META", "GOOG")));
    }

    @Test
    void newsForMapsAHouseSymbolOntoTheTradingViewVenueAndSortsNewestFirst() throws Exception {
        FakeTradingViewFetcher fetcher = new FakeTradingViewFetcher()
                .onFixture(SYMBOL_NEWS, "tradingview-news-symbol.json");

        List<Article> items = source(fetcher).newsFor(byTicker("NVDA"), 10);

        assertEquals(4, items.size());
        assertTrue(fetcher.urls().get(0).contains("symbol%3ANASDAQ%3ANVDA"),
                "a naked US symbol tries NASDAQ first - the minds source's proven mapping");
        assertTrue(items.get(0).publishedAt().isAfter(items.get(3).publishedAt()));
        assertEquals("https://www.tradingview.com", fetcher.calls.get(0).headers().get("Origin"));
    }

    @Test
    void aGermanHouseSymbolIsAddressedAsXetr() throws Exception {
        FakeTradingViewFetcher fetcher = new FakeTradingViewFetcher()
                .onFixture(SYMBOL_NEWS, "tradingview-news-symbol.json");

        source(fetcher).newsFor(byTicker("SIE.DE"), 5);

        assertTrue(fetcher.urls().get(0).contains("symbol%3AXETR%3ASIE"));
        assertEquals(List.of(), source(new FakeTradingViewFetcher()).newsFor(byTicker("AIR.PA"), 5),
                "an unproven venue suffix is an honest no-op, not a guessed fetch");
    }

    @Test
    void nameAndIsinKeysGoThroughTheSymbolSearch() throws Exception {
        FakeTradingViewFetcher fetcher = new FakeTradingViewFetcher()
                .onFixture(SEARCH, "tradingview-symbol-search-isin.json")
                .onFixture(SYMBOL_NEWS, "tradingview-news-symbol.json");

        List<Article> byIsin = source(fetcher).newsFor(byIsin("DE0007236101"), 3);

        assertEquals(3, byIsin.size());
        assertTrue(fetcher.fetched("symbol%3AXETR%3ASIE"), "the ISIN resolved to the primary listing");
        assertTrue(fetcher.fetched("filter=lang%3Ade"), "a German listing reads the German pool");

        FakeTradingViewFetcher byName = new FakeTradingViewFetcher()
                .onFixture(SEARCH, "tradingview-symbol-search.json")
                .onFixture(SYMBOL_NEWS, "tradingview-news-symbol.json");
        assertFalse(source(byName)
                .newsFor(ResolvedInstrument.ofName("Siemens Energy"), 3).isEmpty());
        assertTrue(byName.fetched("symbol%3AXETR%3AENR"));
    }

    @Test
    void theGeneralGermanWireIsTheInstrumentFreeDoor() {
        FakeTradingViewFetcher fetcher = new FakeTradingViewFetcher()
                .onFixture(FLOW, "tradingview-news-flow.json");
        TradingViewNewsSource news = source(fetcher);

        List<Article> items = news.latestGerman(4);

        assertEquals(4, items.size());
        assertTrue(fetcher.urls().get(0).contains("filter=lang%3Ade"));
        assertTrue(fetcher.urls().get(0).contains("client=screener"));
        assertTrue(items.get(0).publishedAt().isAfter(items.get(3).publishedAt()), "newest first");

        news.latestGerman(2);
        assertEquals(1, fetcher.calls.size(), "the wire is TTL-cached, a second read costs no fetch");
    }

    @Test
    void aGatedItemNeverTriggersAFullTextFetch() {
        FakeTradingViewFetcher fetcher = new FakeTradingViewFetcher()
                .onFixture(FLOW, "tradingview-news-flow.json");
        TradingViewNewsSource news = source(fetcher);

        Article reuters = news.latestGerman(6).stream()
                .filter(i -> TradingViewNewsSource.isGated(i.publisher()))
                .findFirst().orElseThrow();

        assertTrue(news.fullText(reuters).isEmpty(),
                "the story endpoint WOULD serve the body - taking it is the thing we refuse");
        assertFalse(fetcher.fetched(STORY), "not a single request may go out for a gated body");
        assertEquals(1, fetcher.calls.size());
    }

    @Test
    void aFreeItemDoesFetchTheStoryBody() {
        FakeTradingViewFetcher fetcher = new FakeTradingViewFetcher()
                .onFixture(FLOW, "tradingview-news-flow.json")
                .onFixture(STORY, "tradingview-story.json");
        TradingViewNewsSource news = source(fetcher);

        Article free = news.latestGerman(6).stream()
                .filter(i -> !TradingViewNewsSource.isGated(i.publisher()))
                .findFirst().orElseThrow();

        String text = news.fullText(free).orElseThrow();

        assertTrue(fetcher.fetched(STORY));
        assertTrue(text.contains("Cramer"), "the astDescription tree is rendered to clear text");
        assertEquals("https://www.tradingview.com", fetcher.calls.get(1).headers().get("Origin"));
    }

    @Test
    void storyParsesBodyTagsAndRelatedSymbols() {
        TradingViewNewsSource.Story story = TradingViewNewsSource.parseStory(
                FakeTradingViewFetcher.load("tradingview-story.json"));

        assertNotNull(story);
        assertEquals("invezz:3edd11aab094b:0", story.id());
        assertEquals("Invezz", story.source());
        assertEquals(Instant.ofEpochSecond(1785668400), story.published());
        assertEquals(List.of("NASDAQ:NVDA", "NASDAQ:META", "NASDAQ:GOOG"), story.relatedSymbols());
        assertEquals(List.of("Analysts", "Invezz", "US stocks"), story.tags());
        assertTrue(story.text().contains("dip-buying rule"));
        assertFalse(story.text().contains("news-image"), "image nodes contribute no body text");
        assertTrue(story.text().lines().count() > 2, "paragraphs stay separate lines");
    }

    @Test
    void astRenderingIsDepthBoundedAndTolerantOfTornTrees() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder deep = new StringBuilder("{\"type\":\"root\",\"children\":[\"top\"");
        for (int i = 0; i < 60; i++) deep.append(",{\"type\":\"p\",\"children\":[\"x\"");
        for (int i = 0; i < 60; i++) deep.append("]}");
        deep.append("]}");

        String rendered = TradingViewNewsSource.renderAst(mapper.readTree(deep.toString()));

        assertTrue(rendered.startsWith("top"));
        assertEquals("", TradingViewNewsSource.renderAst(mapper.readTree("{}")));
        assertEquals("", TradingViewNewsSource.renderAst(null));
    }

    @Test
    void garbageAndFailuresNeverThrowAndNeverInventItems() throws Exception {
        assertNull(TradingViewNewsSource.parseItems("<html>403</html>"));
        assertNull(TradingViewNewsSource.parseItems("{\"foo\":1}"), "no items array = no answer");
        assertNull(TradingViewNewsSource.parseItems(""));
        assertEquals(List.of(), TradingViewNewsSource.parseItems("{\"items\":[]}"));
        assertNull(TradingViewNewsSource.parseStory("nonsense"));
        assertNull(TradingViewNewsSource.parseStory("{\"id\":\"x\"}"), "a story without a title is none");

        FakeTradingViewFetcher dead = new FakeTradingViewFetcher()
                .on(FLOW, WebResponse.text(403, "forbidden", Map.of()))
                .on(SYMBOL_NEWS, WebResponse.text(500, "boom", Map.of()))
                .on(SEARCH, WebResponse.text(403, "forbidden", Map.of()));
        TradingViewNewsSource news = source(dead);
        assertEquals(List.of(), news.latestGerman(5));
        assertEquals(List.of(), news.newsFor(byTicker("NVDA"), 5));
        assertEquals(List.of(), news.newsFor(null, 5));
        assertEquals(List.of(), news.newsFor(ResolvedInstrument.ofName(""), 5));
        assertEquals(List.of(), news.latest("de", 0));
        assertTrue(news.story("  ", "en").isEmpty());
        assertTrue(news.fullText(null).isEmpty());
    }

    @Test
    void theSourceDeclaresItselfAsReportedNewsNotRoomChatter() {
        TradingViewNewsSource news = new TradingViewNewsSource(new FakeTradingViewFetcher(),
                new TradingViewSymbolSearch(new FakeTradingViewFetcher()));

        assertEquals("tradingview-news", news.sourceName());
        assertFalse(news.socialSentiment(), "the minds source carries the sentiment leg, this one does not");
    }
}
