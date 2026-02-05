package de.bsommerfeld.updater.launcher;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StorageResolver's OS-aware path resolution.
 * Only macOS branch is verifiable on the current platform;
 * this validates the contract and path structure.
 */
class StorageResolverTest {

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
