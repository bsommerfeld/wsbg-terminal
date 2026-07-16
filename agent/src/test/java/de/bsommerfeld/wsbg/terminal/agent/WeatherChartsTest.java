package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.ChartStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.ChokepointStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.ConflictStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DaypartStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.FreightStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.HazardStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.HealthStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.HolidayStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.IndexStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MoonStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.OilStockStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.OutlookStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PlaceWeatherStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PollStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.PowerStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RateStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RoomPulse;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SentimentStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldSignals;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldStats;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Abendausgabe's figure layer and forecast strip: deterministic SVGs from
 * frozen stats, five-section anchoring, and the mood→weather-symbol mapping.
 */
class WeatherChartsTest {

    @Test
    void figuresRenderAndStayInsideTheFiveSections() {
        WorldStats world = new WorldStats(
                List.of(new IndexStat("Tech", "XLK", 200.0, 1.8, null, "USD", List.of()),
                        new IndexStat("Energie", "XLE", 90.0, -0.6, null, "USD", List.of()),
                        new IndexStat("Finanzen", "XLF", 45.0, 0.2, null, "USD", List.of()),
                        new IndexStat("Versorger", "XLU", 70.0, -1.1, null, "USD", List.of())),
                List.of(), List.of(),
                new RoomPulse(18, 12, 12, 5, 14, 23),
                List.of(), List.of(), List.of(), List.of(), null, List.of(), null, List.of(),
                null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new OutlookStat("J P Morgan", "JPM, erw. EPS $5.52", null,
                        "pre-market", "EARNINGS")),
                null, null, null,
                new MoonStat("WAXING_GIBBOUS", 87, 3),
                List.of());
        WeatherStatsCollector.Stats stats = new WeatherStatsCollector.Stats(
                List.of(new IndexStat("DAX", "^GDAXI", 24013.0, 1.2, null, "PTS", List.of()),
                        new IndexStat("S&P 500", "^GSPC", 6300.0, 0.4, null, "PTS", List.of()),
                        new IndexStat("Bitcoin", "BTC-USD", 63000.0, -1.0, null, "USD", List.of())),
                List.of(new TickerStat("RHM", "Rheinmetall", 7, 1, 1980.0, "EUR", 2.3, null, null),
                        new TickerStat("NVDA", "NVIDIA", 5, 0, 142.0, "EUR", -0.8, null, null)),
                List.of(),
                new SentimentStat(62, "GREED", 58),
                world);

        List<HeadlineRecord> headlines = List.of(
                headline(1783850400L, HeadlineSentiment.BULLISH, "RHM"),   // 10:00 UTC
                headline(1783857600L, HeadlineSentiment.BEARISH, "NVDA"),  // 12:00 UTC
                headline(1783864800L, HeadlineSentiment.NEUTRAL, "RHM"));  // 14:00 UTC

