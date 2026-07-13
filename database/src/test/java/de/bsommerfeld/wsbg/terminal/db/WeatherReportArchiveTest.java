package de.bsommerfeld.wsbg.terminal.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permanent Wetterbericht archive: append-only JSONL, one report per day
 * (idempotent on the date), frozen market stats round-tripping in full, torn
 * lines skipped on load.
 */
class WeatherReportArchiveTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("weather-reports.jsonl");
    }

    private static WeatherReportRecord rec(String date, String text) {
        return new WeatherReportRecord(date, 1_700_000_000L, text, "de", 42, 3,
                List.of(new WeatherReportRecord.IndexStat("DAX", "^GDAXI", 24013.0, 1.23, 81_000_000L,
                        "PTS", List.of(23950.0, 23980.0, 24013.0))),
                List.of(new WeatherReportRecord.TickerStat("NVDA", "NVIDIA", 7, 1,
                        142.5, "EUR", 3.1, 55_000_000L, 7_800_000_000L)),
                List.of(new WeatherReportRecord.NewsStat("NVIDIA hebt Prognose an", "Reuters", 4)),
                new WeatherReportRecord.SentimentStat(62, "GREED", 58,
                        List.of(new WeatherReportRecord.SentimentComponent("market_momentum_sp500", 80)),
                        71, "GREED"),
                world(),
                List.of(new WeatherReportRecord.ChartStat(0, "Der Tag auf einen Blick",
                        "alle Quellen", "<svg viewBox=\"0 0 560 60\"><text>x</text></svg>")));
    }

    /** A representative Abendausgabe world block — every stat family populated once. */
    private static WeatherReportRecord.WorldStats world() {
        return new WeatherReportRecord.WorldStats(
                List.of(new WeatherReportRecord.IndexStat("Tech", "XLK", 200.0, 1.8, null, "USD",
                        List.of())),
                List.of(new WeatherReportRecord.IndexStat("Nikkei 225", "^N225", 39000.0, -0.4,
                        null, "PTS", List.of())),
                List.of(new WeatherReportRecord.RateStat("10J Bund", 3.13, 3.17, "2026-07-10")),
                new WeatherReportRecord.RoomPulse(18, 12, 12, 5, 14, 23),
                List.of(new WeatherReportRecord.AdhocStat("Covestro: Prognose angehoben",
                        "DE0006062144", "10:57", "1COV")),
                List.of(new WeatherReportRecord.AnalystActionStat(
                        "JPMORGAN stuft BMW auf 'Overweight'", "18:18")),
                List.of(new WeatherReportRecord.MacroStat("Inflationsrate im Juni 2026 bei +2,3 %",
                        "Destatis", "08:00", null, null, null)),
                List.of(new WeatherReportRecord.MacroStat("Core CPI m/m", "USD", "14:30", "High",
                        "0.2%", "0.1%")),
                "„WOCHENAUSBLICK" + "“ — dpa-AFX-Teaser",
                List.of(new WeatherReportRecord.MoverStat("OPEN", "Opendoor", 42.6, 2.61,
                        "GAINER", true)),
                new WeatherReportRecord.PutCallStat(0.81, 0.55, 1.01, "2026-07-10"),
                List.of(new WeatherReportRecord.SocialStat("DC", "Dakota Gold", 40, 22, 728)),
                new WeatherReportRecord.CryptoStat(2.27e12, -0.32, 56.1, 71, "GREED",
                        0.0034, 37.5,
                        List.of(new WeatherReportRecord.TrendingCoin("Cash Dog", "CASHDOG", 6096.7))),
                List.of(new WeatherReportRecord.BetStat("Fed cut in September?", "Yes", 78.0,
                        3_936_100.0)),
                List.of(new WeatherReportRecord.ShortVolStat("HOOD", 62.0, "2026-07-10")),
                List.of(new WeatherReportRecord.DepthStat("RHM", 2100.0, "EUR", 8.2, 12, 5, 1,
                        "Q3-Bericht", "2026-11-06", 0.6, "D. E. Shaw", null)),
                List.of(new WeatherReportRecord.WatchlistStat("Rheinmetall", "RHM", 2.3,
                        1980.0, "EUR")),
                List.of("Rheinmetall"),
                List.of(new WeatherReportRecord.OutlookStat("J P Morgan Chase & Co",
                        "JPM, erw. EPS $5.52", null, "pre-market", "EARNINGS")),
                new WeatherReportRecord.PegelStat(54.0, "low"),
                3.941417901613009E13,
                new WeatherReportRecord.ExchangeWeatherStat(21.7, "clear-night"),
                new WeatherReportRecord.MoonStat("WAXING_GIBBOUS", 87, 3),
                List.of(new WeatherReportRecord.DaypartStat("MORNING", "SUNNY", 14, 9, 3, 1, "NVIDIA"),
                        new WeatherReportRecord.DaypartStat("TOMORROW", "STORM", 0, 0, 0, 0,
                                "Core CPI m/m")));
    }

    @Test
    void fullReportSurvivesTheDiskRoundTrip() {
        new WeatherReportArchive(file()).append(rec("2026-07-10", "**Großwetterlage** ruhig."));

        WeatherReportArchive reloaded = new WeatherReportArchive(file());
        assertEquals(1, reloaded.size());
        WeatherReportRecord r = reloaded.byDate("2026-07-10").orElseThrow();
        assertEquals("**Großwetterlage** ruhig.", r.text());
        assertEquals(42, r.headlineCount());
        assertEquals(List.of(23950.0, 23980.0, 24013.0), r.indices().get(0).spark());
        assertEquals("NVDA", r.tickers().get(0).ticker());
        assertEquals(7_800_000_000L, r.tickers().get(0).turnoverEur());
        assertEquals(4, r.news().get(0).citations());
        assertEquals(71, r.sentiment().cryptoScore());
        assertEquals("market_momentum_sp500", r.sentiment().components().get(0).key());
        // The whole world block must survive verbatim — one equality over everything.
        assertEquals(world(), r.world());
        assertEquals(1, r.charts().size());
        assertEquals("Der Tag auf einen Blick", r.charts().get(0).title());
        assertTrue(r.charts().get(0).svg().startsWith("<svg"));
    }

    @Test
    void preWorldArchiveLinesLoadWithNullWorld() throws Exception {
        // A v1 line (no world, three-arg sentiment) — the shape every existing
        // install already has on disk.
        String v1 = "{\"date\":\"2026-07-01\",\"generatedAt\":1700000000,\"text\":\"alt\","
                + "\"language\":\"de\",\"headlineCount\":5,\"importantCount\":0,"
                + "\"indices\":[],\"tickers\":[],\"news\":[],"
                + "\"sentiment\":{\"score\":50,\"band\":\"NEUTRAL\",\"previousClose\":49}}\n";
        Files.writeString(file(), v1, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        WeatherReportArchive reloaded = new WeatherReportArchive(file());
        WeatherReportRecord r = reloaded.byDate("2026-07-01").orElseThrow();
        assertEquals("alt", r.text());
        assertEquals(50, r.sentiment().score());
        assertTrue(r.sentiment().components().isEmpty());
        assertEquals(null, r.world());
    }

    @Test
    void oneReportPerDayAppendIsIdempotent() {
        WeatherReportArchive archive = new WeatherReportArchive(file());
        archive.append(rec("2026-07-10", "Erster."));
        archive.append(rec("2026-07-10", "Zweiter — darf nie ankommen."));

        assertEquals(1, archive.size());
        assertEquals("Erster.", new WeatherReportArchive(file())
                .byDate("2026-07-10").orElseThrow().text());
    }

    @Test
    void recentIsNewestFirstAndCapped() {
        WeatherReportArchive archive = new WeatherReportArchive(file());
        archive.append(rec("2026-07-08", "a"));
        archive.append(rec("2026-07-09", "b"));
        archive.append(rec("2026-07-10", "c"));

        List<WeatherReportRecord> recent = archive.recent(2);
        assertEquals(List.of("2026-07-10", "2026-07-09"),
                recent.stream().map(WeatherReportRecord::date).toList());
    }

    @Test
    void tornLineFromACrashIsSkippedOnLoad() throws Exception {
        new WeatherReportArchive(file()).append(rec("2026-07-09", "intakt"));
        Files.writeString(file(), "{\"date\":\"2026-07-10\",\"generatedAt\":17", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        WeatherReportArchive reloaded = new WeatherReportArchive(file());
        assertEquals(1, reloaded.size());
        assertTrue(reloaded.byDate("2026-07-09").isPresent());
    }
}
