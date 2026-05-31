package de.bsommerfeld.updater.api;

import de.bsommerfeld.updater.download.Downloader;
import de.bsommerfeld.updater.json.JsonParser;
import de.bsommerfeld.updater.model.FileEntry;
import de.bsommerfeld.updater.model.UpdateManifest;
import de.bsommerfeld.updater.update.UpdateCheckResult;
import de.bsommerfeld.updater.update.UpdateManager;

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
 * Each pipeline step reports its own 0.0→1.0 progress ratio independently.
 * The step counter (step/totalSteps) tells the UI where in the overall
 * pipeline we are, while the progress bar only reflects the current phase.
 */
public final class TinyUpdateClient implements UpdateClient {

    private final GitHubRepository repository;
    private final Path appDirectory;
    private final UpdateManager updateManager;

    // Extra steps beyond downloads (e.g. AI model install) —
    // included in the total so the step counter is consistent
    // across both update and environment phases.
    private int extraSteps = 0;

    public TinyUpdateClient(GitHubRepository repository, Path appDirectory) {
        this.repository = repository;
        this.appDirectory = appDirectory;
        this.updateManager = new UpdateManager(appDirectory);
    }

    /** Adds extra steps to the pipeline total (e.g. +1 for AI model install). */
    public void setExtraSteps(int extraSteps) {
        this.extraSteps = extraSteps;
    }

    /** Returns the number of download steps the last update had. */
    public int lastDownloadStepCount() {
        return lastDownloadSteps;
    }

    private int lastDownloadSteps = 0;

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
        progress.accept(UpdateProgress.indeterminate("Checking for updates"));

        trace("Fetching release info from " + repository.latestReleaseUrl());
        String releaseJson = Downloader.toString(repository.latestReleaseUrl());
        String tagName = JsonParser.extractString(releaseJson, "tag_name");
        trace("Remote tag: " + tagName + ", local: " + currentVersion());

        if (tagName.equals(currentVersion())) {
            trace("Version match — skipping update");
            progress.accept(UpdateProgress.of("Up to date", 1.0));
            return false;
        }

        UpdateCheckResult diff = resolveChanges(releaseJson, progress);
        trace("Diff: " + diff.outdated().size() + " outdated, " + diff.orphaned().size() + " orphaned");

        if (diff.isUpToDate()) {
            trace("All files match despite version mismatch — recording version");
            recordVersion(tagName);
            progress.accept(UpdateProgress.of("Up to date", 1.0));
            return false;
        }

        applyUpdate(releaseJson, diff, progress);

        recordVersion(tagName);
        trace("Update complete — recorded version " + tagName);
        progress.accept(UpdateProgress.of("Update complete", 1.0));
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

    private UpdateCheckResult resolveChanges(String releaseJson,
            Consumer<UpdateProgress> progress) throws Exception {
        progress.accept(UpdateProgress.indeterminate("Checking for updates"));
        String manifestUrl = findAssetUrl(releaseJson, "update.json");
        String manifestJson = Downloader.toString(manifestUrl);
        UpdateManifest manifest = JsonParser.parseManifest(manifestJson);
        return updateManager.check(manifest);
    }

