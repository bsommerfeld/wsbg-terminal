package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.AdhocStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.AnalystActionStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DaypartStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DepthStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.IndexStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MoverStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.OutlookStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PutCallStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RateStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RoomPulse;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SentimentStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldStats;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deterministic material formatters: frozen stats → labelled blocks the
 * evening passes read. Empty legs must vanish entirely (no headers for the
 * model to hallucinate under), numbers must render German-formatted.
 */
class WeatherMaterialTest {

    @Test
    void emptyLegsProduceNoBlocks() {
        assertEquals("", WeatherMaterial.marketsBlock(List.of()));
        assertEquals("", WeatherMaterial.adhocBlock(List.of()));
        assertEquals("", WeatherMaterial.sentimentBlock(null, null));
        assertEquals("", WeatherMaterial.colourBlock(null));
    }

    @Test
    void ratesBlockCarriesPriorAndDate() {
        String block = WeatherMaterial.ratesBlock(List.of(
                new RateStat("10J Bund", 3.13, 3.17, "2026-07-10")));
        assertTrue(block.contains("10J Bund 3,13 %"), block);
        assertTrue(block.contains("prior 3,17 %"), block);
        assertTrue(block.contains("2026-07-10"), block);
    }

    @Test
    void sentimentBlockCombinesGaugesAndPutCall() {
        String block = WeatherMaterial.sentimentBlock(
                new SentimentStat(62, "GREED", 58, List.of(), 71, "GREED"),
                new PutCallStat(0.81, 0.55, 1.01, "2026-07-10"));
        assertTrue(block.contains("US Fear & Greed 62 (greed), prior day 58"), block);
        assertTrue(block.contains("Crypto Fear & Greed 71"), block);
        assertTrue(block.contains("equity 0,55"), block);
    }

    @Test
    void sentimentBlockCarriesTheSevenComponents() {
        String block = WeatherMaterial.sentimentBlock(
                new SentimentStat(62, "GREED", 58,
                        List.of(new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SentimentComponent(
                                        "market_momentum_sp500", 61),
                                new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SentimentComponent(
                                        "safe_haven_demand", 24)),
                        null, null), null);
        assertTrue(block.contains("components: market momentum sp500 61, safe haven demand 24"),
                block);
    }

    @Test
    void houseBlockCarriesTheWatchlistTldr() {
        String block = WeatherMaterial.houseBlock(List.of(
                new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WatchlistStat(
                        "Rheinmetall", "RHM", 2.1, 2010.0, "EUR",
                        "Der Raum lehnt bullisch, wartet aber auf die Zahlen.")),
                List.of("SAP SE: Die These trägt."));
        assertTrue(block.contains("Rheinmetall +2,1 % (house read: Der Raum lehnt bullisch"),
                block);
        assertTrue(block.contains("SAP SE: Die These trägt."), block);
    }

    @Test
    void adhocBlockMarksCagePapers() {
        String block = WeatherMaterial.adhocBlock(List.of(
                new AdhocStat("Covestro AG: EBITDA-Prognose angehoben", "DE0006062144",
                        "10:57", "1COV")));
        assertTrue(block.contains("Covestro"), block);
        assertTrue(block.contains("discussed in the room today: 1COV"), block);
    }

    @Test
    void moversBlockGroupsKindsAndMarksOverlap() {
        String block = WeatherMaterial.moversBlock(List.of(
                new MoverStat("OPEN", "Opendoor", 42.6, 2.61, "GAINER", true),
                new MoverStat("XYZ", "Xyz Corp", -18.0, 5.0, "LOSER", false)));
        assertTrue(block.contains("gainers: Opendoor +42,6 % [also discussed in the room]"), block);
        assertTrue(block.contains("losers: Xyz Corp -18,0 %"), block);
    }

    @Test
    void sectorsBlockNamesStrongestAndWeakest() {
        String block = WeatherMaterial.sectorsBlock(List.of(
                new IndexStat("Tech", "XLK", 200.0, 1.8, null, "USD", null),
                new IndexStat("Energie", "XLE", 90.0, 0.4, null, "USD", null),
                new IndexStat("Immobilien", "XLRE", 40.0, -1.2, null, "USD", null)));
        assertTrue(block.contains("strongest Tech +1,8 %"), block);
        assertTrue(block.contains("Immobilien -1,2 %"), block);
    }

