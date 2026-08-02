package de.bsommerfeld.wsbg.terminal.finanzennet;

import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.CompanyDate;
import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.EstimateRow;
import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.FinancialStatement;
import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.PriceTarget;
import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.TargetBoard;
import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.VenueQuote;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser tests against live-captured finanzen.net pages (SAP, 2026-08-02). */
class FinanzenNetMarketClientTest {

    // ------------------------------------------------------------- venues

    @Test
    void readsTheGermanVenueTableIncludingLangUndSchwarz() throws Exception {
        List<VenueQuote> quotes = FinanzenNetMarketClient.parseVenues(
                Fixtures.load("fn-boersenplaetze-sap.html"));

        assertFalse(quotes.isEmpty());
        VenueQuote stuttgart = quotes.get(0);
        assertEquals("Stuttgart", stuttgart.venue());
        assertEquals(159.00, stuttgart.last(), 1e-9);
        assertEquals(157.10, stuttgart.previousClose(), 1e-9);
        assertEquals(1.90, stuttgart.change(), 1e-9);
        assertEquals(1.21, stuttgart.changePercent(), 1e-9);
        assertEquals(152.80, stuttgart.dayLow(), 1e-9);
        assertEquals(159.00, stuttgart.dayHigh(), 1e-9);
        assertEquals(LocalTime.of(21, 57, 30), stuttgart.time());
        assertEquals(LocalDate.of(2026, 7, 31), stuttgart.date());
        assertEquals("EUR", stuttgart.currency());
        assertEquals("german", stuttgart.segment());

        assertTrue(quotes.stream().anyMatch(q -> q.venue().contains("Lang und Schwarz")),
                "the second EUR price leg must be in the table");
        for (String venue : List.of("XETRA", "Tradegate", "gettex", "Quotrix", "Baader")) {
            assertTrue(quotes.stream().anyMatch(q -> q.venue().contains(venue)), venue);
        }
    }

    @Test
    void germanNumberFormattingIsReadCorrectly() {
        // Dot = thousands, comma = decimal - the inverse of the machine convention.
        assertEquals(22631.0, HtmlTables.germanNumber("22.631"), 1e-9);
        assertEquals(159.0, HtmlTables.germanNumber("159,00 EUR"), 1e-9);
        assertEquals(1.21, HtmlTables.germanNumber("+1,21%"), 1e-9);
        assertEquals(-0.78, HtmlTables.germanNumber("-0,78"), 1e-9);
        assertEquals(40374.87, HtmlTables.germanNumber("40.374,87"), 1e-9);
        assertEquals(1234567.0, HtmlTables.germanNumber("1.234.567"), 1e-9);
        assertNull(HtmlTables.germanNumber("-"));
        assertNull(HtmlTables.germanNumber(""));
        assertNull(HtmlTables.germanNumber(null));
        assertEquals(LocalDate.of(2027, 1, 28), HtmlTables.germanDate("28.01.2027 (e)*"));
        assertNull(HtmlTables.germanDate("kein Datum"));
    }

    @Test
    void volumeIsNotTruncatedByTheThousandsDot() throws Exception {
        List<VenueQuote> quotes = FinanzenNetMarketClient.parseVenues(
                Fixtures.load("fn-boersenplaetze-sap.html"));

        VenueQuote stuttgart = quotes.get(0);
        assertEquals(22631L, stuttgart.volume(),
                "22.631 is twenty-two thousand shares, not 22.6");
    }

    @Test
    void venuesAreSegmentedIntoGermanOffExchangeAndForeign() throws Exception {
        List<VenueQuote> quotes = FinanzenNetMarketClient.parseVenues(
                Fixtures.load("fn-boersenplaetze-sap.html"));

        assertTrue(quotes.stream().anyMatch(q -> "german".equals(q.segment())));
        assertTrue(quotes.stream().anyMatch(q -> "off-exchange".equals(q.segment())));
        quotes.forEach(q -> assertNotNull(q.last()));
    }

    @Test
    void singleVenueLookupIsCaseInsensitiveAndSubstringBased() throws Exception {
        FinanzenNetMarketClient client = new FinanzenNetMarketClient(new Fixtures.FakeFetcher(
                Map.of("boersenplaetze", Fixtures.load("fn-boersenplaetze-sap.html"))));

        Optional<VenueQuote> ls = client.venueQuote("sap", "lang und schwarz");
        Optional<VenueQuote> xetra = client.venueQuote("sap", "XETRA");

        assertTrue(ls.isPresent());
        assertTrue(xetra.isPresent());
        assertEquals("EUR", ls.get().currency());
        assertTrue(client.venueQuote("sap", "Nasdaq Nowhere").isEmpty());
    }

    // ------------------------------------------------------------- targets

