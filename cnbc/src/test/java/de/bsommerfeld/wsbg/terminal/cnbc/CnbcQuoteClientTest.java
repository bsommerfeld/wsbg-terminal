package de.bsommerfeld.wsbg.terminal.cnbc;

import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser tests against live-captured CNBC market-data fixtures (2026-08-02). */
class CnbcQuoteClientTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = CnbcQuoteClientTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- number normalisation ----

    @Test
    void stripsThousandSeparatorsPercentAndMagnitudeSuffixes() {
        assertEquals(7489.72, CnbcQuoteClient.number("7,489.72"));
        assertEquals(4.718, CnbcQuoteClient.number("4.718%"));
        assertEquals(4.514e12, CnbcQuoteClient.number("4.514T"));
        assertEquals(5.2e6, CnbcQuoteClient.number("5.2M"));
        assertEquals(1.5e9, CnbcQuoteClient.number("1.5B"));
        assertEquals(-1.55, CnbcQuoteClient.number("-1.55"));
        assertEquals(2.0, CnbcQuoteClient.number("+2"));
    }

    @Test
    void missingNumbersStayNullNeverZero() {
        assertNull(CnbcQuoteClient.number(null));
        assertNull(CnbcQuoteClient.number(""));
        assertNull(CnbcQuoteClient.number("-"));
        assertNull(CnbcQuoteClient.number("--"));
        assertNull(CnbcQuoteClient.number("N/A"));
        assertNull(CnbcQuoteClient.number("UNCH"));
        assertNull(CnbcQuoteClient.number("gibberish"));
    }

    @Test
    void parsesUsDatesIncludingTheEstimateMarker() {
        assertEquals(LocalDate.of(2026, 10, 28), CnbcQuoteClient.usDate("10/28/2026(est)"));
        assertEquals(LocalDate.of(2026, 8, 10), CnbcQuoteClient.usDate("08/10/2026"));
        assertNull(CnbcQuoteClient.usDate(""));
        assertNull(CnbcQuoteClient.usDate("soon"));
    }

    // ---- quotes ----

    @Test
    void parsesEveryAssetClassFromOneCall() throws Exception {
        List<CnbcQuoteClient.Quote> quotes =
                CnbcQuoteClient.parseQuotes(fixture("cnbc-quotes.json"));

        assertEquals(6, quotes.size());
        List<String> symbols = quotes.stream().map(CnbcQuoteClient.Quote::symbol).toList();
        assertTrue(symbols.containsAll(
                List.of("AAPL", "SAP-DE", ".SPX", "@CL.1", "EUR=", "US10Y")));
    }

    @Test
    void carriesXetraQuotesInEuros() throws Exception {
        CnbcQuoteClient.Quote sap = CnbcQuoteClient.parseQuotes(fixture("cnbc-quotes.json"))
                .stream().filter(q -> q.symbol().equals("SAP-DE")).findFirst().orElseThrow();

        assertEquals("EUR", sap.currency());
        assertEquals("XETRA", sap.exchange());
        assertEquals(157.68, sap.last());
        assertEquals("SAP SE", sap.name());
    }

    @Test
    void carriesTheFundamentalsYahooLocksBehindACrumb() throws Exception {
        CnbcQuoteClient.Quote apple = CnbcQuoteClient.parseQuotes(fixture("cnbc-quotes.json"))
                .stream().filter(q -> q.symbol().equals("AAPL")).findFirst().orElseThrow();

        assertEquals(35.55, apple.peTrailing());
        assertEquals(8.69, apple.epsTrailing());
        assertEquals(1.04, apple.beta());
        assertEquals(4.537e12, apple.marketCap());
        assertEquals(0.35, apple.dividendYieldPercent());
        assertEquals(140.91, apple.returnOnEquityPercent());
        assertNotNull(apple.yearHigh());
        assertNotNull(apple.yearLow());
    }

    @Test
    void carriesExtendedHoursAndEventDates() throws Exception {
        CnbcQuoteClient.Quote apple = CnbcQuoteClient.parseQuotes(fixture("cnbc-quotes.json"))
                .stream().filter(q -> q.symbol().equals("AAPL")).findFirst().orElseThrow();

        assertNotNull(apple.extended());
        assertEquals("POST_MKT", apple.extended().type());
        assertEquals(307.36, apple.extended().last());
        assertEquals(-0.50, apple.extended().changePercent());
        assertEquals(LocalDate.of(2026, 10, 28), apple.nextEarningsDate());
        assertEquals(LocalDate.of(2026, 8, 10), apple.exDividendDate());
        assertFalse(apple.halted());
    }

    @Test
    void anIndexHasNoFundamentalsAndSaysSo() throws Exception {
        CnbcQuoteClient.Quote spx = CnbcQuoteClient.parseQuotes(fixture("cnbc-quotes.json"))
                .stream().filter(q -> q.symbol().equals(".SPX")).findFirst().orElseThrow();

        assertEquals(7489.72, spx.last(), "thousands separator survives parsing");
        assertNull(spx.marketCap(), "an index carries no market cap - null, not zero");
    }

    @Test
    void quoteGarbageYieldsEmptyListNotException() {
        assertTrue(CnbcQuoteClient.parseQuotes("{\"FormattedQuoteResult\":null}").isEmpty());
        assertTrue(CnbcQuoteClient.parseQuotes("<html>nope</html>").isEmpty());
        assertTrue(CnbcQuoteClient.parseQuotes("").isEmpty());
        assertTrue(CnbcQuoteClient.parseQuotes(null).isEmpty());
    }

    // ---- earnings ----

    @Test
    void parsesEstimateAndActualPerQuarter() throws Exception {
        List<CnbcQuoteClient.EarningsQuarter> q =
                CnbcQuoteClient.parseEarnings(fixture("cnbc-earnings.json"));

        assertFalse(q.isEmpty());
        CnbcQuoteClient.EarningsQuarter first = q.get(0);
        assertEquals("NVDA", first.symbol());
        assertEquals(1.757, first.epsEstimate());
        assertEquals(2.39, first.epsActualGaap());
        assertEquals(1.87, first.epsActualAdjusted());
        assertEquals(78854995000d, first.revenueEstimate());
        assertEquals(81615000000d, first.revenueActual());
        assertEquals("UP", first.surprise());
        assertNotNull(first.announcedAt());
        assertFalse(first.beforeMarket(), "NVDA reports after the bell");
    }

    @Test
    void surpriseIsRecomputableFromTheNumbers() throws Exception {
        CnbcQuoteClient.EarningsQuarter first =
                CnbcQuoteClient.parseEarnings(fixture("cnbc-earnings.json")).get(0);

        boolean beat = first.epsActualAdjusted() > first.epsEstimate();
        assertTrue(beat, "the raw numbers must support the verdict, not just echo it");
        assertEquals("UP", first.surprise());
    }

    @Test
    void earningsGarbageYieldsEmptyListNotException() {
        assertTrue(CnbcQuoteClient.parseEarnings("{\"earnings\":{}}").isEmpty());
        assertTrue(CnbcQuoteClient.parseEarnings("nope").isEmpty());
        assertTrue(CnbcQuoteClient.parseEarnings(null).isEmpty());
    }

    // ---- movers ----

    @Test
    void parsesMoversWithPreAndAfterHoursMove() throws Exception {
        List<CnbcQuoteClient.Mover> movers =
                CnbcQuoteClient.parseMovers(fixture("cnbc-movers.json"));

        assertFalse(movers.isEmpty());
        CnbcQuoteClient.Mover top = movers.get(0);
        assertNotNull(top.symbol());
        assertNotNull(top.lastPrice());
        assertNotNull(top.changePercent());
        assertNotNull(top.volume());
        assertNotNull(top.volumeVsTenDayAverage());
    }

    @Test
    void moversGarbageYieldsEmptyListNotException() {
        assertTrue(CnbcQuoteClient.parseMovers("{\"rankedSymbolList\":[]}").isEmpty());
        assertTrue(CnbcQuoteClient.parseMovers("nope").isEmpty());
        assertTrue(CnbcQuoteClient.parseMovers(null).isEmpty());
    }

    // ---- request shaping ----

    @Test
    void batchesLargeSymbolListsAcrossCalls() {
        FakeFetcher fetcher = new FakeFetcher();
        CnbcQuoteClient client = new CnbcQuoteClient(fetcher);

        List<String> many = new ArrayList<>();
        for (int i = 0; i < CnbcQuoteClient.MAX_SYMBOLS_PER_CALL * 2 + 3; i++) {
            many.add("SYM" + i);
        }
        client.quotes(many);

        assertEquals(3, fetcher.calls.size(), "53 symbols must split into three calls");
    }

    @Test
    void emptyInputNeverHitsTheNetwork() {
        FakeFetcher fetcher = new FakeFetcher();
        CnbcQuoteClient client = new CnbcQuoteClient(fetcher);

        assertTrue(client.quotes(List.of()).isEmpty());
        assertNull(client.quote("  "));
        assertTrue(client.earnings(null, 8).isEmpty());
        assertTrue(client.earnings("NVDA", 0).isEmpty());
        assertTrue(client.movers("", "TOP").isEmpty());
        assertTrue(fetcher.calls.isEmpty());
    }

    private static final class FakeFetcher implements WebFetcher {
        final List<String> calls = new ArrayList<>();

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            calls.add(url);
            return new WebResponse(200, "{}", Map.of());
        }
    }
}