    @Test
    void compactRendersGermanMagnitudes() {
        assertEquals("39,4 Bio", WeatherMaterial.compact(39_414_179_016_130L));
        assertEquals("3,4 Mio", WeatherMaterial.compact(3_400_000L));
    }

    @Test
    void pulseAggregatesSentimentRedAndBusiestHour() {
        ZoneId utc = ZoneOffset.UTC;
        long tenUtc = 1783850400L;   // 2026-07-13 10:00 UTC
        RoomPulse pulse = WeatherStatsCollector.pulse(List.of(
                headline(tenUtc, HeadlineSentiment.BULLISH, HeadlineHighlight.IMPORTANT, "NVDA"),
                headline(tenUtc + 60, HeadlineSentiment.CAPITULATION, HeadlineHighlight.NORMAL, "RHM"),
                headline(tenUtc + 7200, HeadlineSentiment.NEUTRAL, HeadlineHighlight.NORMAL, null)),
                utc);
        assertEquals(1, pulse.bullish());
        assertEquals(1, pulse.bearish());
        assertEquals(1, pulse.neutral());
        assertEquals(1, pulse.redCount());
        assertEquals(10, pulse.busiestHour());
        assertEquals(3, pulse.distinctSubjects());
    }

    private static HeadlineRecord headline(long createdAt, HeadlineSentiment sentiment,
            HeadlineHighlight highlight, String ticker) {
        return new HeadlineRecord("c" + createdAt, "text", null, createdAt, List.of(), List.of(),
                highlight, ticker, List.of(), null, List.of(), null, sentiment, null,
                false, List.of());
    }

    // --- the Redaktion's section shelves ------------------------------------

    @Test
    void windowOfSplitsTheDayAtNoonAndFour() {
        assertEquals(0, WeatherMaterial.windowOf("09:15"));
        assertEquals(1, WeatherMaterial.windowOf("12:00"));
        assertEquals(1, WeatherMaterial.windowOf("15:59"));
        assertEquals(2, WeatherMaterial.windowOf("16:00"));
        assertEquals(2, WeatherMaterial.windowOf("23:30"));
        assertEquals(-1, WeatherMaterial.windowOf(null));
        assertEquals(-1, WeatherMaterial.windowOf("gestern"));
    }

    @Test
    void shelvesRouteTimedItemsToTheirWindow() {
        WorldStats world = new WorldStats(null, null, null, null,
                List.of(new AdhocStat("Morgens-Adhoc", "DE0000000001", "08:30", null),
                        new AdhocStat("Abend-Adhoc", "DE0000000002", "19:10", null)),
                List.of(new AnalystActionStat("UBS stuft hoch", "13:05")),
                null, null, null, null, null, null, null, null, null,
                List.of(new DepthStat("RHM", 2100.0, "EUR", 8.0, 10, 4, 1,
                        null, null, null, null, null)),
                null, null,
                List.of(new OutlookStat("CPI", "USD", "High", "14:30", "ECON")),
                null, null, null, null,
                List.of(new DaypartStat("MORNING", "SUNNY", 5, 4, 1, 0, "Rheinmetall"),
                        new DaypartStat("MIDDAY", "FOG", 0, 0, 0, 0, null),
                        new DaypartStat("EVENING", "RAIN", 3, 0, 3, 1, "NVIDIA"),
                        new DaypartStat("TOMORROW", "STORM", 0, 0, 0, 0, "CPI")));
        WeatherStatsCollector.Stats stats = new WeatherStatsCollector.Stats(
                List.of(), List.of(), List.of(), null, world);

        String[] shelves = WeatherMaterial.sectionShelves(stats, LocalDate.of(2026, 7, 13),
                "08:12 Rheinmetall läuft", "", "19:02 NVIDIA zieht an");

        assertEquals(WeatherMaterial.SECTION_COUNT, shelves.length);
        // Every shelf carries the day's ISO date for the examiner.
        for (String shelf : shelves) assertTrue(shelf.contains("2026-07-13"), shelf);
        // The morning shelf gets the morning ad-hoc and its window line; the
        // evening ad-hoc must not leak into it.
        assertTrue(shelves[WeatherMaterial.SEC_MORNING].contains("Morgens-Adhoc"));
        assertFalse(shelves[WeatherMaterial.SEC_MORNING].contains("Abend-Adhoc"));
        assertTrue(shelves[WeatherMaterial.SEC_MORNING].contains("lead subject Rheinmetall"));
        assertTrue(shelves[WeatherMaterial.SEC_MORNING].contains("Rheinmetall läuft"));
        // Analyst actions belong to the midday shelf (13:05), street depth too.
        assertTrue(shelves[WeatherMaterial.SEC_MIDDAY].contains("UBS stuft hoch"));
        assertTrue(shelves[WeatherMaterial.SEC_MIDDAY].contains("STREET DEPTH"));
        assertFalse(shelves[WeatherMaterial.SEC_MORNING].contains("UBS stuft hoch"));
        // The evening shelf gets its ad-hoc and wire; the outlook shelf the docket.
        assertTrue(shelves[WeatherMaterial.SEC_EVENING].contains("Abend-Adhoc"));
        assertTrue(shelves[WeatherMaterial.SEC_EVENING].contains("NVIDIA zieht an"));
        assertTrue(shelves[WeatherMaterial.SEC_OUTLOOK].contains("CPI"));
        assertFalse(shelves[WeatherMaterial.SEC_OUTLOOK].contains("NVIDIA"));
    }