    @Test
    void readsAnalystPriceTargetsWithHouseTargetDistanceAndDate() throws Exception {
        TargetBoard board = FinanzenNetMarketClient.parseTargets(
                Fixtures.load("fn-kursziele-sap.html"));

        assertFalse(board.targets().isEmpty());
        PriceTarget first = board.targets().get(0);
        assertEquals("Jefferies & Company Inc.", first.analyst());
        assertEquals(210.00, first.target(), 1e-9);
        assertEquals("EUR", first.currency());
        assertEquals(31.91, first.upsidePercent(), 1e-9);
        assertEquals(LocalDate.of(2026, 7, 28), first.date());
    }

    @Test
    void readsTheConsensusDriftOverThreePointsInTime() throws Exception {
        TargetBoard board = FinanzenNetMarketClient.parseTargets(
                Fixtures.load("fn-kursziele-sap.html"));

        assertEquals(3, board.trend().size());
        assertEquals("Aktuell", board.trend().get(0).label());
        assertEquals(6, board.trend().get(0).buy());
        assertEquals(1, board.trend().get(0).hold());
        assertEquals(1, board.trend().get(0).sell());
        assertEquals("vor 1/2 Jahr", board.trend().get(1).label());
        assertEquals(9, board.trend().get(1).buy());
        assertEquals("vor 1 Jahr", board.trend().get(2).label());
    }

    @Test
    void readsThePeerGroupConsensusIncludingForeignCurrencyTargets() throws Exception {
        TargetBoard board = FinanzenNetMarketClient.parseTargets(
                Fixtures.load("fn-kursziele-sap.html"));

        assertFalse(board.peers().isEmpty());
        var adobe = board.peers().stream()
                .filter(p -> p.name().startsWith("Adobe"))
                .findFirst().orElseThrow();
        assertEquals(1, adobe.buy());
        assertEquals(1, adobe.sell());
        assertEquals(370.0, adobe.averageTarget(), 1e-9);
        assertEquals("USD", adobe.currency(), "peers are not all EUR");

        var noCoverage = board.peers().stream()
                .filter(p -> p.name().startsWith("BMC"))
                .findFirst().orElseThrow();
        assertNull(noCoverage.averageTarget(), "'-' means no coverage, not zero");
    }

    // ---------------------------------------------------------- estimates

    @Test
    void readsForwardConsensusPerPeriodWithAnalystCount() throws Exception {
        List<EstimateRow> rows = FinanzenNetMarketClient.parseEstimates(
                Fixtures.load("fn-schaetzungen-sap.html"));

        EstimateRow currentQuarter = rows.stream()
                .filter(r -> "eps".equals(r.metric()))
                .filter(r -> r.periodLabel().startsWith("aktuelles Quartal"))
                .findFirst().orElseThrow();
        assertEquals(17, currentQuarter.analysts());
        assertEquals(1.83, currentQuarter.meanEstimate(), 1e-9);
        assertEquals(1.72, currentQuarter.priorYear(), 1e-9);
        assertEquals("EUR", currentQuarter.currency());
        assertEquals(LocalDate.of(2026, 9, 30), currentQuarter.periodEnd());
        assertEquals(LocalDate.of(2026, 10, 21), currentQuarter.publication());
    }

    @Test
    void readsTheEstimateVersusActualSurpriseHistory() throws Exception {
        List<EstimateRow> rows = FinanzenNetMarketClient.parseEstimates(
                Fixtures.load("fn-schaetzungen-sap.html"));

        EstimateRow reported = rows.stream()
                .filter(r -> "eps".equals(r.metric()))
                .filter(r -> LocalDate.of(2025, 12, 31).equals(r.periodEnd()))
                .findFirst().orElseThrow();
        assertEquals(1.51, reported.meanEstimate(), 1e-9);
        assertEquals(1.58, reported.actual(), 1e-9);
        assertEquals(0.07, reported.surpriseAbsolute(), 1e-9);
        assertEquals(4.78, reported.surprisePercent(), 1e-9);

        assertTrue(rows.stream().anyMatch(r -> "revenue".equals(r.metric())),
                "revenue consensus is read too");
    }

    @Test
    void aDecemberQuarterAndTheFullYearDoNotOverwriteEachOther() throws Exception {
        List<EstimateRow> rows = FinanzenNetMarketClient.parseEstimates(
                Fixtures.load("fn-schaetzungen-sap.html"));

        List<EstimateRow> december = rows.stream()
                .filter(r -> "eps".equals(r.metric()))
                .filter(r -> LocalDate.of(2026, 12, 31).equals(r.periodEnd()))
                .toList();

        assertEquals(2, december.size(), "both the Q4 column and the FY column survive");
        assertTrue(december.stream().anyMatch(r -> r.periodLabel().startsWith("nächstes Quartal")));
        assertTrue(december.stream()
                .anyMatch(r -> r.periodLabel().startsWith("aktuelles Geschäftsjahr")));
        assertEquals(2.02, december.stream()
                .filter(r -> r.periodLabel().startsWith("nächstes Quartal"))
                .findFirst().orElseThrow().meanEstimate(), 1e-9);
        assertEquals(7.11, december.stream()
                .filter(r -> r.periodLabel().startsWith("aktuelles Geschäftsjahr"))
                .findFirst().orElseThrow().meanEstimate(), 1e-9);
    }

