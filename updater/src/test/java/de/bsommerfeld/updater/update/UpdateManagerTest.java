package de.bsommerfeld.updater.update;

import de.bsommerfeld.updater.model.FileEntry;
import de.bsommerfeld.updater.model.UpdateManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for UpdateManager: file diffing, orphan detection/deletion,
 * and verification against a manifest.
 */
class UpdateManagerTest {

    @TempDir
    Path appDir;

    private UpdateManager manager;

    @BeforeEach
    void setUp() {
        manager = new UpdateManager(appDir);
    }

    // -- check --

    @Test
    void check_shouldDetectMissingFiles() throws IOException {
        var manifest = new UpdateManifest("1.0", List.of(
                new FileEntry("lib/core.jar", "abc123", 100)));

        UpdateCheckResult result = manager.check(manifest);
        assertEquals(1, result.outdated().size());
        assertEquals("lib/core.jar", result.outdated().get(0).path());
    }

    @Test
    void check_shouldDetectMatchingFiles() throws IOException {
        // Write a file and compute its real hash
        Path libDir = appDir.resolve("lib");
        Files.createDirectories(libDir);
        Path file = libDir.resolve("core.jar");
        Files.writeString(file, "jar content");

        String hash = de.bsommerfeld.updater.hash.HashUtil.sha256(file);
        var manifest = new UpdateManifest("1.0", List.of(
                new FileEntry("lib/core.jar", hash, Files.size(file))));

        UpdateCheckResult result = manager.check(manifest);
        assertTrue(result.outdated().isEmpty());
    }

    @Test
    void check_shouldDetectCorruptedFiles() throws IOException {
        Path libDir = appDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("core.jar"), "corrupted");

        var manifest = new UpdateManifest("1.0", List.of(
                new FileEntry("lib/core.jar", "wrong_hash_value", 100)));

        UpdateCheckResult result = manager.check(manifest);
        assertEquals(1, result.outdated().size());
    }

    @Test
    void check_shouldDetectOrphanedFilesInLib() throws IOException {
        Path libDir = appDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("orphan.jar"), "leftover");

        var manifest = new UpdateManifest("1.0", List.of());

        UpdateCheckResult result = manager.check(manifest);
        assertTrue(result.orphaned().contains("lib/orphan.jar"));
    }

    @Test
    void check_shouldReturnUpToDateForEmpty() throws IOException {
        var manifest = new UpdateManifest("1.0", List.of());
        UpdateCheckResult result = manager.check(manifest);
        assertTrue(result.isUpToDate());
    }

    // -- deleteOrphans --

    @Test
    void deleteOrphans_shouldRemoveFiles() throws IOException {
        Path libDir = appDir.resolve("lib");
        Files.createDirectories(libDir);
        Path orphan = libDir.resolve("old.jar");
        Files.writeString(orphan, "old");

        manager.deleteOrphans(List.of("lib/old.jar"));
        assertFalse(Files.exists(orphan));
    }

    @Test
    void deleteOrphans_shouldRemoveEmptyParentDirs() throws IOException {
        Path deepDir = appDir.resolve("lib/sub/deep");
        Files.createDirectories(deepDir);
        Files.writeString(deepDir.resolve("file.txt"), "content");

        manager.deleteOrphans(List.of("lib/sub/deep/file.txt"));
        // Parent dirs should be cleaned up since they're now empty
        assertFalse(Files.exists(deepDir));
    }

    @Test
    void deleteOrphans_shouldNotDeleteAppRoot() throws IOException {
        Path libDir = appDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("only.jar"), "content");

        manager.deleteOrphans(List.of("lib/only.jar"));
        // lib dir should be removed (empty), but appDir itself must remain
        assertTrue(Files.exists(appDir));
    }

    @Test
    void deleteOrphans_shouldHandleAlreadyDeletedFiles() throws IOException {
        // Should not throw even if file doesn't exist
        assertDoesNotThrow(() -> manager.deleteOrphans(List.of("lib/ghost.jar")));
    }

    // -- verify --

    @Test
    void verify_shouldPassForMatchingHashes() throws Exception {
        Path libDir = appDir.resolve("lib");
        Files.createDirectories(libDir);
        Path file = libDir.resolve("core.jar");
        Files.writeString(file, "correct content");

        String hash = de.bsommerfeld.updater.hash.HashUtil.sha256(file);
        var manifest = new UpdateManifest("1.0", List.of(
                new FileEntry("lib/core.jar", hash, Files.size(file))));

        assertDoesNotThrow(() -> manager.verify(manifest));
    }

    @Test
    void verify_shouldThrowForMissingFile() {
        var manifest = new UpdateManifest("1.0", List.of(
                new FileEntry("lib/missing.jar", "hash", 100)));

        assertThrows(UpdateException.class, () -> manager.verify(manifest));
    }

    @Test
    void verify_shouldThrowForHashMismatch() throws IOException {
        Path libDir = appDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("corrupt.jar"), "wrong");

        var manifest = new UpdateManifest("1.0", List.of(
                new FileEntry("lib/corrupt.jar", "expected_hash", 100)));

        assertThrows(UpdateException.class, () -> manager.verify(manifest));
    }
}
