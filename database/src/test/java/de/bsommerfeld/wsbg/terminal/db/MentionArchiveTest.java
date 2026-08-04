package de.bsommerfeld.wsbg.terminal.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionArchiveTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    @TempDir
    Path dir;

    private MentionArchive archive() {
        return new MentionArchive(dir, ZONE);
    }

    private static Instant at(String isoDate, int hour) {
        return ZonedDateTime.of(LocalDate.parse(isoDate).atTime(hour, 0), ZONE).toInstant();
    }

    @Test
    void aDayHoldsWhatWasBookedOnIt() {
        MentionArchive a = archive();
        a.record(at("2026-08-04", 10), "t1_a", Map.of("$tsla", 2));
        a.record(at("2026-08-04", 11), "t1_b", Map.of("$tsla", 1, "telekom", 3));

        MentionDay day = a.day(LocalDate.parse("2026-08-04"));
        assertEquals(3, day.phrases().get("$tsla"));
        assertEquals(3, day.phrases().get("telekom"));
        assertEquals(2, day.items());
        assertEquals(6, day.total());
    }

    @Test
    void theSameItemIsNeverCountedTwice() {
        MentionArchive a = archive();
        assertTrue(a.record(at("2026-08-04", 10), "t1_a", Map.of("$tsla", 2)));
        assertFalse(a.record(at("2026-08-04", 10), "t1_a", Map.of("$tsla", 2)));
        assertEquals(2, a.day(LocalDate.parse("2026-08-04")).phrases().get("$tsla"));
    }

    @Test
    void aRestartDoesNotRecountWhatIsAlreadyOnDisk() {
        archive().record(at("2026-08-04", 10), "t1_a", Map.of("$tsla", 2));
        MentionArchive reopened = archive();
        assertFalse(reopened.record(at("2026-08-04", 12), "t1_a", Map.of("$tsla", 2)));
        assertEquals(2, reopened.day(LocalDate.parse("2026-08-04")).phrases().get("$tsla"));
    }

    @Test
    void daysStayApart() {
        MentionArchive a = archive();
        a.record(at("2026-08-03", 10), "t1_a", Map.of("$tsla", 1));
        a.record(at("2026-08-04", 10), "t1_b", Map.of("$tsla", 5));

        assertEquals(1, a.day(LocalDate.parse("2026-08-03")).phrases().get("$tsla"));
        assertEquals(5, a.day(LocalDate.parse("2026-08-04")).phrases().get("$tsla"));
    }

    @Test
    void aWindowIsFoldedIntoOnePicture() {
        MentionArchive a = archive();
        a.record(at("2026-08-01", 10), "t1_a", Map.of("$tsla", 1));
        a.record(at("2026-08-03", 10), "t1_b", Map.of("$tsla", 2, "telekom", 1));
        a.record(at("2026-08-04", 10), "t1_c", Map.of("telekom", 4));

        MentionDay folded = a.fold(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-04"));
        assertEquals(3, folded.phrases().get("$tsla"));
        assertEquals(5, folded.phrases().get("telekom"));
        assertEquals(3, folded.items());
    }

    @Test
    void aRangeReturnsOnlyTheDaysThatCarrySomething() {
        MentionArchive a = archive();
        a.record(at("2026-08-01", 10), "t1_a", Map.of("$tsla", 1));
        a.record(at("2026-08-04", 10), "t1_c", Map.of("telekom", 4));

        List<MentionDay> days = a.range(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-04"));
        assertEquals(2, days.size());
        assertEquals(LocalDate.parse("2026-08-01"), days.get(0).day());
        assertEquals(LocalDate.parse("2026-08-04"), days.get(1).day());
    }

    @Test
    void theEarliestDayIsTheLeftEdgeOfTheSlider() {
        MentionArchive a = archive();
        a.record(at("2026-07-19", 10), "t1_a", Map.of("$tsla", 1));
        a.record(at("2026-08-04", 10), "t1_b", Map.of("$tsla", 1));
        assertEquals(LocalDate.parse("2026-07-19"), a.earliestDay().orElseThrow());
        assertEquals(List.of(LocalDate.parse("2026-07-19"), LocalDate.parse("2026-08-04")),
                archive().storedDays());
    }

    @Test
    void anEmptyArchiveHasNoEdgeAndNoDay() {
        MentionArchive a = archive();
        assertTrue(a.earliestDay().isEmpty());
        assertTrue(a.day(LocalDate.parse("2026-08-04")).isEmpty());
        assertTrue(a.range(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-04")).isEmpty());
    }

    @Test
    void nothingToCountIsNotBooked() {
        MentionArchive a = archive();
        assertFalse(a.record(at("2026-08-04", 10), "t1_a", Map.of()));
        assertFalse(a.record(at("2026-08-04", 10), "  ", Map.of("$tsla", 1)));
        assertTrue(a.day(LocalDate.parse("2026-08-04")).isEmpty());
    }

    @Test
    void aTornLineFromACrashCostsOnlyItself() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("2026-08-04.jsonl"), """
                {"id":"t1_a","p":{"$tsla":2}}
                {"id":"t1_b","p":{"$tsl
                """);
        assertEquals(2, archive().day(LocalDate.parse("2026-08-04")).phrases().get("$tsla"));
    }

    @Test
    void anInvertedRangeReturnsNothingRatherThanLooping() {
        MentionArchive a = archive();
        a.record(at("2026-08-04", 10), "t1_a", Map.of("$tsla", 1));
        assertTrue(a.range(LocalDate.parse("2026-08-04"), LocalDate.parse("2026-08-01")).isEmpty());
    }
}
