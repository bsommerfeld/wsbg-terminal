package de.bsommerfeld.wsbg.terminal.onvista;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static de.bsommerfeld.wsbg.terminal.onvista.OnvistaFixtures.ScriptedFetcher;
import static de.bsommerfeld.wsbg.terminal.onvista.OnvistaFixtures.json;
import static de.bsommerfeld.wsbg.terminal.onvista.OnvistaFixtures.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network-free parse tests against trimmed live captures (2026-08-02, SAP /
 * DAX). The behaviours that matter here: RLT vs DLY per venue, the two-stage
 * EOD read that turns ~22 days into three decades, the bulk POST body, and the
 * columnar arrays that must truncate rather than misalign.
 */
class OnvistaMarketClientTest {

    private static final OnvistaEntity SAP =
            new OnvistaEntity("STOCK", "82849", "SAP", "DE0007164600", "716460", "SAP");

    // ------------------------------------------------------------- quotes

    @Test
    void quotesSeparateRealtimeVenuesFromDelayedOnes() {
        var venues = OnvistaMarketClient.parseQuotes(json("quotes-sap.json"));
        assertEquals(5, venues.size());

        var xetra = venue(venues, "Xetra");
        assertEquals(163500L, xetra.idNotation());
        assertEquals("DLY", xetra.quality());
        assertFalse(xetra.realtime(), "Xetra is delayed on onvista");

        var tradegate = venue(venues, "Tradegate BSX");
        assertEquals(9386131L, tradegate.idNotation());
        assertTrue(tradegate.realtime());
        assertTrue(tradegate.realtimeBook());
        assertEquals(158.98, tradegate.bid(), 1e-9);
        assertEquals(159.56, tradegate.ask(), 1e-9);

        assertTrue(venue(venues, "gettex").realtime());
        assertEquals(3389771L, venue(venues, "Lang & Schwarz").idNotation());
        assertFalse(venue(venues, "Swiss Exchange").realtime());

        assertEquals(3, venues.stream().filter(v -> v.realtime()).count());
    }

    @Test
    void spreadIsComputedOnlyWhereThereIsABook() {
        var venues = OnvistaMarketClient.parseQuotes(json("quotes-sap.json"));
        // Xetra's row carries no bid/ask at all — no spread may be invented.
        assertTrue(Double.isNaN(venue(venues, "Xetra").spreadPct()));
        double lus = venue(venues, "Lang & Schwarz").spreadPct();
        assertTrue(lus > 0 && lus < 5, "implausible spread: " + lus);
        // The thin venue must show the wider spread.
        assertTrue(venue(venues, "Swiss Exchange").spreadPct()
                > venue(venues, "Tradegate BSX").spreadPct());
    }

    @Test
    void bulkPostBodyCarriesEveryEntityAndTheReplyParses() {
        var apple = new OnvistaEntity("STOCK", "86627", "Apple", "US0378331005", "865985", "APC");
        assertEquals("{\"list\":[{\"entityType\":\"STOCK\",\"entityValue\":\"82849\"},"
                        + "{\"entityType\":\"STOCK\",\"entityValue\":\"86627\"}]}",
                OnvistaMarketClient.quoteListBody(List.of(SAP, apple)));

        var bulk = OnvistaMarketClient.parseQuotes(json("quote-list-bulk.json"));
        assertEquals(2, bulk.size());
        // One row per instrument, each on onvista's own default venue.
        assertTrue(bulk.stream().allMatch(q -> q.last() > 0));
    }

    // ---------------------------------------------------------- eod history

    @Test
    void bareMaxRangeAnswersOnlyAShallowWindow() {
        var probe = OnvistaMarketClient.parseEodHistory(json("eod-probe.json")).orElseThrow();
        assertEquals(5, probe.bars().size());
        // …but it DECLARES the full availability, which is what stage two uses.
        assertEquals(LocalDate.of(1996, 12, 13), probe.startAvailable());
        assertEquals("Xetra", probe.marketName());
        assertEquals(163500L, probe.idNotation());
    }

