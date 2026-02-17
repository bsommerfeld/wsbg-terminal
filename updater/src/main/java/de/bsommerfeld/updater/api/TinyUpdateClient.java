package de.bsommerfeld.updater.api;

import de.bsommerfeld.updater.download.Downloader;
import de.bsommerfeld.updater.json.JsonParser;
import de.bsommerfeld.updater.model.FileEntry;
import de.bsommerfeld.updater.model.UpdateManifest;
import de.bsommerfeld.updater.update.UpdateCheckResult;
import de.bsommerfeld.updater.update.UpdateManager;
import de.bsommerfeld.updater.util.ByteFormatter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GitHub Releases-backed implementation of {@link UpdateClient}.
 *
 * <h3>Update pipeline</h3>
 * 
 * <pre>
 * 1. Resolve latest release tag via GitHub API
 * 2. Compare tag against local version.txt → skip if equal
 * 3. Download update.json manifest → diff against local file hashes
 * 4. Download files.zip → extract only changed files
 * 5. Verify all hashes post-extraction
 * 6. Delete orphaned files no longer in the manifest
 * 7. Write new version to version.txt
 * </pre>
 *
 * <h3>Progress reporting</h3>
 * Every pipeline step emits {@link UpdateProgress} events via the consumer
 * callback, enabling the launcher UI to display phase names, detail text,
 * and a progress bar. Download progress includes formatted byte counts
 * (e.g. "3.2 MB / 12.4 MB").
 */
public final class TinyUpdateClient implements UpdateClient {

    private final GitHubRepository repository;
    private final Path appDirectory;
    private final UpdateManager updateManager;

    public TinyUpdateClient(GitHubRepository repository, Path appDirectory) {
        this.repository = repository;
        this.appDirectory = appDirectory;
        this.updateManager = new UpdateManager(appDirectory);
    }

    /**
     * Runs the full update pipeline.
     *
     * <p>
     * Returns early if the local version already matches the latest
     * release tag, or if all file hashes match despite a version
     * mismatch (e.g. re-release with same content).
     *
     * @return {@code true} if files were updated, {@code false} if already current
     */
    @Override
    public boolean update(Consumer<UpdateProgress> progress) throws Exception {
        progress.accept(UpdateProgress.indeterminate("Checking for updates..."));

        String releaseJson = Downloader.toString(repository.latestReleaseUrl());
        String tagName = JsonParser.extractString(releaseJson, "tag_name");

        if (tagName.equals(currentVersion())) {
            progress.accept(UpdateProgress.of("Up to date", 1.0));
            return false;
        }

        UpdateCheckResult diff = resolveChanges(releaseJson, progress);

        if (diff.isUpToDate()) {
            recordVersion(tagName);
            progress.accept(UpdateProgress.of("Up to date", 1.0));
            return false;
        }

        applyUpdate(releaseJson, diff, progress);

        recordVersion(tagName);
        progress.accept(UpdateProgress.of("Update complete", tagName, 1.0));
        return true;
    }

    @Override
    public String currentVersion() {
        Path versionFile = appDirectory.resolve("version.txt");
        if (!Files.exists(versionFile))
            return null;
        try {
            return Files.readString(versionFile).strip();
        } catch (IOException e) {
            return null;
        }
    }

    // =====================================================================
    // Pipeline Phases
    // =====================================================================

    /**
     * Downloads the manifest and diffs it against the local file state.
     * Returns the result containing which files need updating and which
     * are orphaned.
     */
    private UpdateCheckResult resolveChanges(String releaseJson,
            Consumer<UpdateProgress> progress) throws Exception {
        progress.accept(UpdateProgress.indeterminate("Downloading manifest..."));
        String manifestUrl = findAssetUrl(releaseJson, "update.json");
        String manifestJson = Downloader.toString(manifestUrl);
        UpdateManifest manifest = JsonParser.parseManifest(manifestJson);

        progress.accept(UpdateProgress.indeterminate("Comparing files..."));
        return updateManager.check(manifest);
    }

    /**
     * Downloads the zip, extracts outdated files, verifies integrity,
     * and cleans up orphans. The zip is downloaded fully into memory
     * before extraction — either the entire zip is available or nothing
     * is written to disk, preventing partial-file states on network failures.
     */
    private void applyUpdate(String releaseJson, UpdateCheckResult diff,
            Consumer<UpdateProgress> progress) throws Exception {
        byte[] zipData = downloadZip(releaseJson, diff, progress);

        progress.accept(UpdateProgress.indeterminate("Extracting files..."));
        extractOutdatedFiles(zipData, diff, progress);

        progress.accept(UpdateProgress.indeterminate("Verifying integrity..."));
        String manifestUrl = findAssetUrl(releaseJson, "update.json");
        UpdateManifest manifest = JsonParser.parseManifest(Downloader.toString(manifestUrl));
        updateManager.verify(manifest);

        cleanOrphans(diff, progress);
    }

