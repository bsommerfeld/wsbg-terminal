package de.bsommerfeld.updater.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpdateProgressTest {

    @Test
    void indeterminate_shouldSetMinusOneRatio() {
        var progress = UpdateProgress.indeterminate("Checking...");
        assertEquals(-1.0, progress.progressRatio(), 0.001);
        assertEquals("Checking...", progress.phase());
        assertEquals(0, progress.step());
    }

    @Test
    void indeterminateWithStep_shouldSetMinusOneRatio() {
        var progress = UpdateProgress.indeterminate("Cleaning up", 3, 5);
        assertEquals(-1.0, progress.progressRatio(), 0.001);
        assertEquals(3, progress.step());
        assertEquals(5, progress.totalSteps());
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
    void ofWithStep_shouldStoreStepInfo() {
        var progress = UpdateProgress.of("Downloading", 2, 5, 0.26);
        assertEquals("Downloading", progress.phase());
        assertEquals(2, progress.step());
        assertEquals(5, progress.totalSteps());
        assertEquals(0.26, progress.progressRatio(), 0.001);
    }

    @Test
    void of_shouldClampWithStep() {
        var progress = UpdateProgress.of("Phase", 1, 3, 2.0);
        assertEquals(1.0, progress.progressRatio(), 0.001);
    }

    @Test
    void equality_shouldMatchOnAllFields() {
        var a = UpdateProgress.of("Phase", 1, 3, 0.5);
        var b = UpdateProgress.of("Phase", 1, 3, 0.5);
        assertEquals(a, b);
    }
}
