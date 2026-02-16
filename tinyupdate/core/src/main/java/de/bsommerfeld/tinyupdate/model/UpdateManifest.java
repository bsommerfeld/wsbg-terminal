package de.bsommerfeld.tinyupdate.model;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The full manifest shipped alongside every release.
 * Contains version metadata and an exhaustive file inventory with SHA-256 hashes.
 *
 * @param version tag name of the release (e.g. "1.2.0")
 * @param files   every file the release delivers
 */
public record UpdateManifest(String version, List<FileEntry> files) {

    /**
     * Returns a lookup map keyed by relative file path.
     * Enables O(1) diffing against local state.
     */
    public Map<String, FileEntry> filesByPath() {
        return files.stream().collect(Collectors.toMap(FileEntry::path, e -> e));
    }
}
