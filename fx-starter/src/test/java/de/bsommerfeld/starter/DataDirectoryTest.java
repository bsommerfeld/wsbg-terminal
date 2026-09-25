package de.bsommerfeld.starter;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataDirectoryTest {

    @Test
    void windows_usesLocalAppData_elseItsDefaultUnderHome() {
        assertEquals(Path.of("C:/Users/a/AppData/Local", "WSBG"),
                DataDirectory.of("WSBG", "Windows 11", "C:/Users/a", Map.of("LOCALAPPDATA", "C:/Users/a/AppData/Local")));
        assertEquals(Path.of("/home/a", "AppData", "Local", "WSBG"),
                DataDirectory.of("WSBG", "Windows 11", "/home/a", Map.of()));
    }

    @Test
    void macos_usesApplicationSupport() {
        assertEquals(Path.of("/Users/a/Library/Application Support/WSBG"),
                DataDirectory.of("WSBG", "Mac OS X", "/Users/a", Map.of()));
    }

    @Test
    void linux_usesXdgDataHome_elseLocalShare_lowerCased() {
        assertEquals(Path.of("/data/wsbg"), DataDirectory.of("WSBG", "Linux", "/home/a", Map.of("XDG_DATA_HOME", "/data")));
        assertEquals(Path.of("/home/a/.local/share/wsbg"), DataDirectory.of("WSBG", "Linux", "/home/a", Map.of()));
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> DataDirectory.of(" ", "Linux", "/home/a", Map.of()));
    }
}