    @Test
    void pressReviewRoutesToItsWindowAndSectorsRideEveryDayShelf() {
        WorldStats world = new WorldStats(
                List.of(new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.IndexStat(
                                "Tech", "XLK", null, -1.8, null, "USD", List.of()),
                        new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.IndexStat(
                                "Energie", "XLE", null, 2.0, null, "USD", List.of())),
                null, null, null, List.of(), List.of(), null, null, null, null, null,
                null, null, null, null, List.of(), null, null,
                List.of(new OutlookStat("CPI", "USD", "High", "14:30", "ECON")),
                null, null, null, null, List.of(), null, null, null, null, List.of(),
                List.of(new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PressReviewStat(
                                "NFIB optimism jumps", "Small business surveys hot",
                                "CNBC", "US_ECONOMY", "12:04"),
                        new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PressReviewStat(
                                "Futures slip ahead of CPI", null,
                                "MarketWatch", "US_MARKETS", "08:11")));
        WeatherStatsCollector.Stats stats = new WeatherStatsCollector.Stats(
                List.of(), List.of(), List.of(), null, world);
        String[] shelves = WeatherMaterial.sectionShelves(stats, LocalDate.of(2026, 7, 14),
                "", "", "");
        // The 12:04 press item sits on the midday shelf, the 08:11 one on the
        // morning shelf — never the other way round.
        assertTrue(shelves[WeatherMaterial.SEC_MIDDAY].contains("NFIB optimism jumps"));
        assertFalse(shelves[WeatherMaterial.SEC_MORNING].contains("NFIB optimism jumps"));
        assertTrue(shelves[WeatherMaterial.SEC_MORNING].contains("Futures slip ahead of CPI"));
        assertTrue(shelves[WeatherMaterial.SEC_MORNING].contains("[MarketWatch]"));
        // The sector table rides every day shelf AND the outlook (the
        // calendar→sector tie needs it beside the docket).
        assertTrue(shelves[WeatherMaterial.SEC_PICTURE].contains("US SECTOR ROTATION"));
        assertTrue(shelves[WeatherMaterial.SEC_MORNING].contains("US SECTOR ROTATION"));
        assertTrue(shelves[WeatherMaterial.SEC_MIDDAY].contains("US SECTOR ROTATION"));
        assertTrue(shelves[WeatherMaterial.SEC_EVENING].contains("US SECTOR ROTATION"));
        assertTrue(shelves[WeatherMaterial.SEC_OUTLOOK].contains("US SECTOR ROTATION"));
    }

