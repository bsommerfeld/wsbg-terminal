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
                        List.of(23950.0, 23980.0, 24013.0))),
                List.of(new WeatherReportRecord.TickerStat("NVDA", "NVIDIA", 7, 1,
                        142.5, "EUR", 3.1, 55_000_000L)),
                List.of(new WeatherReportRecord.NewsStat("NVIDIA hebt Prognose an", "Reuters", 4)));
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
        assertEquals(4, r.news().get(0).citations());
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
