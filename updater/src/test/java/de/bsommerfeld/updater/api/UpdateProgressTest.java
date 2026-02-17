package de.bsommerfeld.updater.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpdateProgressTest {

    @Test
    void indeterminate_shouldSetMinusOneRatio() {
        var progress = UpdateProgress.indeterminate("Checking...");
        assertEquals(-1.0, progress.progressRatio(), 0.001);
        assertEquals("Checking...", progress.phase());
        assertNull(progress.detail());
    }

    @Test
    void indeterminateWithDetail_shouldSetMinusOneRatio() {
        var progress = UpdateProgress.indeterminate("Cleaning up", "3 files");
        assertEquals(-1.0, progress.progressRatio(), 0.001);
        assertEquals("3 files", progress.detail());
    }

    @Test
    void of_shouldClampRatioToZeroOne() {
        var low = UpdateProgress.of("Phase", -0.5);
        assertEquals(0.0, low.progressRatio(), 0.001);

        var high = UpdateProgress.of("Phase", 1.5);
        assertEquals(1.0, high.progressRatio(), 0.001);
    }

    @Test
    void of_shouldPreserveValidRatio() {
        var progress = UpdateProgress.of("Phase", 0.5);
        assertEquals(0.5, progress.progressRatio(), 0.001);
    }

    @Test
    void ofWithDetail_shouldStoreDetailText() {
        var progress = UpdateProgress.of("Downloading", "3.2 MB / 12.4 MB", 0.26);
        assertEquals("Downloading", progress.phase());
        assertEquals("3.2 MB / 12.4 MB", progress.detail());
        assertEquals(0.26, progress.progressRatio(), 0.001);
    }

    @Test
    void of_shouldClampWithDetail() {
        var progress = UpdateProgress.of("Phase", "detail", 2.0);
        assertEquals(1.0, progress.progressRatio(), 0.001);
    }

    @Test
    void equality_shouldMatchOnAllFields() {
        var a = UpdateProgress.of("Phase", "detail", 0.5);
        var b = UpdateProgress.of("Phase", "detail", 0.5);
        assertEquals(a, b);
    }
}