        List<ChartStat> charts = new WeatherCharts("de")
                .build(stats, headlines, ZoneOffset.UTC, List.of());
        assertTrue(charts.size() >= 5, "expected a filled figure set, got " + charts.size());
        for (ChartStat c : charts) {
            assertTrue(c.section() >= 0 && c.section() <= 4,
                    c.title() + " anchored outside the five sections: " + c.section());
            assertTrue(c.svg().startsWith("<svg") && c.svg().endsWith("</svg>"), c.title());
        }
        assertTrue(charts.stream().anyMatch(c -> c.title().contains("auf einen Blick")));
        assertTrue(charts.stream().anyMatch(c -> c.title().contains("Zum Mond")));
    }

    @Test
    void worldSignalFiguresRenderFromTheFrozenCatch() {
        LinkedHashMap<String, Double> results = new LinkedHashMap<>();
        results.put("CDU/CSU", 29.0);
        results.put("AfD", 24.5);
        WorldSignals signals = new WorldSignals(
                List.of(new ChokepointStat("Strait of Hormuz", "2026-07-13", 90, -12.5)),
                new OilStockStat("2026-07-10", 443.2, -2.1, 403.0, 0.5,
                        228.4, 1.9, 118.0, -1.2),
                new FreightStat(1250.0, 1210.0, "2026-07-12",
                        List.of(1100.0, 1150.0, 1210.0, 1250.0)),
                new PowerStat(85.0, 61.0, 142.0, 83.0, 55.0, "Wind",
                        List.of(70.0, 61.0, 90.0, 142.0, 80.0)),
                null,
                List.of(),
                List.of(new PollStat("Bundestag", "Forsa", "2026-07-13",
                        "CDU/CSU 29 %, AfD 24,5 %", results)),
                List.of(),
                new HealthStat(78.5, 6200.0, "2026-W28", List.of(),
                        List.of(5000.0, 5600.0, 6200.0)),
                List.of(),
                List.of("Bundesliga: Bayern – Dortmund"),
                new HolidayStat("Tag der Deutschen Einheit", "2026-10-03", true,
                        List.of("BY", "BW")),
                List.of(new ConflictStat("Iran", "Strikes reported near Hormuz",
                        "Reuters", 33.4, 53.2)));
        WorldStats world = new WorldStats(
                null, null,
                List.of(new RateStat("10J Bund", 2.61, 2.58, "2026-07-13"),
                        new RateStat("10J US-Treasury", 4.40, 4.38, null),
                        new RateStat("HICP-Inflation Euroraum", 2.10, 2.00, null)),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                List.of(new PlaceWeatherStat("New York", "Wall Street", 28.0, "klar",
                        10.0, 30.0, 21.0, "klar", 40.71, -74.01)),
                List.of(new HazardStat("STORM", "Hurricane Egon", "HIGH", 25.0, -70.0)),
                null, null, signals);
        WeatherStatsCollector.Stats stats = new WeatherStatsCollector.Stats(
                List.of(), List.of(), List.of(), null, world);

        List<ChartStat> charts = new WeatherCharts("de").build(stats, List.of(), ZoneOffset.UTC, List.of());
        for (ChartStat c : charts) {
            assertTrue(c.section() >= 0 && c.section() <= 4,
                    c.title() + " anchored outside the five sections: " + c.section());
        }

        ChartStat freight = figure(charts, "Harpex");
        assertEquals(0, freight.section());
        assertTrue(freight.note().contains("Harper Petersen"), freight.note());
        assertTrue(freight.svg().contains("1.250") && freight.svg().contains("Vorwoche"),
                freight.svg());

        ChartStat power = figure(charts, "Strompreis");
        assertEquals(0, power.section());
        assertTrue(power.note().contains("Fraunhofer"), power.note());
        assertTrue(power.svg().contains("EUR/MWh") && power.svg().contains("142"),
                power.svg());

        ChartStat oil = figure(charts, "US-Öllager");
        assertEquals(0, oil.section());
        assertTrue(oil.note().contains("EIA"), oil.note());
        assertTrue(oil.svg().contains("Rohöl") && oil.svg().contains("SPR")
                && oil.svg().contains("2,1 Mb"), oil.svg());
        assertTrue(oil.title().contains("2026-07-10"), oil.title());

        ChartStat ladder = figure(charts, "Zins-Leiter");
        assertEquals(2, ladder.section());
        assertTrue(ladder.note().contains("Bundesbank"), ladder.note());
        assertTrue(ladder.svg().contains("10J Bund") && ladder.svg().contains("2,61 %"),
                ladder.svg());
        assertTrue(ladder.svg().contains("var(--ddc-surface"),
                "HICP mark should render hollow: " + ladder.svg());

        ChartStat poll = figure(charts, "Bundestag");
        assertEquals(0, poll.section());
        assertTrue(poll.title().contains("Forsa") && poll.title().contains("2026-07-13"),
                poll.title());
        assertTrue(poll.svg().contains("CDU/CSU") && poll.svg().contains("24,5 %"),
                poll.svg());

        ChartStat health = figure(charts, "Gesundheitslage");
        assertEquals(0, health.section());
        assertTrue(health.note().contains("RKI"), health.note());
        assertTrue(health.svg().contains("INTENSIVBETTEN")
                && health.svg().contains("2026-W28"), health.svg());

        ChartStat calendar = figure(charts, "Kalender");
        assertEquals(4, calendar.section());
        assertTrue(calendar.svg().contains("Feiertag")
                && calendar.svg().contains("Bayern"), calendar.svg());

        ChartStat map = figure(charts, "Weltlage");
        assertEquals(0, map.section());
        assertTrue(map.note().contains("PortWatch"), map.note());
        assertTrue(map.svg().contains("viewBox=\"0 0 1000 500\""), map.svg());
        assertTrue(map.svg().contains("var(--ddc-conflict"),
                "conflict triangle missing: " + map.svg());
        assertTrue(map.svg().contains("28°"), "place temp label missing");
        assertTrue(map.svg().contains("var(--ddc-neg"),
                "chokepoint down-delta tint missing");
    }

    @Test
    void worldSignalFiguresStayAbsentWithoutTheCatch() {
        WeatherStatsCollector.Stats bare = new WeatherStatsCollector.Stats(
                List.of(), List.of(), List.of(), null, null);
        assertTrue(new WeatherCharts("de").build(bare, List.of(), ZoneOffset.UTC, List.of()).isEmpty(),
                "null world must yield no figures");

        WorldSignals hollow = new WorldSignals(null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        WorldStats world = new WorldStats(
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, hollow);
        WeatherStatsCollector.Stats stats = new WeatherStatsCollector.Stats(
                List.of(), List.of(), List.of(), null, world);
        assertTrue(new WeatherCharts("de").build(stats, List.of(), ZoneOffset.UTC, List.of()).isEmpty(),
                "an empty catch must yield no figures");
    }

    @Test
    void fearGreedBandRendersFromTheArchivedHistory() {
        WeatherStatsCollector.Stats bare = new WeatherStatsCollector.Stats(
                List.of(), List.of(), List.of(), null, null);
        List<Integer> scores = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) scores.add(30 + i); // fear → greed sweep
        List<ChartStat> charts = new WeatherCharts("de")
                .build(bare, List.of(), ZoneOffset.UTC, scores);
        assertEquals(1, charts.size());
        ChartStat band = charts.get(0);
        assertEquals(0, band.section());
        assertEquals("CNN", band.note());
        assertTrue(band.title().contains("40 Handelstage"), band.title());
        assertTrue(band.svg().contains("heute 69"), "today's score direct-labeled");
        assertTrue(band.svg().contains("extreme Gier")
                && band.svg().contains("extreme Angst"), "band zone labels");
        assertTrue(band.svg().contains("var(--ddc-pos")
                && band.svg().contains("var(--ddc-neg"), "zone washes");
        // A lone reading is no regime band.
        assertTrue(new WeatherCharts("de")
                .build(bare, List.of(), ZoneOffset.UTC, List.of(55)).isEmpty());
    }

    @Test
    void signalSeriesFiguresRenderFromArchiveDerivedHistory() {
        List<Double> entropy = new java.util.ArrayList<>();
        for (int i = 0; i < 14; i++) entropy.add(0.85);
        entropy.add(0.31); // today: collapsed
        List<Double> cage = new java.util.ArrayList<>();
        List<Double> street = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) {
            cage.add(50.0);
            street.add(50.0);
        }
        cage.add(80.0);  // today: cage far greedier...
        street.add(20.0); // ...than the fearful street
        List<ChartStat> charts = new WeatherCharts("de")
                .signalSeriesFigures(entropy, cage, street);
        assertEquals(2, charts.size());
        ChartStat entropyFig = figure(charts, "Aufmerksamkeits-Entropie");
        assertEquals(0, entropyFig.section());
        assertTrue(entropyFig.title().contains("15 messbare Tage"), entropyFig.title());
        assertTrue(entropyFig.svg().contains("heute 0,31"), "today direct-labeled (de decimal)");
        assertTrue(entropyFig.svg().contains("fokussiert"), "reading bands labeled");
        ChartStat both = figure(charts, "beide Barometer");
        assertEquals(0, both.section());
        assertTrue(both.svg().contains("Käfig 80"), "cage barometer direct-labeled");
        assertTrue(both.svg().contains("CNN 20"), "street barometer direct-labeled");
        assertTrue(both.svg().contains("Gier") && both.svg().contains("Angst"),
                "greed/fear halves labeled");
        // Below the point floor no series is drawn - a two-point trend is noise.
        assertTrue(new WeatherCharts("de")
                .signalSeriesFigures(List.of(0.5, 0.6), List.of(1.0, 2.0), List.of(3.0, 4.0))
                .isEmpty());
    }

    private static ChartStat figure(List<ChartStat> charts, String titlePart) {
        return charts.stream().filter(c -> c.title().contains(titlePart)).findFirst()
                .orElseThrow(() -> new AssertionError("missing figure: " + titlePart
                        + " — have " + charts.stream().map(ChartStat::title).toList()));
    }

    @Test
    void moodIconMapsBullsBearsAndSilence() {
        assertEquals("FOG", WeatherStatsCollector.moodIcon(0, 0, 0, 0));
        assertEquals("CLOUDY", WeatherStatsCollector.moodIcon(0, 0, 5, 0));
        assertEquals("SUNNY", WeatherStatsCollector.moodIcon(8, 2, 3, 0));
        assertEquals("PARTLY", WeatherStatsCollector.moodIcon(5, 5, 0, 0));
        assertEquals("RAIN", WeatherStatsCollector.moodIcon(2, 6, 1, 0));
        assertEquals("STORM", WeatherStatsCollector.moodIcon(1, 8, 0, 3));
    }

    @Test
    void daypartsSplitTheDayAndTomorrowFollowsTheDocket() {
        List<HeadlineRecord> headlines = List.of(
                headline(epochAtUtcHour(9), HeadlineSentiment.BULLISH, "RHM"),
                headline(epochAtUtcHour(10), HeadlineSentiment.BULLISH, "RHM"),
                headline(epochAtUtcHour(14), HeadlineSentiment.BEARISH, "NVDA"),
                headline(epochAtUtcHour(18), HeadlineSentiment.NEUTRAL, null));
        List<OutlookStat> outlook = List.of(
                new OutlookStat("Core CPI m/m", "USD", "High", "14:30", "ECON"));

        List<DaypartStat> parts = WeatherStatsCollector.dayparts(
                headlines, ZoneOffset.UTC, outlook);
        assertEquals(4, parts.size());
        DaypartStat morning = parts.get(0);
        assertEquals("MORNING", morning.key());
        assertEquals(2, morning.lines());
        assertEquals("SUNNY", morning.icon());
        assertEquals("MIDDAY", parts.get(1).key());
        assertEquals(1, parts.get(1).lines());
        DaypartStat tomorrow = parts.get(3);
        assertEquals("TOMORROW", tomorrow.key());
        assertEquals("STORM", tomorrow.icon());
        assertEquals("Core CPI m/m", tomorrow.note());
    }

    @Test
    void timelineBlockNamesTheWindows() {
        String block = WeatherMaterial.timelineBlock(List.of(
                new DaypartStat("MORNING", "SUNNY", 14, 9, 3, 1, "NVIDIA"),
                new DaypartStat("EVENING", "FOG", 0, 0, 0, 0, null),
                new DaypartStat("TOMORROW", "STORM", 0, 0, 0, 0, "CPI")));
        assertTrue(block.contains("morning: 14 lines"), block);
        assertTrue(block.contains("lead subject NVIDIA"), block);
        assertTrue(block.contains("evening: 0 lines (the cage was quiet)"), block);
        assertTrue(!block.contains("TOMORROW"), "tomorrow is the strip's business, not the timeline's");
    }

    private static long epochAtUtcHour(int hour) {
        return 1783814400L + hour * 3600L; // 2026-07-13 00:00 UTC + hour
    }

    private static HeadlineRecord headline(long createdAt, HeadlineSentiment sentiment,
            String ticker) {
        return new HeadlineRecord("c" + createdAt, "text", null, createdAt, List.of(), List.of(),
                sentiment == HeadlineSentiment.BULLISH && ticker != null && createdAt % 2 == 0
                        ? HeadlineHighlight.IMPORTANT : HeadlineHighlight.NORMAL,
                ticker, List.of(), null, List.of(), null, sentiment, null, false, List.of());
    }
}
