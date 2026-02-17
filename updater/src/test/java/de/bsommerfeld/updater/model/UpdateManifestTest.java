package de.bsommerfeld.updater.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UpdateManifestTest {

    @Test
    void filesByPath_shouldReturnCorrectMapping() {
        var files = List.of(
                new FileEntry("lib/core.jar", "hash1", 100),
                new FileEntry("lib/util.jar", "hash2", 200));
        var manifest = new UpdateManifest("1.0.0", files);

        Map<String, FileEntry> map = manifest.filesByPath();
        assertEquals(2, map.size());
        assertEquals("hash1", map.get("lib/core.jar").sha256());
        assertEquals("hash2", map.get("lib/util.jar").sha256());
    }

    @Test
    void filesByPath_shouldReturnEmptyForNoFiles() {
        var manifest = new UpdateManifest("1.0.0", List.of());
        assertTrue(manifest.filesByPath().isEmpty());
    }

    @Test
    void version_shouldReturnCorrectValue() {
        var manifest = new UpdateManifest("2.5.0", List.of());
        assertEquals("2.5.0", manifest.version());
    }

    @Test
    void files_shouldReturnAllEntries() {
        var files = List.of(
                new FileEntry("a", "h1", 1),
                new FileEntry("b", "h2", 2),
                new FileEntry("c", "h3", 3));
        var manifest = new UpdateManifest("1.0", files);
        assertEquals(3, manifest.files().size());
    }
}