    @Test
    void untimedItemsAppearExactlyOnceInTheirHomeWindow() {
        WorldStats world = new WorldStats(null, null, null, null,
                List.of(new AdhocStat("Zeitlose Adhoc", "DE0000000003", null, null)),
                List.of(new AnalystActionStat("Zeitlose Analyse", null)),
                null, null, null, null, null, null, null, null, null, List.of(),
                null, null, List.of(), null, null, null, null, List.of());
        WeatherStatsCollector.Stats stats = new WeatherStatsCollector.Stats(
                List.of(), List.of(), List.of(), null, world);
        String[] shelves = WeatherMaterial.sectionShelves(stats, LocalDate.of(2026, 7, 13),
                "", "", "");
        assertTrue(shelves[WeatherMaterial.SEC_MORNING].contains("Zeitlose Adhoc"));
        assertFalse(shelves[WeatherMaterial.SEC_MIDDAY].contains("Zeitlose Adhoc"));
        assertFalse(shelves[WeatherMaterial.SEC_EVENING].contains("Zeitlose Adhoc"));
        assertTrue(shelves[WeatherMaterial.SEC_MIDDAY].contains("Zeitlose Analyse"));
        assertFalse(shelves[WeatherMaterial.SEC_MORNING].contains("Zeitlose Analyse"));
    }

    @Test
    void econOutcomesBlockRendersActualVsForecastWithDirection() {
        String block = WeatherMaterial.econOutcomesBlock(List.of(
                new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.EconOutcomeStat(
                        "Inflation Rate MoM", "US", "14:30", "High", 0.3, 0.2, 0.1, "%"),
                new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.EconOutcomeStat(
                        "Bubill Auction", "DE", "09:30", "Low", 2.436, null, 2.331, "%")));
        assertTrue(block.contains("14:30 [US] Inflation Rate MoM: actual 0,30 %"), block);
        assertTrue(block.contains("forecast 0,20 %"), block);
        assertTrue(block.contains("above forecast"), block);
        // No forecast → no surprise verdict, previous still shown.
        assertTrue(block.contains("Bubill Auction: actual 2,44 % (previous 2,33 %)"), block);
        assertEquals("", WeatherMaterial.econOutcomesBlock(List.of()));
    }

    @Test
    void surpriseWordTreatsHalfAPercentAsInLine() {
        assertEquals("in line with forecast", WeatherMaterial.surpriseWord(100.2, 100.0));
        assertEquals("above forecast", WeatherMaterial.surpriseWord(0.3, 0.2));
        assertEquals("below forecast", WeatherMaterial.surpriseWord(-0.1, 0.2));
    }

    @Test
    void worldAndCbAndReviewBlocksRender() {
        String world = WeatherMaterial.worldEventsBlock(List.of(
                new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldEventStat(
                        "Business and economy", "Brent jumps after the strait closure.",
                        "Reuters")));
        assertTrue(world.contains("[Business and economy] Brent jumps"), world);
        assertTrue(world.contains("(Reuters)"), world);

        String cb = WeatherMaterial.cbDatesBlock(List.of(
                new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.CbDateStat(
                        "EZB", "EZB-Zinsentscheid", "2026-07-23"),
                new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.CbDateStat(
                        "Fed", "FOMC-Zinsentscheid", "2026-07-29")));
        assertTrue(cb.contains("EZB-Zinsentscheid 2026-07-23"), cb);
        assertTrue(cb.contains("FOMC-Zinsentscheid 2026-07-29"), cb);

        String reviews = WeatherMaterial.eventReviewsBlock(List.of(
                new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.EventReviewStat(
                        "Inflation Rate MoM (US)",
                        List.of("US-Inflation zieht an [Handelsblatt]"))));
        assertTrue(reviews.contains("Inflation Rate MoM (US):"), reviews);
        assertTrue(reviews.contains("US-Inflation zieht an [Handelsblatt]"), reviews);

        assertEquals("", WeatherMaterial.worldEventsBlock(List.of()));
        assertEquals("", WeatherMaterial.cbDatesBlock(List.of()));
        assertEquals("", WeatherMaterial.eventReviewsBlock(List.of()));
    }

    @Test
    void outlookBlockRendersCorpAndCbKinds() {
        String block = WeatherMaterial.outlookBlock(List.of(
                new OutlookStat("JDC Group AG: Hauptversammlung", "JDC", null, null, "CORP"),
                new OutlookStat("EZB-Zinsentscheid", "EZB", "High", null, "CB")));
        assertTrue(block.contains("corporate (Germany): JDC Group AG: Hauptversammlung"), block);
        assertTrue(block.contains("[discussed in the room today: JDC]"), block);
        assertTrue(block.contains("rate decision: EZB-Zinsentscheid (High)"), block);
    }

