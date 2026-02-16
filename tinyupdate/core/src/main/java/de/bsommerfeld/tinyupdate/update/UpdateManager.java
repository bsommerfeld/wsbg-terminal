package de.bsommerfeld.tinyupdate.update;

import de.bsommerfeld.tinyupdate.hash.HashUtil;
import de.bsommerfeld.tinyupdate.model.FileEntry;
import de.bsommerfeld.tinyupdate.model.UpdateManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compares local file state against a remote {@link UpdateManifest} and determines
 * which files are outdated, missing, or orphaned.
 *
 * <p>Uses SHA-256 hash comparison — not version strings — so partial updates
 * or corrupted files are detected and repaired automatically.
 */
public final class UpdateManager {

    private final Path appDirectory;

    public UpdateManager(Path appDirectory) {
        this.appDirectory = appDirectory;
    }

    /**
     * Diffs local files against the remote manifest.
     *
     * @return a result containing files to download and files to delete
     */
    public UpdateCheckResult check(UpdateManifest manifest) throws IOException {
        Map<String, FileEntry> remote = manifest.filesByPath();

        List<FileEntry> outdated = new ArrayList<>();
        for (FileEntry entry : manifest.files()) {
            Path localFile = appDirectory.resolve(entry.path());
            if (!Files.exists(localFile) || !hashMatches(localFile, entry.sha256())) {
                outdated.add(entry);
            }
        }

        Set<String> remotePaths = remote.keySet();
        List<String> orphaned = collectLocalFiles().stream()
                .filter(p -> !remotePaths.contains(p))
                .collect(Collectors.toList());

        return new UpdateCheckResult(outdated, orphaned);
    }

    /**
     * Deletes orphaned files that no longer appear in the manifest.
     * Empty parent directories are removed as well.
     */
    public void deleteOrphans(List<String> orphanedPaths) throws IOException {
        for (String relative : orphanedPaths) {
            Path file = appDirectory.resolve(relative);
            Files.deleteIfExists(file);

            // Walk up and remove empty directories, but never the app root
            Path parent = file.getParent();
            while (parent != null && !parent.equals(appDirectory) && isEmptyDirectory(parent)) {
                Files.delete(parent);
                parent = parent.getParent();
            }
        }
    }

    /**
     * Verifies that all files in the manifest match their expected SHA-256 hash.
     *
     * @throws UpdateException if any file is missing or corrupted
     */
    public void verify(UpdateManifest manifest) throws UpdateException, IOException {
        for (FileEntry entry : manifest.files()) {
            Path localFile = appDirectory.resolve(entry.path());
            if (!Files.exists(localFile)) {
                throw new UpdateException("Missing after update: " + entry.path());
            }
            if (!hashMatches(localFile, entry.sha256())) {
                throw new UpdateException("Hash mismatch after update: " + entry.path());
            }
        }
    }

    private boolean hashMatches(Path file, String expectedHash) throws IOException {
        String actual = HashUtil.sha256(file);
        return actual.equalsIgnoreCase(expectedHash);
    }

    /**
     * Collects all relative file paths under lib/ and bin/ directories.
     * Only these directories are managed by the updater — config/ and logs/ are user-owned.
     */
    private List<String> collectLocalFiles() throws IOException {
        List<String> managed = new ArrayList<>();
        collectFromSubdir("lib", managed);
        collectFromSubdir("bin", managed);
        return managed;
    }

    private void collectFromSubdir(String subdirName, List<String> out) throws IOException {
        Path subdir = appDirectory.resolve(subdirName);
        if (!Files.isDirectory(subdir)) return;

        try (Stream<Path> walk = Files.walk(subdir)) {
            walk.filter(Files::isRegularFile)
                    .map(appDirectory::relativize)
                    .map(Path::toString)
                    // Normalize to forward slashes for cross-platform manifest compatibility
                    .map(p -> p.replace('\\', '/'))
                    .forEach(out::add);
        }
    }

    private boolean isEmptyDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (var entries = Files.list(dir)) {
            return entries.findFirst().isEmpty();
        }
    }
}
