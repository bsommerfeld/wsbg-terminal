package de.bsommerfeld.wsbg.terminal.core.util;

import de.bsommerfeld.wsbg.terminal.core.config.OperatingSystem;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StorageUtilsTest {

    // ── Cross-platform resolution via the test seam ────────────────────────

    @Test
    void windows_usesLocalAppData_andNeverRoaming() {
        Path dir = StorageUtils.getAppDataDir(OperatingSystem.WINDOWS,
                Map.of("LOCALAPPDATA", "/win/local"), "/win/home");

        assertEquals(Paths.get("/win/local", "wsbg-terminal"), dir);
        assertFalse(dir.toString().toLowerCase().contains("roaming"),
                "Windows must use Local, not Roaming (multi-GB ai/ must not sync)");
    }

    @Test
    void windows_prefersLocalAppData_evenWhenRoamingAppDataPresent() {
        // A leftover APPDATA must not win — we deliberately ignore Roaming.
        Path dir = StorageUtils.getAppDataDir(OperatingSystem.WINDOWS,
                Map.of("APPDATA", "/win/roaming", "LOCALAPPDATA", "/win/local"), "/win/home");

        assertEquals(Paths.get("/win/local", "wsbg-terminal"), dir);
    }

    @Test
    void windows_fallsBackToLocalUnderHome_whenEnvMissing() {
        Path dir = StorageUtils.getAppDataDir(OperatingSystem.WINDOWS, Map.of(), "/win/home");

        assertEquals(Paths.get("/win/home", "AppData", "Local", "wsbg-terminal"), dir);
    }

    @Test
    void macos_usesApplicationSupport() {
        Path dir = StorageUtils.getAppDataDir(OperatingSystem.MACOS, Map.of(), "/Users/x");

        assertEquals(Paths.get("/Users/x", "Library", "Application Support", "wsbg-terminal"), dir);
    }

    @Test
    void linux_prefersXdgDataHome_elseLocalShare() {
        assertEquals(Paths.get("/xdg", "wsbg-terminal"),
                StorageUtils.getAppDataDir(OperatingSystem.LINUX, Map.of("XDG_DATA_HOME", "/xdg"), "/home/x"));
        assertEquals(Paths.get("/home/x", ".local", "share", "wsbg-terminal"),
                StorageUtils.getAppDataDir(OperatingSystem.LINUX, Map.of(), "/home/x"));
    }

    @Test
    void getAppDataDir_shouldReturnNonNullPath() {
        Path dir = StorageUtils.getAppDataDir();
        assertNotNull(dir);
    }

    @Test
    void getAppDataDir_shouldContainAppName() {
        Path dir = StorageUtils.getAppDataDir();
        assertTrue(dir.toString().contains("wsbg-terminal"));
    }

    @Test
    void getAppDataDir_shouldBeAbsolute() {
        Path dir = StorageUtils.getAppDataDir();
        assertTrue(dir.isAbsolute());
    }

    @Test
    void getAppDataDir_shouldProducePlatformSpecificPath() {
        Path dir = StorageUtils.getAppDataDir();
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("mac") || os.contains("darwin")) {
            assertTrue(dir.toString().contains("Library/Application Support"));
        } else if (os.contains("win")) {
            assertTrue(dir.toString().contains("AppData") || dir.toString().contains("Roaming"));
        } else {
            // Linux: either XDG_DATA_HOME or ~/.local/share
            assertTrue(dir.toString().contains(".local/share")
                    || dir.toString().contains(System.getenv("XDG_DATA_HOME") != null
                            ? System.getenv("XDG_DATA_HOME")
                            : ".local/share"));
        }
    }

    @Test
    void getLogsDir_shouldBeSubdirOfAppDataDir() {
        Path appDir = StorageUtils.getAppDataDir();
        Path logsDir = StorageUtils.getLogsDir();

        assertEquals(appDir.resolve("logs"), logsDir);
    }

    @Test
    void getLogsDir_shouldEndWithLogs() {
        Path logsDir = StorageUtils.getLogsDir();
        assertEquals("logs", logsDir.getFileName().toString());
    }
}
