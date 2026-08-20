package de.bsommerfeld.wsbg.terminal.web.impl.sources.tradingview;

import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradingView symbol search - fixtures are live response excerpts (2026-08-02).
 * {@code tradingview-symbol-search.json} is the "siemens" answer trimmed to
 * five representative rows (an XETR equity with ISIN, an NSE equity, an NSE
 * FUTURES row without ISIN, a Budapest listing of the German ISIN, a GETTEX
 * depositary receipt); {@code tradingview-symbol-search-isin.json} is the
 * {@code DE0007236101} answer, where every row carries
 * {@code found_by_isin: true} - and the {@code hl=1} highlighting wraps the
 * ISIN itself in {@code <em>…</em>}, which is the parser's real job here.
 * Ported from the module world.
 */
class TradingViewSymbolSearchTest {

    private static final String SEARCH = "symbol_search/v3/";

    @Test
    void parseReadsIsinAndInstrumentMetadataOffTheLiveAnswer() {
        TradingViewSymbolSearch.SearchResult result =
                TradingViewSymbolSearch.parse(FakeTradingViewFetcher.load("tradingview-symbol-search.json"));

        assertNotNull(result);
        assertEquals(689, result.remaining(), "symbols_remaining is the hit count beyond the page");
        assertEquals(5, result.hits().size());

        TradingViewSymbolSearch.SymbolHit enr = result.hits().get(0);
        assertEquals("ENR", enr.symbol());
        assertEquals("Siemens Energy AG", enr.description());
        assertEquals("stock", enr.type());
        assertEquals("XETR", enr.exchange());
        assertEquals("EUR", enr.currencyCode());
        assertEquals("DE000ENER6Y0", enr.isin(), "the ISIN is the whole point of this endpoint");
        assertEquals("ice", enr.providerId());
        assertEquals("DE", enr.country());
        assertTrue(enr.primaryListing());
        assertTrue(enr.isStock());
        assertEquals("XETR:ENR", enr.tvSymbol(), "the addressing form every other TV API wants");
    }

    @Test
    void parseStripsTheHl1HighlightMarkupFromEveryField() {
        List<TradingViewSymbolSearch.SymbolHit> hits =
                TradingViewSymbolSearch.parse(FakeTradingViewFetcher.load("tradingview-symbol-search.json")).hits();

        TradingViewSymbolSearch.SymbolHit nse = hits.get(1);
        assertEquals("SIEMENS", nse.symbol(), "<em> wrappers must never reach a consumer");
        assertFalse(nse.description().contains("<em>"));
        assertEquals("INE003A01024", nse.isin());
    }

    @Test
    void rowsWithoutAnIsinKeepNullRatherThanAnEmptyString() {
        TradingViewSymbolSearch.SymbolHit futures =
                TradingViewSymbolSearch.parse(FakeTradingViewFetcher.load("tradingview-symbol-search.json"))
                        .hits().get(2);
        assertEquals("futures", futures.type());
        assertNull(futures.isin(), "a futures row carries no ISIN - not an empty one");
        assertFalse(futures.isStock());
    }

    @Test
    void resolveByIsinKeepsOnlyRowsTheApiItselfMatchedByIsinPrimaryFirst() {
        FakeTradingViewFetcher fetcher = new FakeTradingViewFetcher()
                .onFixture(SEARCH, "tradingview-symbol-search-isin.json");
        TradingViewSymbolSearch search = new TradingViewSymbolSearch(fetcher);

        List<TradingViewSymbolSearch.SymbolHit> hits = search.resolveByIsin("DE0007236101");

        assertEquals(3, hits.size());
        assertTrue(hits.stream().allMatch(TradingViewSymbolSearch.SymbolHit::foundByIsin));
        assertTrue(hits.stream().allMatch(h -> "DE0007236101".equals(h.isin())),
                "the highlighted <em>ISIN</em> is cleaned before the equality check");
        assertEquals("XETR:SIE", hits.get(0).tvSymbol(), "primary listing sorts first");
        assertEquals("XETR:SIE", search.primaryListing("DE0007236101").orElseThrow().tvSymbol());
    }

    @Test
    void everyRequestCarriesTheMandatoryOriginAndRefererPair() {
        FakeTradingViewFetcher fetcher = new FakeTradingViewFetcher()
                .onFixture(SEARCH, "tradingview-symbol-search.json");
        new TradingViewSymbolSearch(fetcher).search("siemens");

        assertEquals(1, fetcher.calls.size());
        Map<String, String> headers = fetcher.calls.get(0).headers();
        assertEquals("https://www.tradingview.com", headers.get("Origin"),
                "without Origin the host answers 403");
        assertEquals("https://www.tradingview.com/", headers.get("Referer"));
        assertTrue(fetcher.calls.get(0).url().contains("text=siemens"));
        assertTrue(fetcher.calls.get(0).url().contains("hl=1"));
    }

    @Test
    void repeatedSearchesAreServedFromTheCache() {
        FakeTradingViewFetcher fetcher = new FakeTradingViewFetcher()
                .onFixture(SEARCH, "tradingview-symbol-search.json");
        TradingViewSymbolSearch search = new TradingViewSymbolSearch(fetcher);

        search.search("siemens");
        search.search("siemens");
        search.isinForName("siemens");

        assertEquals(1, fetcher.calls.size(), "instrument metadata does not move by the minute");
    }

    @Test
    void nameResolvesToTheFirstEquityIsinAndTheBestStockMatch() {
        FakeTradingViewFetcher fetcher = new FakeTradingViewFetcher()
                .onFixture(SEARCH, "tradingview-symbol-search.json");
        TradingViewSymbolSearch search = new TradingViewSymbolSearch(fetcher);

        assertEquals("DE000ENER6Y0", search.isinForName("siemens").orElseThrow());
        assertEquals("XETR:ENR", search.bestStockMatch("siemens").orElseThrow().tvSymbol(),
                "a futures/DR shell must never win the instrument resolution");
    }

    @Test
    void garbageAndMissesNeverThrow() {
        assertNull(TradingViewSymbolSearch.parse("<html>403</html>"));
        assertNull(TradingViewSymbolSearch.parse("{\"foo\":1}"), "no symbols array = not an answer");
        assertNull(TradingViewSymbolSearch.parse(""));
        assertTrue(TradingViewSymbolSearch.parse("{\"symbols_remaining\":0,\"symbols\":[]}").isEmpty(),
                "the pinned miss is a 200 with zero rows");

        FakeTradingViewFetcher fetcher = new FakeTradingViewFetcher()
                .on(SEARCH, WebResponse.text(403, "forbidden", Map.of()));
        TradingViewSymbolSearch search = new TradingViewSymbolSearch(fetcher);
        assertTrue(search.search("siemens").isEmpty());
        assertEquals(List.of(), search.resolveByIsin("DE0007236101"));
        assertTrue(search.search("  ").isEmpty());
    }
}
