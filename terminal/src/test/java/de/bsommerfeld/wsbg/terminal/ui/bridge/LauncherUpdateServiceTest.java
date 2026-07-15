package de.bsommerfeld.wsbg.terminal.ui.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LauncherUpdateService}'s pure decision logic — the
 * hull-generation comparison and the platform installer pick. The service
 * itself (PushHub wiring, Desktop browse) is not constructed here.
 */
class LauncherUpdateServiceTest {

    @Nested
    @DisplayName("isHullOutdated")
    class IsHullOutdated {

        @Test
        @DisplayName("dev run (no launcher executable) never nags")
        void devRunNeverNags() {
            assertFalse(LauncherUpdateService.isHullOutdated(null, null));
            assertFalse(LauncherUpdateService.isHullOutdated("", "1"));
            assertFalse(LauncherUpdateService.isHullOutdated("  ", null));
        }

        @Test
        @DisplayName("pre-handshake hull (no generation env) is outdated")
        void missingGenerationIsOutdated() {
            assertTrue(LauncherUpdateService.isHullOutdated("/opt/wsbg/launcher", null));
            assertTrue(LauncherUpdateService.isHullOutdated("/opt/wsbg/launcher", ""));
        }

        @Test
        @DisplayName("garbled generation counts as pre-handshake hull")
        void garbledGenerationIsOutdated() {
            assertTrue(LauncherUpdateService.isHullOutdated("/opt/wsbg/launcher", "abc"));
        }

        @Test
        @DisplayName("current and future generations are not outdated")
        void currentGenerationIsFine() {
            String required = String.valueOf(LauncherUpdateService.REQUIRED_GENERATION);
            assertFalse(LauncherUpdateService.isHullOutdated("/opt/wsbg/launcher", required));
            assertFalse(LauncherUpdateService.isHullOutdated("/opt/wsbg/launcher",
                    String.valueOf(LauncherUpdateService.REQUIRED_GENERATION + 1)));
            // whitespace-tolerant
            assertFalse(LauncherUpdateService.isHullOutdated("/opt/wsbg/launcher", " " + required + " "));
        }

        @Test
        @DisplayName("older reported generation is outdated")
        void olderGenerationIsOutdated() {
            assertTrue(LauncherUpdateService.isHullOutdated("/opt/wsbg/launcher",
                    String.valueOf(LauncherUpdateService.REQUIRED_GENERATION - 1)));
        }
    }

    @Nested
    @DisplayName("installerUrl")
    class InstallerUrl {

        private static final String BASE =
                "https://github.com/bsommerfeld/wsbg-terminal/releases/latest/download/";

        @Test
        @DisplayName("Windows and macOS pick their stable asset")
        void windowsAndMac() {
            assertEquals(BASE + "WSBG-Terminal-Windows.exe",
                    LauncherUpdateService.installerUrl("Windows 11", false, false));
            assertEquals(BASE + "WSBG-Terminal-macOS.dmg",
                    LauncherUpdateService.installerUrl("Mac OS X", false, false));
        }

        @Test
        @DisplayName("Linux picks .deb or .rpm by package manager, dpkg first")
        void linuxByPackageManager() {
            assertEquals(BASE + "WSBG-Terminal-Linux.deb",
                    LauncherUpdateService.installerUrl("Linux", true, false));
            assertEquals(BASE + "WSBG-Terminal-Linux.rpm",
                    LauncherUpdateService.installerUrl("Linux", false, true));
            assertEquals(BASE + "WSBG-Terminal-Linux.deb",
                    LauncherUpdateService.installerUrl("Linux", true, true));
        }

        @Test
        @DisplayName("exotic distro without dpkg/rpm falls back to the releases page")
        void exoticLinuxFallsBack() {
            assertEquals("https://github.com/bsommerfeld/wsbg-terminal/releases/latest",
                    LauncherUpdateService.installerUrl("Linux", false, false));
        }
    }
}