    @Test
    void newCalendarLegsLandOnTheirShelves() {
        WorldStats world = new WorldStats(null, null, null, null,
                List.of(), List.of(), null, null, null, null, null, null, null, null,
                null, List.of(), null, null, List.of(), null, null, null, null, List.of(),
                List.of(new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.EconOutcomeStat(
                        "Inflation Rate MoM", "US", "14:30", "High", 0.3, 0.2, 0.1, "%")),
                List.of(new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldEventStat(
                        "Armed conflicts and attacks", "Strait of Hormuz declared closed.",
                        "France 24")),
                List.of(new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.EventReviewStat(
                        "Inflation Rate MoM (US)", List.of("Presse-Titel [Quelle]"))),
                List.of(new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.CbDateStat(
                        "EZB", "EZB-Zinsentscheid", "2026-07-23")),
                null);
        WeatherStatsCollector.Stats stats = new WeatherStatsCollector.Stats(
                List.of(), List.of(), List.of(), null, world);
        String[] shelves = WeatherMaterial.sectionShelves(stats, LocalDate.of(2026, 7, 13),
                "", "", "");
        // The 14:30 outcome is a midday fact — not morning, not evening.
        assertTrue(shelves[WeatherMaterial.SEC_MIDDAY].contains("MACRO OUTCOMES"));
        assertFalse(shelves[WeatherMaterial.SEC_MORNING].contains("MACRO OUTCOMES"));
        // The world log frames the big picture; the press review is evening material.
        assertTrue(shelves[WeatherMaterial.SEC_PICTURE].contains("WORLD TODAY"));
        assertTrue(shelves[WeatherMaterial.SEC_EVENING].contains("PRESS ON TODAY'S DATA"));
        // The next rate decisions anchor the outlook.
        assertTrue(shelves[WeatherMaterial.SEC_OUTLOOK].contains("NEXT RATE DECISIONS"));
        assertTrue(shelves[WeatherMaterial.SEC_OUTLOOK].contains("EZB-Zinsentscheid 2026-07-23"));
    }

    @Test
    void topNewsBlockRendersAttributedArdLines() {
        String block = WeatherMaterial.topNewsBlock(List.of(
                new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TopNewsStat(
                        "Krieg im Nahen Osten", "Trump kündigt Seeblockade an",
                        "Nach neuen Angriffen kündigt Trump eine Seeblockade an.",
                        "20:56", "ausland", false),
                new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TopNewsStat(
                        null, "DAX startet robust", null, "18:51", "wirtschaft", true)));
        assertTrue(block.startsWith("TOP NEWS of the day (Tagesschau/ARD"), block);
        assertTrue(block.contains("20:56 Krieg im Nahen Osten: Trump kündigt Seeblockade an"), block);
        assertTrue(block.contains("— Nach neuen Angriffen"), block);
        assertTrue(block.contains("18:51 (breaking) DAX startet robust"), block);
        assertEquals("", WeatherMaterial.topNewsBlock(List.of()));
        // Top news frame the big picture shelf, attributed.
        WorldStats world = new WorldStats(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                List.of(new de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TopNewsStat(
                        null, "DAX startet robust", null, "18:51", "wirtschaft", false)));
        WeatherStatsCollector.Stats stats = new WeatherStatsCollector.Stats(
                List.of(), List.of(), List.of(), null, world);
        String[] shelves = WeatherMaterial.sectionShelves(stats, LocalDate.of(2026, 7, 13),
                "", "", "");
        assertTrue(shelves[WeatherMaterial.SEC_PICTURE].contains("TOP NEWS of the day"));
        assertFalse(shelves[WeatherMaterial.SEC_MORNING].contains("TOP NEWS of the day"));
    }

    @Test
    void shelfEmptyDetectsTheDateOnlyShelf() {
        WeatherStatsCollector.Stats stats = new WeatherStatsCollector.Stats(
                List.of(), List.of(), List.of(), null, null);
        String[] shelves = WeatherMaterial.sectionShelves(stats, LocalDate.of(2026, 7, 13),
                "", "", "");
        assertTrue(WeatherMaterial.shelfEmpty(shelves[WeatherMaterial.SEC_OUTLOOK]));
        assertTrue(WeatherMaterial.shelfEmpty(null));
        assertTrue(WeatherMaterial.shelfEmpty("  "));
        assertFalse(WeatherMaterial.shelfEmpty("DATE: x\n\nWIRE STORIES ..."));
    }
}