    /**
     * Downloads the files.zip with byte-level progress reporting.
     * The detail line shows transferred and total size in human-readable
     * format (e.g. "3.2 MB / 12.4 MB").
     */
    private byte[] downloadZip(String releaseJson, UpdateCheckResult diff,
            Consumer<UpdateProgress> progress) throws Exception {
        String zipUrl = findAssetUrl(releaseJson, "files.zip");

        progress.accept(UpdateProgress.of(
                "Downloading update",
                diff.outdated().size() + " files to update",
                0.0));

        return Downloader.toBytes(zipUrl, (read, total) -> {
            double ratio = total > 0 ? (double) read / total : -1;
            String detail = ByteFormatter.format(read);
            if (total > 0) {
                detail += " / " + ByteFormatter.format(total);
            }
            progress.accept(UpdateProgress.of("Downloading update", detail, ratio));
        });
    }

    /** Deletes orphaned files if any exist, reporting count to the UI. */
    private void cleanOrphans(UpdateCheckResult diff,
            Consumer<UpdateProgress> progress) throws IOException {
        if (diff.orphaned().isEmpty())
            return;

        progress.accept(UpdateProgress.indeterminate(
                "Cleaning up",
                diff.orphaned().size() + " orphaned files"));
        updateManager.deleteOrphans(diff.orphaned());
    }

    // =====================================================================
    // Extraction
    // =====================================================================

    /**
     * Extracts only the files flagged as outdated from the zip — unchanged
     * files in the archive are skipped entirely. Reports per-file progress
     * with filename and extraction count.
     */
    private void extractOutdatedFiles(byte[] zipData, UpdateCheckResult diff,
            Consumer<UpdateProgress> progress) throws IOException {
        Set<String> outdatedPaths = diff.outdated().stream()
                .map(FileEntry::path)
                .collect(Collectors.toSet());

        int total = outdatedPaths.size();
        int extracted = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory())
                    continue;

                // Normalize Windows backslashes so the path matches the manifest
                String name = entry.getName().replace('\\', '/');
                if (!outdatedPaths.contains(name))
                    continue;

                extracted++;
                String fileName = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
                double ratio = (double) extracted / total;
                progress.accept(UpdateProgress.of(
                        "Extracting files",
                        fileName + " (" + extracted + "/" + total + ")",
                        ratio));

                Path target = appDirectory.resolve(name);
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // =====================================================================
    // Utilities
    // =====================================================================

    /**
     * Scans the GitHub release JSON for a named asset and returns its
     * {@code browser_download_url}.
     *
     * <p>
     * GitHub's release API returns assets as an array of objects, each
     * containing a {@code name} and a {@code browser_download_url} field.
     * This method walks the JSON linearly looking for a matching name,
     * then reads the download URL from the same asset object.
     *
     * @throws IOException if the asset is not found in the release
     */
    private static String findAssetUrl(String releaseJson, String assetName) throws IOException {
        int nameIdx = releaseJson.indexOf("\"name\"");
        while (nameIdx != -1) {
            int valueStart = releaseJson.indexOf('"', releaseJson.indexOf(':', nameIdx) + 1);
            int valueEnd = releaseJson.indexOf('"', valueStart + 1);
            String name = releaseJson.substring(valueStart + 1, valueEnd);

            if (name.equals(assetName)) {
                int downloadUrlKey = releaseJson.indexOf("\"browser_download_url\"", valueEnd);
                if (downloadUrlKey != -1) {
                    int urlStart = releaseJson.indexOf('"', releaseJson.indexOf(':', downloadUrlKey) + 1);
                    int urlEnd = releaseJson.indexOf('"', urlStart + 1);
                    return releaseJson.substring(urlStart + 1, urlEnd);
                }
            }

            nameIdx = releaseJson.indexOf("\"name\"", valueEnd);
        }
        throw new IOException("Asset not found in release: " + assetName);
    }

    /**
     * Writes the version tag to {@code version.txt} so future runs can skip
     * unchanged versions.
     */
    private void recordVersion(String version) throws IOException {
        Path versionFile = appDirectory.resolve("version.txt");
        Files.createDirectories(versionFile.getParent());
        Files.writeString(versionFile, version);
    }
}
