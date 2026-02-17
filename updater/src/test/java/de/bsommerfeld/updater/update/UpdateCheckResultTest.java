package de.bsommerfeld.updater.update;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckResultTest {

    @Test
    void isUpToDate_shouldReturnTrueWhenEmpty() {
        var result = new UpdateCheckResult(List.of(), List.of());
        assertTrue(result.isUpToDate());
    }

    @Test
    void isUpToDate_shouldReturnFalseWithOutdated() {
        var result = new UpdateCheckResult(
                List.of(new de.bsommerfeld.updater.model.FileEntry("a", "h", 1)),
                List.of());
        assertFalse(result.isUpToDate());
    }

    @Test
    void isUpToDate_shouldReturnFalseWithOrphaned() {
        var result = new UpdateCheckResult(List.of(), List.of("orphan.jar"));
        assertFalse(result.isUpToDate());
    }

    @Test
    void totalChanges_shouldSumBothLists() {
        var result = new UpdateCheckResult(
                List.of(
                        new de.bsommerfeld.updater.model.FileEntry("a", "h", 1),
                        new de.bsommerfeld.updater.model.FileEntry("b", "h", 2)),
                List.of("orphan1", "orphan2", "orphan3"));
        assertEquals(5, result.totalChanges());
    }

    @Test
    void totalChanges_shouldReturnZeroWhenUpToDate() {
        var result = new UpdateCheckResult(List.of(), List.of());
        assertEquals(0, result.totalChanges());
    }
}
