package de.bsommerfeld.wsbg.terminal.core.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StorageUtilsTest {

    @Test
    void getAppDataDir_shouldReturnNonNullPath() {
        Path dir = StorageUtils.getAppDataDir("test-app");
        assertNotNull(dir);
    }

    @Test
    void getAppDataDir_shouldContainAppName() {
        Path dir = StorageUtils.getAppDataDir("test-app");
        assertTrue(dir.toString().contains("test-app"));
    }

    @Test
    void getAppDataDir_shouldBeAbsolute() {
        Path dir = StorageUtils.getAppDataDir("test-app");
        assertTrue(dir.isAbsolute());
    }

    @Test
    void getAppDataDir_shouldProducePlatformSpecificPath() {
        Path dir = StorageUtils.getAppDataDir("test-app");
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
        Path appDir = StorageUtils.getAppDataDir("test-app");
        Path logsDir = StorageUtils.getLogsDir("test-app");

        assertEquals(appDir.resolve("logs"), logsDir);
    }

    @Test
    void getLogsDir_shouldEndWithLogs() {
        Path logsDir = StorageUtils.getLogsDir("test-app");
        assertEquals("logs", logsDir.getFileName().toString());
    }

    @Test
    void getAppDataDir_differentNames_shouldProduceDifferentPaths() {
        Path dir1 = StorageUtils.getAppDataDir("app-one");
        Path dir2 = StorageUtils.getAppDataDir("app-two");
        assertNotEquals(dir1, dir2);
    }
}
