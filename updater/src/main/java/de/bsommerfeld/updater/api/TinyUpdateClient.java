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
     * Downloads the zip, extracts outdated files, verifies integrity,
     * and cleans up orphans. The zip is downloaded fully into memory
     * before extraction — either the entire zip is available or nothing
     * is written to disk, preventing partial-file states on network failures.
     *
     * <p>Pipeline steps use Discord-style status: "Downloading update 1/4".
     * The progress ratio represents overall pipeline position, not
     * per-step byte ratios — this prevents confusing jumps when
     * a step with few items (e.g. 3 app files) finishes quickly.
     */
    private void applyUpdate(String releaseJson, UpdateCheckResult diff,
            Consumer<UpdateProgress> progress) throws Exception {

        String manifestUrl = findAssetUrl(releaseJson, "update.json");
        UpdateManifest manifest = JsonParser.parseManifest(Downloader.toString(manifestUrl));

        boolean hasSplitZips = hasAsset(releaseJson, "app.zip") && hasAsset(releaseJson, "deps.zip");
        boolean hasOrphans = !diff.orphaned().isEmpty();

        // Step count: download + extract (×2 if split) + verify + cleanup (if orphans)
        int totalSteps = hasSplitZips ? 4 : 2;
        totalSteps++; // verify
        if (hasOrphans) totalSteps++;
        int step = 0;

        if (hasSplitZips) {
            step++;
            final int dlAppStep = step;
            byte[] appZipData = downloadZip(releaseJson, "app.zip", "Downloading update", dlAppStep, totalSteps, progress);
            step++;
            extractOutdatedFiles(appZipData, diff, "Extracting files", step, totalSteps, progress);

            UpdateCheckResult remainingDiff = updateManager.check(manifest);
            if (!remainingDiff.outdated().isEmpty()) {
                step++;
                final int dlDepsStep = step;
                byte[] depsZipData = downloadZip(releaseJson, "deps.zip", "Downloading dependencies", dlDepsStep, totalSteps, progress);
                step++;
                extractOutdatedFiles(depsZipData, remainingDiff, "Extracting dependencies", step, totalSteps, progress);
            }
        } else {
            step++;
            final int dlStep = step;
            byte[] zipData = downloadZip(releaseJson, "files.zip", "Downloading update", dlStep, totalSteps, progress);
            step++;
            extractOutdatedFiles(zipData, diff, "Extracting files", step, totalSteps, progress);
        }

        step++;
        progress.accept(UpdateProgress.of("Verifying integrity", step + "/" + totalSteps, (double) step / totalSteps));
        updateManager.verify(manifest);

        if (hasOrphans) {
            step++;
            progress.accept(UpdateProgress.of("Cleaning up", step + "/" + totalSteps, (double) step / totalSteps));
            updateManager.deleteOrphans(diff.orphaned());
        }
    }

    /**
     * Downloads an archive with byte-level progress mapped to the
     * overall pipeline position. For step 1/5 with 50% downloaded,
     * the ratio is (1-1 + 0.5) / 5 = 0.1.
     */
    private byte[] downloadZip(String releaseJson, String assetName,
            String phaseName, int step, int totalSteps,
            Consumer<UpdateProgress> progress) throws Exception {
        String zipUrl = findAssetUrl(releaseJson, assetName);
        trace("Downloading " + assetName + " from " + zipUrl);

        progress.accept(UpdateProgress.of(phaseName, step + "/" + totalSteps, (step - 1.0) / totalSteps));

        // [0]=lastLogTime, [1]=lastLogBytes
        long startTime = System.currentTimeMillis();
        long[] tracker = {startTime, 0};

        byte[] data = Downloader.toBytes(zipUrl, (read, total) -> {
            double subRatio = total > 0 ? (double) read / total : 0;
            double overallRatio = (step - 1.0 + subRatio) / totalSteps;

            // Speed from total bytes / total elapsed — inherently race-free
            // because `read` is backed by AtomicLong (globalTransferred)
            // and `startTime` is a constant. No locks needed.
            long elapsed = System.currentTimeMillis() - startTime;
            long speed = elapsed > 500 ? (read * 1000) / elapsed : -1;

            progress.accept(UpdateProgress.download(phaseName, step + "/" + totalSteps, overallRatio,
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
     * Extracts only the files flagged as outdated from the zip — unchanged
     * files in the archive are skipped entirely. Reports per-file progress
     * as a detail label (e.g. "launcher.jar 2/3") while keeping the
     * overall pipeline ratio from the step counter.
     */
    private void extractOutdatedFiles(byte[] zipData, UpdateCheckResult diff,
            String phaseName, int step, int totalSteps,
            Consumer<UpdateProgress> progress) throws IOException {
        Set<String> outdatedPaths = diff.outdated().stream()
                .map(FileEntry::path)
                .collect(Collectors.toSet());

        int total = outdatedPaths.size();
        int extracted = 0;
        trace("Extracting " + total + " files from " + phaseName);

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
                String detail = fileName + " " + extracted + "/" + total;

                // Interpolate within step: ratio moves from (step-1)/total to step/total
                double subRatio = (double) extracted / total;
                double overallRatio = (step - 1.0 + subRatio) / totalSteps;
                progress.accept(UpdateProgress.of(phaseName, detail, overallRatio));

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
