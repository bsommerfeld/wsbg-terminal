package de.bsommerfeld.wsbg.terminal.briefing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the live-probed HTML shapes of TradingEconomics (fixtures fetched
 * 2026-08-02, trimmed): the {@code data-*} attributes on the calendar rows with
 * the importance riding as a CSS class, the Tailwind-rebuilt earnings board
 * whose ticker no longer sits in an attribute, the shared quote-table shape,
 * the keyless JSON stream and an indicator page with its release history,
 * components, related table and JSON-LD coverage span.
 */
class TradingEconomicsClientTest {

    private static String fixture(String name) {
        try (InputStream in = TradingEconomicsClientTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("fixture missing: " + name, e);
        }
    }

    // ---- /calendar ---------------------------------------------------------

    @Test
    void calendarReadsEveryDataAttributeOffTheRow() {
        List<TradingEconomicsClient.CalendarEvent> events =
                TradingEconomicsClient.parseCalendar(fixture("te-calendar.html"));
        assertEquals(5, events.size());
        TradingEconomicsClient.CalendarEvent china = events.get(0);
        assertEquals("/china/manufacturing-pmi", china.url());
        assertEquals("china", china.country());
        assertEquals("manufacturing pmi", china.category());
        assertEquals("ratingdog manufacturing pmi", china.event());
        assertEquals("CHINAMANPMI", china.symbol(), "data-symbol arrives single-quoted, unlike its siblings");
        assertEquals("2026-08-03", china.date());
        assertEquals("01:45 AM", china.time());
        assertEquals("RatingDog Manufacturing PMI", china.title());
    }

    @Test
    void calendarImportanceRidesAsTheCalendarDateClassAndFallsToZero() {
        List<TradingEconomicsClient.CalendarEvent> events =
                TradingEconomicsClient.parseCalendar(fixture("te-calendar.html"));
        assertEquals(3, events.get(0).importance());
        assertEquals(2, events.get(1).importance());
        assertEquals(1, events.get(2).importance());
        TradingEconomicsClient.CalendarEvent opec = events.get(3);
        assertEquals(0, opec.importance(), "an all-day entry carries no rating class at all");
        assertEquals("", opec.time());
        assertEquals("OPEC and non-OPEC Ministerial Meeting", opec.title(),
                "the all-day rows print their title in a bare span, not the event anchor");
    }

    @Test
    void calendarKeepsValuesVerbatimAndAnEmptyStringMeansNotYet() {
        List<TradingEconomicsClient.CalendarEvent> events =
                TradingEconomicsClient.parseCalendar(fixture("te-calendar.html"));
        TradingEconomicsClient.CalendarEvent china = events.get(0);
        assertEquals("", china.actual(), "the forward window has no actuals yet");
        assertEquals("51.7", china.previous());
        assertEquals("51.5", china.consensus());
        assertEquals("51.5", china.forecast());
        assertEquals("", events.get(1).consensus(), "an empty consensus anchor is an honest blank");
    }

    @Test
    void calendarEntitiesInTitlesArriveDecodedNotSpelled() {
        List<TradingEconomicsClient.CalendarEvent> events =
                TradingEconomicsClient.parseCalendar(fixture("te-calendar.html"));
        assertTrue(events.stream().anyMatch(e -> e.title().contains("S&P Global")),
                "an &amp; must never reach a reader");
    }

    @Test
    void calendarImportanceFilterIsTheServersJobAndTheParserJustAgrees() {
        // The ?importance=3 page IS the same markup, only shorter - so a client
        // filter over the fixture must land on exactly the rated rows.
        List<TradingEconomicsClient.CalendarEvent> high =
                TradingEconomicsClient.parseCalendar(fixture("te-calendar.html")).stream()
                        .filter(e -> e.importance() >= 3).toList();
        assertEquals(2, high.size());
        assertTrue(high.stream().allMatch(e -> e.importance() == 3));
    }

    @Test
    void calendarGarbageIsEmpty() {
        assertTrue(TradingEconomicsClient.parseCalendar(null).isEmpty());
        assertTrue(TradingEconomicsClient.parseCalendar("<html>wall</html>").isEmpty());
        assertTrue(TradingEconomicsClient.parseCalendar("").isEmpty());
    }

    // ---- /earnings ---------------------------------------------------------

    @Test
    void earningsCarriesTheDateDownFromTheSeparatorRows() {
        List<TradingEconomicsClient.EarningsEntry> docket =
                TradingEconomicsClient.parseEarnings(fixture("te-earnings.html"));
        assertEquals(5, docket.size());
        assertEquals("", docket.get(0).date(),
                "the rows above the first header are today's and get no date - a blank, not a guess");
        assertEquals("2026-08-03", entry(docket, "BRKB:US").date());
        assertEquals("2026-08-04", entry(docket, "AMD:US").date(),
                "the block header dates everything below it");
    }

