package de.bsommerfeld.tinyupdate.api;

import de.bsommerfeld.tinyupdate.download.Downloader;
import de.bsommerfeld.tinyupdate.json.JsonParser;
import de.bsommerfeld.tinyupdate.model.FileEntry;
import de.bsommerfeld.tinyupdate.model.UpdateManifest;
import de.bsommerfeld.tinyupdate.update.UpdateCheckResult;
import de.bsommerfeld.tinyupdate.update.UpdateManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GitHub Releases-backed update client.
 *
 * <p>Workflow: resolve latest release → download manifest → diff against local state →
 * download zip → extract changed files → verify hashes → clean orphans → record version.
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

    @Override
    public boolean update(Consumer<UpdateProgress> progress) throws Exception {
        progress.accept(UpdateProgress.indeterminate("Checking for updates..."));

        String releaseJson = Downloader.toString(repository.latestReleaseUrl());
        String tagName = JsonParser.extractString(releaseJson, "tag_name");
        String manifestUrl = findAssetUrl(releaseJson, "update.json");

        // Quick version check before downloading the full manifest
        String local = currentVersion();
        if (tagName.equals(local)) {
            progress.accept(UpdateProgress.of("Up to date", 1.0));
            return false;
        }

        progress.accept(UpdateProgress.indeterminate("Downloading manifest..."));
        String manifestJson = Downloader.toString(manifestUrl);
        UpdateManifest manifest = JsonParser.parseManifest(manifestJson);

        progress.accept(UpdateProgress.indeterminate("Comparing files..."));
        UpdateCheckResult diff = updateManager.check(manifest);

        if (diff.isUpToDate()) {
            recordVersion(tagName);
            progress.accept(UpdateProgress.of("Up to date", 1.0));
            return false;
        }

        progress.accept(UpdateProgress.indeterminate("Downloading update (" + diff.outdated().size() + " files)..."));
        String zipUrl = findAssetUrl(releaseJson, "files.zip");
        byte[] zipData = Downloader.toBytes(zipUrl, (read, total) -> {
            double ratio = total > 0 ? (double) read / total : -1;
            progress.accept(UpdateProgress.of("Downloading...", ratio));
        });

        progress.accept(UpdateProgress.indeterminate("Extracting..."));
        extractOutdatedFiles(zipData, diff);

        progress.accept(UpdateProgress.indeterminate("Verifying integrity..."));
        updateManager.verify(manifest);

        progress.accept(UpdateProgress.indeterminate("Cleaning up..."));
        updateManager.deleteOrphans(diff.orphaned());

        recordVersion(tagName);
        progress.accept(UpdateProgress.of("Update complete", 1.0));
        return true;
    }

    @Override
    public String currentVersion() {
        Path versionFile = appDirectory.resolve("version.txt");
        if (!Files.exists(versionFile)) return null;
        try {
            return Files.readString(versionFile).strip();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Extracts only the files flagged as outdated from the zip — unchanged files
     * in the archive are skipped. This is cheaper than a full extraction for
     * incremental updates with few changed files.
     */
    private void extractOutdatedFiles(byte[] zipData, UpdateCheckResult diff) throws IOException {
        var outdatedPaths = diff.outdated().stream()
                .map(FileEntry::path)
                .collect(java.util.stream.Collectors.toSet());

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String name = entry.getName().replace('\\', '/');
                if (!outdatedPaths.contains(name)) continue;

                Path target = appDirectory.resolve(name);
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Finds the browser_download_url for a named asset in the GitHub release JSON.
     * GitHub's release API nests assets in a JSON array — we scan it linearly
     * since there are typically <10 assets.
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

    private void recordVersion(String version) throws IOException {
        Path versionFile = appDirectory.resolve("version.txt");
        Files.createDirectories(versionFile.getParent());
        Files.writeString(versionFile, version);
    }
}
