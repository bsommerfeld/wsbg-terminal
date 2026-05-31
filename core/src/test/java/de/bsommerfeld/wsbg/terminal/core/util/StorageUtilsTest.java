package de.bsommerfeld.wsbg.terminal.core.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StorageUtilsTest {

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
