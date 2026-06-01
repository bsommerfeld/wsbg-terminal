package de.bsommerfeld.updater.launcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StorageResolver's OS-aware path resolution. The parameterised
 * {@code resolve(os, env, home)} seam lets every platform branch be verified
 * deterministically regardless of the host OS.
 */
class StorageResolverTest {

    // ── Cross-platform resolution via the test seam ────────────────────────

    @Test
    void windows_usesLocalAppData_andNeverRoaming() {
        Path p = StorageResolver.resolve("Windows 11",
                Map.of("LOCALAPPDATA", "/win/local"), "/win/home");

        assertEquals(Path.of("/win/local", "wsbg-terminal"), p);
        assertFalse(p.toString().toLowerCase().contains("roaming"));
    }

    @Test
    void windows_prefersLocalAppData_evenWhenRoamingAppDataPresent() {
        Path p = StorageResolver.resolve("Windows 11",
                Map.of("APPDATA", "/win/roaming", "LOCALAPPDATA", "/win/local"), "/win/home");

        assertEquals(Path.of("/win/local", "wsbg-terminal"), p);
    }

    @Test
    void windows_fallsBackToLocalUnderHome_whenEnvMissing() {
        Path p = StorageResolver.resolve("Windows 11", Map.of(), "/win/home");

        assertEquals(Path.of("/win/home", "AppData", "Local", "wsbg-terminal"), p);
    }

    @Test
    void macos_usesApplicationSupport() {
        Path p = StorageResolver.resolve("Mac OS X", Map.of(), "/Users/x");

        assertEquals(Path.of("/Users/x", "Library", "Application Support", "wsbg-terminal"), p);
    }

    @Test
    void linux_prefersXdgDataHome_elseLocalShare() {
        assertEquals(Path.of("/xdg", "wsbg-terminal"),
                StorageResolver.resolve("Linux", Map.of("XDG_DATA_HOME", "/xdg"), "/home/x"));
        assertEquals(Path.of("/home/x", ".local", "share", "wsbg-terminal"),
                StorageResolver.resolve("Linux", Map.of(), "/home/x"));
    }

    @Test
    void resolve_shouldReturnNonNullPath() {
        Path path = StorageResolver.resolve();
        assertNotNull(path);
    }

    @Test
    void resolve_shouldReturnAbsolutePath() {
        Path path = StorageResolver.resolve();
        assertTrue(path.isAbsolute());
    }

    @Test
    void resolve_shouldEndWithAppDirName() {
        Path path = StorageResolver.resolve();
        assertEquals("wsbg-terminal", path.getFileName().toString());
    }

    @Test
    void resolve_shouldContainUserHome() {
        Path path = StorageResolver.resolve();
        String userHome = System.getProperty("user.home");
        assertTrue(path.toString().startsWith(userHome),
                "Path should be under user's home directory");
    }

    @Test
    void resolve_shouldReturnConsistentResults() {
        Path first = StorageResolver.resolve();
        Path second = StorageResolver.resolve();
        assertEquals(first, second);
    }
}