    // -------------------------------------------------------------- dates

    @Test
    void readsTheForwardCalendarWithEstimatedEpsAndEstimatedDates() throws Exception {
        List<CompanyDate> dates = FinanzenNetMarketClient.parseDates(
                Fixtures.load("fn-termine-sap.html"), false);

        assertFalse(dates.isEmpty());
        CompanyDate next = dates.get(0);
        assertEquals("Quartalszahlen", next.kind());
        assertEquals("Q3 2026", next.info());
        assertEquals(LocalDate.of(2026, 10, 21), next.date());
        assertFalse(next.dateEstimated());
        assertEquals(1.8299, next.eps(), 1e-9);
        assertFalse(next.epsIsActual(), "the forward table carries estimates");

        assertTrue(dates.stream().anyMatch(CompanyDate::dateEstimated),
                "'(e)*' marks a date finanzen.net only estimates");
    }

    @Test
    void readsThePastCalendarWithReportedEpsBackToTwentyTwentyTwo() throws Exception {
        List<CompanyDate> past = FinanzenNetMarketClient.parseDates(
                Fixtures.load("fn-termine-sap.html"), true);

        assertFalse(past.isEmpty());
        CompanyDate last = past.get(0);
        assertEquals(LocalDate.of(2026, 7, 23), last.date());
        assertEquals(1.89, last.eps(), 1e-9);
        assertTrue(last.epsIsActual());
        assertTrue(past.stream().anyMatch(d -> "Hauptversammlung".equals(d.kind())));
        assertTrue(past.stream().anyMatch(d -> d.date().getYear() <= 2024),
                "the history reaches back years");
    }

    // --------------------------------------------------------- financials

    @Test
    void readsTheMultiYearProfitAndLossAndBalanceSheet() throws Exception {
        List<FinancialStatement> statements = FinanzenNetMarketClient.parseFinancials(
                Fixtures.load("fn-bilanz-guv-sap.html"));

        assertTrue(statements.size() >= 3);
        FinancialStatement guv = statements.stream()
                .filter(s -> s.title().contains("GuV"))
                .findFirst().orElseThrow();
        assertTrue(guv.periods().containsAll(List.of("2025", "2024", "2023")));
        assertEquals(36800.0, guv.row("Umsatzerlöse").orElseThrow().byPeriod().get("2025"), 1e-9);
        assertEquals(27553.0, guv.row("Umsatzerlöse").orElseThrow().byPeriod().get("2019"), 1e-9);
        assertTrue(statements.stream().anyMatch(s -> s.title().contains("Bilanz")));
    }

    // --------------------------------------------------------- robustness

    @Test
    void garbageAndOutagesYieldEmptyResultsNotExceptions() {
        assertTrue(FinanzenNetMarketClient.parseVenues("<html>403</html>").isEmpty());
        assertTrue(FinanzenNetMarketClient.parseVenues("").isEmpty());
        assertTrue(FinanzenNetMarketClient.parseVenues(null).isEmpty());
        assertTrue(FinanzenNetMarketClient.parseEstimates("not html").isEmpty());
        assertTrue(FinanzenNetMarketClient.parseDates("", false).isEmpty());
        assertTrue(FinanzenNetMarketClient.parseFinancials(null).isEmpty());
        assertTrue(FinanzenNetMarketClient.parseTargets("<html/>").targets().isEmpty());

        Fixtures.FakeFetcher dead = new Fixtures.FakeFetcher(Map.of());
        dead.failAll = true;
        FinanzenNetMarketClient client = new FinanzenNetMarketClient(dead);
        assertTrue(client.venueQuotes("sap").isEmpty());
        assertTrue(client.priceTargets("sap").isEmpty());
        assertTrue(client.estimates("sap").isEmpty());
        assertTrue(client.venueQuotes("   ").isEmpty(), "a blank slug never leaves the process");
    }

    @Test
    void everyPageIsFetchedWithTheFullAkamaiHeaderSet() throws Exception {
        Fixtures.FakeFetcher fetcher = new Fixtures.FakeFetcher(Map.of(
                "boersenplaetze", Fixtures.load("fn-boersenplaetze-sap.html"),
                "kursziele", Fixtures.load("fn-kursziele-sap.html"),
                "schaetzungen", Fixtures.load("fn-schaetzungen-sap.html"),
                "termine", Fixtures.load("fn-termine-sap.html"),
                "bilanz_guv", Fixtures.load("fn-bilanz-guv-sap.html")));
        FinanzenNetMarketClient client = new FinanzenNetMarketClient(fetcher);

        client.venueQuotes("sap");
        client.targetBoard("sap");
        client.estimates("sap");
        client.upcomingDates("sap");
        client.financials("sap");

        assertEquals(5, fetcher.calls.size());
        fetcher.headers.forEach(h -> {
            assertEquals(12, h.size(), "the complete set, nothing dropped");
            assertTrue(h.containsKey("sec-ch-ua"));
            assertEquals("document", h.get("Sec-Fetch-Dest"));
        });
    }
}
