package de.bsommerfeld.updater.launcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StagedLauncher}'s pure decision logic — the version
 * comparison behind the strictly-newer handoff rule. Process spawning and the
 * TinyUpdate sync are not exercised here.
 */
class StagedLauncherTest {

    @Nested
    @DisplayName("compareVersions")
    class CompareVersions {

        @Test
        @DisplayName("plain semver ordering")
        void semverOrdering() {
            assertTrue(StagedLauncher.compareVersions("1.5.1", "1.5.0") > 0);
            assertTrue(StagedLauncher.compareVersions("1.5.0", "1.5.1") < 0);
            assertEquals(0, StagedLauncher.compareVersions("1.5.0", "1.5.0"));
            assertTrue(StagedLauncher.compareVersions("2.0.0", "1.9.9") > 0);
            assertTrue(StagedLauncher.compareVersions("1.10.0", "1.9.0") > 0);
        }

        @Test
        @DisplayName("release tags with v prefix compare against bare versions")
        void vPrefixTolerated() {
            assertEquals(0, StagedLauncher.compareVersions("v1.5.0", "1.5.0"));
            assertTrue(StagedLauncher.compareVersions("v1.6.0", "1.5.9") > 0);
        }

        @Test
        @DisplayName("missing segments count as zero")
        void missingSegmentsAreZero() {
            assertEquals(0, StagedLauncher.compareVersions("1.2", "1.2.0"));
            assertTrue(StagedLauncher.compareVersions("1.2.1", "1.2") > 0);
        }

        @Test
        @DisplayName("a garbled version never wins against a clean one")
        void garbledNeverWins() {
            assertTrue(StagedLauncher.compareVersions("garbage", "1.0.0") < 0);
            assertTrue(StagedLauncher.compareVersions("99999999999999999999", "1.0.0") < 0);
        }
    }

}