    @Test
    void eodHistoryIsATwoStageReadThatFeedsBackTheDeclaredStart() {
        var fetcher = new ScriptedFetcher(raw("eod-probe.json"), raw("eod-full.json"));
        var history = new OnvistaMarketClient(fetcher).eodHistory(SAP, 163500L).orElseThrow();

        assertEquals(2, fetcher.urls.size());
        assertFalse(fetcher.urls.get(0).contains("startDate="), "the probe must NOT pin a start");
        assertTrue(fetcher.urls.get(1).contains("startDate=1996-12-13"),
                "stage two must reuse the probed start: " + fetcher.urls.get(1));
        assertTrue(fetcher.urls.get(1).contains("perPage=10000"));
        assertTrue(fetcher.urls.get(1).contains("idNotation=163500"));

        // The deep read reaches back to the 1996 bars the probe never saw.
        assertEquals(6, history.bars().size());
        assertEquals(LocalDate.of(1996, 12, 13), history.bars().get(0).date());
        assertEquals(8.862393, history.bars().get(0).open(), 1e-9);
        assertEquals(8.819785, history.bars().get(0).close(), 1e-9);
    }

    @Test
    void aThinnerSecondStageNeverLosesBars() {
        // Stage two answering an error must not downgrade the probe's result.
        var fetcher = new ScriptedFetcher(raw("eod-probe.json"), null);
        var history = new OnvistaMarketClient(fetcher).eodHistory(SAP, 163500L).orElseThrow();
        assertEquals(5, history.bars().size());
    }

    @Test
    void shortColumnsTruncateRatherThanMisalign() {
        // onvista serves EOD as parallel columns; a short column must cut the
        // series, never shift a close onto the wrong day.
        var node = OnvistaApi.JSON.createObjectNode();
        node.putArray("datetimeLast").add(1700000000L).add(1700086400L).add(1700172800L);
        node.putArray("first").add(1.0).add(2.0).add(3.0);
        node.putArray("last").add(1.5).add(2.5);
        node.putArray("high").add(1.9).add(2.9).add(3.9);
        node.putArray("low").add(0.9).add(1.9).add(2.9);
        node.putArray("volume").add(10).add(20).add(30);
        var bars = OnvistaMarketClient.parseEodHistory(node).orElseThrow().bars();
        assertEquals(2, bars.size());
        assertEquals(2.5, bars.get(1).close(), 1e-9);
        assertTrue(OnvistaMarketClient.parseEodHistory(OnvistaApi.JSON.createObjectNode()).isEmpty());
    }

    @Test
    void pageShapedEodRowsProduceTheSameRecord() {
        var page = OnvistaPageBundle.nextData(raw("page-sap.html"))
                .path("props").path("pageProps").path("data").path("eodHistory");
        var history = OnvistaMarketClient.parseEodRows(page).orElseThrow();
        assertEquals(3, history.bars().size());
        assertEquals(LocalDate.of(1996, 12, 13), history.startAvailable());
        assertEquals(141.6, history.bars().get(0).open(), 1e-9);
        assertEquals(141.72, history.bars().get(0).close(), 1e-9);
    }

    // ------------------------------------------------------ ticks & panels

    @Test
    void ticksParseFromTheColumnarReply() {
        var ticks = OnvistaMarketClient.parseTicks(json("times-and-sales-sap.json"));
        assertEquals(5, ticks.size());
        assertEquals(157.02, ticks.get(0).price(), 1e-9);
        assertEquals(405.0, ticks.get(0).volume(), 1e-9);
        assertNotNull(ticks.get(0).at());
        assertTrue(ticks.get(0).at().isBefore(ticks.get(1).at()));
    }

    @Test
    void performancePanelKeepsNamedFiguresAndTheFullRawSet() {
        var pv = OnvistaMarketClient.parsePerformanceValues(
                json("performance-values-sap.json")).orElseThrow();
        assertEquals(163500L, pv.idNotation());
        assertEquals(2771188.86667, pv.avgVolumeD30(), 1e-5);
        assertTrue(pv.vola250() > 0);
        // Nothing is dropped: any onvista field stays reachable by name.
        assertEquals(pv.perfRelW52(), pv.figure("performanceRelW52"), 1e-9);
        assertTrue(pv.raw().size() > 50, "raw panel unexpectedly thin: " + pv.raw().size());
        assertTrue(Double.isNaN(pv.figure("noSuchFigure")));
        assertFalse(pv.raw().containsKey("expires"));
    }