    /**
     * Downloads, extracts, verifies, and cleans up. Only downloads
     * count as steps (with progress bar 0→100%). Extraction is shown
     * as a status label with indeterminate dot but no step counter.
     * Verify and cleanup run silently.
     */
    private void applyUpdate(String releaseJson, UpdateCheckResult diff,
            Consumer<UpdateProgress> progress) throws Exception {

        String manifestUrl = findAssetUrl(releaseJson, "update.json");
        UpdateManifest manifest = JsonParser.parseManifest(Downloader.toString(manifestUrl));

        boolean hasSplitZips = hasAsset(releaseJson, "app.zip") && hasAsset(releaseJson, "deps.zip");

        // Only downloads count as steps
        int downloadSteps = hasSplitZips ? 2 : 1;
        int totalSteps = downloadSteps + extraSteps;
        lastDownloadSteps = downloadSteps;
        int step = 0;

        if (hasSplitZips) {
            step++;
            byte[] appZipData = downloadZip(releaseJson, "app.zip",
                    "Downloading update", step, totalSteps, progress);

            progress.accept(UpdateProgress.indeterminate("Extracting files"));
            extractOutdatedFiles(appZipData, diff);

            UpdateCheckResult remainingDiff = updateManager.check(manifest);
            if (!remainingDiff.outdated().isEmpty()) {
                step++;
                byte[] depsZipData = downloadZip(releaseJson, "deps.zip",
                        "Downloading dependencies", step, totalSteps, progress);

                progress.accept(UpdateProgress.indeterminate("Extracting dependencies"));
                extractOutdatedFiles(depsZipData, remainingDiff);
            }
        } else {
            step++;
            byte[] zipData = downloadZip(releaseJson, "files.zip",
                    "Downloading update", step, totalSteps, progress);

            progress.accept(UpdateProgress.indeterminate("Extracting files"));
            extractOutdatedFiles(zipData, diff);
        }

        progress.accept(UpdateProgress.indeterminate("Verifying integrity"));
        updateManager.verify(manifest);

        if (!diff.orphaned().isEmpty()) {
            updateManager.deleteOrphans(diff.orphaned());
        }
    }

    /**
     * Downloads an archive with per-phase progress (0.0→1.0).
     * Speed is computed from total bytes / elapsed time — inherently
     * race-free with parallel downloads since {@code read} is backed
     * by an AtomicLong.
     */
    private byte[] downloadZip(String releaseJson, String assetName,
            String phaseName, int step, int totalSteps,
            Consumer<UpdateProgress> progress) throws Exception {
        String zipUrl = findAssetUrl(releaseJson, assetName);
        trace("Downloading " + assetName + " from " + zipUrl);

        progress.accept(UpdateProgress.of(phaseName, step, totalSteps, 0.0));

        long startTime = System.currentTimeMillis();
        long[] tracker = {startTime, 0};

        byte[] data = Downloader.toBytes(zipUrl, (read, total) -> {
            double ratio = total > 0 ? (double) read / total : 0;

            long elapsed = System.currentTimeMillis() - startTime;
            long speed = elapsed > 500 ? (read * 1000) / elapsed : -1;

            progress.accept(UpdateProgress.download(phaseName, step, totalSteps, ratio,
                    speed >= 0 ? speed : UpdateProgress.SPEED_UNCHANGED));

            long now = System.currentTimeMillis();
            if (now - tracker[0] >= 2000) {
                long logElapsed = now - tracker[0];
                long logSpeed = logElapsed > 0 ? ((read - tracker[1]) * 1000) / logElapsed : 0;
                trace(assetName + ": " + formatBytes(read) + " / "
                        + (total > 0 ? formatBytes(total) : "?")
                        + " (" + formatBytes(logSpeed) + "/s)");
                tracker[0] = now;
                tracker[1] = read;
            }
        });

        trace("Downloaded " + assetName + ": " + data.length + " bytes");
        return data;
    }

    // =====================================================================
    // Extraction
    // =====================================================================

    /**
     * Extracts only the files flagged as outdated from the zip.
     * No per-file progress — extraction is brief and the UI shows
     * an indeterminate dot during this phase.
     */
    private void extractOutdatedFiles(byte[] zipData, UpdateCheckResult diff) throws IOException {
        Set<String> outdatedPaths = diff.outdated().stream()
                .map(FileEntry::path)
                .collect(Collectors.toSet());

        int total = outdatedPaths.size();
        int extracted = 0;
        trace("Extracting " + total + " files");

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory())
                    continue;

                String name = entry.getName().replace('\\', '/');
                if (!outdatedPaths.contains(name))
                    continue;

                extracted++;
                Path target = appDirectory.resolve(name);
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        trace("Extraction complete: " + extracted + "/" + total + " files");
    }

    // =====================================================================
    // Utilities
    // =====================================================================

    /**
     * Scans the GitHub release JSON for a named asset and returns its
     * {@code browser_download_url}.
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

    private static boolean hasAsset(String releaseJson, String assetName) {
        try {
            findAssetUrl(releaseJson, assetName);
            return true;
        } catch (IOException e) {
            return false;
        }
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

    private static void trace(String message) {
        System.err.println("[updater] " + message);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