    @Test
    void earningsFindsTheCompanyBehindTheAnchorThatIsNotTheTicker() {
        // Both anchors in the first cell share a class; only high-impact rows
        // are bold, so the name is simply the link that is not the symbol.
        TradingEconomicsClient.EarningsEntry echo =
                entry(TradingEconomicsClient.parseEarnings(fixture("te-earnings.html")), "SATS:US");
        assertEquals("EchoStar", echo.company());
        assertEquals("United States", echo.country());
        assertEquals(1, echo.impact());
    }

    private static TradingEconomicsClient.EarningsEntry entry(
            List<TradingEconomicsClient.EarningsEntry> docket, String symbol) {
        return docket.stream().filter(e -> e.symbol().equals(symbol)).findFirst().orElseThrow();
    }

    @Test
    void earningsReadsTheTickerOutOfTheSpanTheRebuiltPageUsesNow() {
        List<TradingEconomicsClient.EarningsEntry> docket =
                TradingEconomicsClient.parseEarnings(fixture("te-earnings.html"));
        TradingEconomicsClient.EarningsEntry brk = entry(docket, "BRKB:US");
        assertEquals("BRKB:US", brk.symbol(), "the Tailwind rebuild dropped data-symbol");
        assertEquals("Berkshire Hathaway", brk.company());
        assertEquals("United States", brk.country());
        assertEquals("Q2", brk.fiscalPeriod());
        assertEquals("AM", brk.time());
        assertEquals(3, brk.impact(), "the star colour is the only impact signal on this page");
    }

    @Test
    void earningsSplitsTheActualSlashConsensusCellAndDropsTheDash() {
        List<TradingEconomicsClient.EarningsEntry> docket =
                TradingEconomicsClient.parseEarnings(fixture("te-earnings.html"));
        TradingEconomicsClient.EarningsEntry brk = entry(docket, "BRKB:US");
        assertEquals("", brk.epsActual(), "an unreported print is a dash, which is not a number");
        assertEquals("4.92", brk.epsConsensus());
        assertEquals("5.17", brk.epsPrevious());
        assertEquals("", brk.revenueActual());
        assertEquals("96.52B", brk.revenueConsensus());
        assertEquals("92.52B", brk.revenuePrevious());
        assertEquals("$638.29B", brk.marketCap());
    }

    @Test
    void earningsKeepsAReportedRowWithItsActualBesideTheConsensus() {
        TradingEconomicsClient.EarningsEntry divi =
                entry(TradingEconomicsClient.parseEarnings(fixture("te-earnings.html")), "DIVI:IN");
        assertEquals("33.98", divi.epsActual());
        assertEquals("25.08", divi.epsConsensus());
        assertEquals("20.49", divi.epsPrevious());
        assertEquals("30.8B", divi.revenueActual());
        assertEquals("27.75B", divi.revenueConsensus());
    }

    @Test
    void earningsGarbageIsEmpty() {
        assertTrue(TradingEconomicsClient.parseEarnings(null).isEmpty());
        assertTrue(TradingEconomicsClient.parseEarnings("<table><tr><td>nope</td></tr></table>").isEmpty());
    }

    // ---- quote tables ------------------------------------------------------

    @Test
    void quoteTableReadsSymbolNameUnitAndTheSignedDataValues() {
        List<TradingEconomicsClient.Quote> quotes =
                TradingEconomicsClient.parseQuotes(fixture("te-commodities.html"));
        assertEquals(3, quotes.size());
        TradingEconomicsClient.Quote oil = quotes.get(0);
        assertEquals("CL1:COM", oil.symbol());
        assertEquals("Crude Oil", oil.name());
        assertEquals("USD/Bbl", oil.unit());
        assertEquals(82.309, oil.last(), 1e-9);
        assertEquals(-2.3607, oil.dayChange(), 1e-9,
                "the printed change is unsigned - only data-value knows the direction");
        assertEquals(-2.79, oil.percentChange(), 1e-9);
        assertEquals("Aug/02", oil.asOf());
    }

    @Test
    void quoteTableGarbageIsEmpty() {
        assertTrue(TradingEconomicsClient.parseQuotes(null).isEmpty());
        assertTrue(TradingEconomicsClient.parseQuotes("<html>wall</html>").isEmpty());
    }

    // ---- /ws/stream.ashx ---------------------------------------------------

    @Test
    void streamIsRealJsonAndKeepsTheAlreadyLinkedHtml() {
        List<TradingEconomicsClient.StreamItem> items =
                TradingEconomicsClient.parseStream(fixture("te-stream.json"));
        assertEquals(3, items.size());
        TradingEconomicsClient.StreamItem first = items.get(0);
        assertTrue(first.id() > 0);
        assertFalse(first.title().isBlank());
        assertFalse(first.date().isBlank());
        assertTrue(items.stream().anyMatch(i -> i.html().contains("stream-symbol-link")),
                "the html field ships the symbol anchors we would otherwise have to resolve");
    }

    @Test
    void streamGarbageIsEmpty() {
        assertTrue(TradingEconomicsClient.parseStream("<html>426 upgrade required</html>").isEmpty());
        assertTrue(TradingEconomicsClient.parseStream("{\"error\":\"nope\"}").isEmpty(),
                "the dead API answers objects, the live page answers an array");
        assertTrue(TradingEconomicsClient.parseStream(null).isEmpty());
    }

