package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.ChartStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.DaypartStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.IndexStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.MoonStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.OutlookStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.RoomPulse;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.SentimentStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.TickerStat;
import de.bsommerfeld.wsbg.terminal.db.WeatherReportRecord.WorldStats;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
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
                .build(stats, headlines, ZoneOffset.UTC);
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