    @Test
    void calendarYearPerformanceParses() {
        var years = OnvistaMarketClient.parsePerformanceYears(
                json("performance-values-year-sap.json"));
        assertEquals(3, years.size());
        assertEquals(2016, years.get(0).year());
        assertEquals(12.850913055328434, years.get(0).performanceRel(), 1e-9);
        assertTrue(years.get(2).performanceRel() < 0, "2018 was a down year");
    }

    @Test
    void historicalCrossRateParses() {
        var fx = OnvistaMarketClient.parseCrossRate(json("crossrate-eur-usd.json")).orElseThrow();
        assertEquals(LocalDate.of(2026, 7, 30), fx.date());
        assertEquals(1.15232, fx.close(), 1e-9);
        assertTrue(fx.high() >= fx.close() && fx.low() <= fx.close());
        assertTrue(OnvistaMarketClient.parseCrossRate(
                OnvistaApi.JSON.createObjectNode()).isEmpty());
    }

    // ---------------------------------------------------- indices & screener

    @Test
    void indexConstituentsCarryTheIdentifiersToAddressEachMember() {
        var members = OnvistaMarketClient.parseIndexMembers(
                json("index-constituents-dax.json"), "list");
        assertEquals(3, members.size());
        var first = members.get(0);
        assertEquals("adidas", first.instrument().name());
        assertEquals("DE000A1EWWW0", first.instrument().isin());
        assertEquals("ADS", first.instrument().symbol());
        // The member entity is directly reusable for every other endpoint.
        assertEquals("STOCK/36714349", first.instrument().pathPair());
    }

    @Test
    void topFlopSplitsWinnersFromLosersWithOnvistasOwnTally() {
        var tf = OnvistaMarketClient.parseTopFlop(json("index-top-flop-dax.json")).orElseThrow();
        assertEquals(40, tf.total());
        assertEquals(14, tf.numberTop());
        assertEquals(26, tf.numberFlop());
        assertEquals(2, tf.top().size());
        assertEquals(2, tf.flop().size());
        assertTrue(tf.top().get(0).performancePct() > 0);
        assertTrue(tf.flop().get(0).performancePct() < 0);
        assertTrue(OnvistaMarketClient.parseTopFlop(OnvistaApi.JSON.createObjectNode()).isEmpty());
    }

    @Test
    void screenerHitsCarryInstrumentCompanyAndFigures() {
        var hits = OnvistaMarketClient.parseScreener(json("screener-dax.json"));
        assertEquals(2, hits.size());
        var hit = hits.get(0);
        assertEquals("Daimler Truck", hit.instrument().name());
        assertEquals("DE000DTR0CK8", hit.instrument().isin());
        assertEquals("Automobilproduktion", hit.branch());
        assertEquals("Kraftfahrzeugindustrie", hit.sector());
        assertEquals("Daimler Truck Holding AG Namens-Aktien o.N.", hit.officialName());
        assertTrue(hit.marketCap() > 1e10);
        assertTrue(hit.last() > 0);
    }

    // --------------------------------------------------------- calendar feed

    @Test
    void worldwideCalendarCarriesAllThreeEventKindsAndLinksTheInstrument() {
        var events = OnvistaMarketClient.parseCalendarEvents(json("calendar-events.json"));
        assertEquals(3, events.size());
        var kinds = events.stream().map(e -> e.categoryType()).toList();
        assertTrue(kinds.contains("RESULT_REPORTS"));
        assertTrue(kinds.contains("DIVIDENDS"));
        assertTrue(kinds.contains("GENERAL_MEETING"));

        var report = events.stream()
                .filter(e -> "RESULT_REPORTS".equals(e.categoryType())).findFirst().orElseThrow();
        assertNotNull(report.start());
        assertNotNull(report.title());
        assertNotNull(report.instrument(), "a corporate event must carry its instrument");
        assertNotNull(report.instrument().isin());
    }

    private static OnvistaMarketClient.VenueQuote venue(
            List<OnvistaMarketClient.VenueQuote> venues, String name) {
        return venues.stream().filter(v -> name.equals(v.marketName())).findFirst().orElseThrow();
    }
}