    // ---- indicator pages ---------------------------------------------------

    @Test
    void indicatorReadsTheReleaseHistoryIncludingTheUpcomingBlankOne() {
        TradingEconomicsClient.IndicatorPage page = TradingEconomicsClient.parseIndicator(
                "united-states", "inflation-cpi", fixture("te-indicator.html"));
        List<TradingEconomicsClient.Release> releases = page.releases();
        assertEquals(3, releases.size());
        TradingEconomicsClient.Release june = releases.get(0);
        assertEquals("2026-06-10", june.date());
        assertEquals("12:30 PM", june.time());
        assertEquals("May", june.reference());
        assertEquals("4.2%", june.actual());
        assertEquals("3.8%", june.previous());
        assertEquals("4.2%", june.consensus());
        assertEquals("4.0%", june.teForecast());
        TradingEconomicsClient.Release upcoming = releases.get(2);
        assertEquals("2026-08-12", upcoming.date());
        assertEquals("", upcoming.actual(), "an unreleased print is blank, never a stale repeat");
        assertEquals("3.4%", upcoming.teForecast());
    }

    @Test
    void indicatorSplitsComponentsFromRelatedAndKeepsTheirLinks() {
        TradingEconomicsClient.IndicatorPage page = TradingEconomicsClient.parseIndicator(
                "united-states", "inflation-cpi", fixture("te-indicator.html"));
        TradingEconomicsClient.IndicatorRow core = page.components().get(0);
        assertEquals("Core Inflation Rate YoY", core.name());
        assertEquals("/united-states/core-inflation-rate", core.url());
        assertEquals("2.60", core.last());
        assertEquals("2.90", core.previous());
        assertEquals("percent", core.unit());
        assertEquals("Jun 2026", core.reference());
        assertEquals(3, page.related().size());
        assertEquals("CPI", page.related().get(0).name());
        assertTrue(page.components().stream().noneMatch(r -> r.name().equals("CPI")),
                "the two tables must not bleed into each other");
    }

    @Test
    void indicatorTakesTheCoveredSpanFromTheJsonLdBecauseTheSeriesItselfIsAPng() {
        TradingEconomicsClient.IndicatorPage page = TradingEconomicsClient.parseIndicator(
                "united-states", "inflation-cpi", fixture("te-indicator.html"));
        assertEquals("1914-12-31/2026-06-30", page.temporalCoverage());
        assertEquals("united-states", page.country());
        assertEquals("inflation-cpi", page.indicator());
    }

    @Test
    void indicatorGarbageIsNull() {
        assertEquals(null, TradingEconomicsClient.parseIndicator("x", "y", "<html>wall</html>"));
        assertEquals(null, TradingEconomicsClient.parseIndicator("x", "y", null));
    }

    @Test
    void everyStreamStaysReachableWithoutANetworkAndAddressesTheRightPage() {
        // The mandate: each endpoint is wired even without a caller today.
        RecordingFetcher seen = new RecordingFetcher();
        TradingEconomicsClient client = new TradingEconomicsClient(seen);
        assertTrue(client.calendar().isEmpty());
        assertTrue(client.calendar(3).isEmpty());
        assertTrue(client.earnings().isEmpty());
        assertTrue(client.currencies().isEmpty());
        assertTrue(client.commodities().isEmpty());
        assertTrue(client.stocks().isEmpty());
        assertTrue(client.bonds().isEmpty());
        assertTrue(client.stream().isEmpty());
        assertTrue(client.stream(10).isEmpty());
        Optional<TradingEconomicsClient.IndicatorPage> page =
                client.indicator("United States", "Inflation CPI");
        assertTrue(page.isEmpty(), "a dead transport costs the page, never the tick");

        assertEquals(List.of(
                        "https://tradingeconomics.com/calendar",
                        "https://tradingeconomics.com/calendar?importance=3",
                        "https://tradingeconomics.com/earnings",
                        "https://tradingeconomics.com/currencies",
                        "https://tradingeconomics.com/commodities",
                        "https://tradingeconomics.com/stocks",
                        "https://tradingeconomics.com/bonds",
                        "https://tradingeconomics.com/ws/stream.ashx?start=0",
                        "https://tradingeconomics.com/ws/stream.ashx?start=10",
                        "https://tradingeconomics.com/united-states/inflation-cpi"),
                seen.urls,
                "the importance filter is a server-side query parameter, and a spoken "
                        + "country/indicator has to become the page slug");
    }

    /** A transport that answers nothing and remembers what was asked for. */
    private static final class RecordingFetcher implements de.bsommerfeld.wsbg.terminal.source.net.WebFetcher {

        private final List<String> urls = new java.util.ArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public de.bsommerfeld.wsbg.terminal.source.net.WebResponse fetch(
                String url, java.util.Map<String, String> headers, java.time.Duration timeout) {
            urls.add(url);
            return null;
        }
    }
}
