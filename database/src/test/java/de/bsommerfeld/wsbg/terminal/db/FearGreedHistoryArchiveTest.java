package de.bsommerfeld.wsbg.terminal.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permanent Fear&amp;Greed daily history: append-only JSONL, idempotent on
 * the date, latest-date cursor for the top-up, out-of-band scores rejected,
 * torn lines skipped on load.
 */
class FearGreedHistoryArchiveTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("fear-greed-history.jsonl");
    }

    @Test
    void appendsLooksUpAndReloads() {
        FearGreedHistoryArchive archive = new FearGreedHistoryArchive(file());
        assertTrue(archive.append(new FearGreedDayRecord("2026-07-10", 62.4, "greed")));
        assertTrue(archive.append(new FearGreedDayRecord("2026-07-13", 24.1, "extreme fear")));
        assertEquals(2, archive.size());
        assertEquals("2026-07-13", archive.latestDate().orElseThrow());
        assertEquals(62.4, archive.byDate("2026-07-10").orElseThrow().score(), 1e-9);

        FearGreedHistoryArchive reloaded = new FearGreedHistoryArchive(file());
        assertEquals(2, reloaded.size());
        assertEquals("2026-07-13", reloaded.latestDate().orElseThrow());
        assertEquals("extreme fear", reloaded.byDate("2026-07-13").orElseThrow().rating());
    }

    @Test
    void sameDateIsWrittenOnce() {
        FearGreedHistoryArchive archive = new FearGreedHistoryArchive(file());
        assertTrue(archive.append(new FearGreedDayRecord("2026-07-13", 24.1, "extreme fear")));
        assertFalse(archive.append(new FearGreedDayRecord("2026-07-13", 99.0, "extreme greed")));
        assertEquals(24.1, archive.byDate("2026-07-13").orElseThrow().score(), 1e-9);
        assertEquals(1, new FearGreedHistoryArchive(file()).size());
    }

    @Test
    void outOfBandScoresAreRejected() {
        FearGreedHistoryArchive archive = new FearGreedHistoryArchive(file());
        assertFalse(archive.append(new FearGreedDayRecord("2026-07-13", -1, "")));
        assertFalse(archive.append(new FearGreedDayRecord("2026-07-13", 101, "")));
        assertFalse(archive.append(new FearGreedDayRecord("2026-07-13", Double.NaN, "")));
        assertFalse(archive.append(new FearGreedDayRecord(" ", 50, "")));
        assertTrue(archive.latestDate().isEmpty());
    }

    @Test
    void tornLineIsSkippedOnLoad() throws Exception {
        FearGreedHistoryArchive archive = new FearGreedHistoryArchive(file());
        archive.append(new FearGreedDayRecord("2026-07-13", 24.1, "extreme fear"));
        Files.writeString(file(), "{\"date\":\"2026-07-1", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        FearGreedHistoryArchive reloaded = new FearGreedHistoryArchive(file());
        assertEquals(1, reloaded.size());
        assertEquals("2026-07-13", reloaded.latestDate().orElseThrow());
    }
}
