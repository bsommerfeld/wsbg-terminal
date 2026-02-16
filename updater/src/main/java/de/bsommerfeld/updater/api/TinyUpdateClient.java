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
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
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

        List<FileEntry> outdated = diff.outdated();
        String zipUrl = findAssetUrl(releaseJson, "files.zip");

        progress.accept(UpdateProgress.of(
                "Downloading update",
                outdated.size() + " files to update",
                0.0
        ));

        byte[] zipData = Downloader.toBytes(zipUrl, (read, total) -> {
            double ratio = total > 0 ? (double) read / total : -1;
            String detail = ByteFormatter.format(read);
            if (total > 0) {
                detail += " / " + ByteFormatter.format(total);
            }
            progress.accept(UpdateProgress.of("Downloading update", detail, ratio));
        });

        progress.accept(UpdateProgress.indeterminate("Extracting files..."));
        extractOutdatedFiles(zipData, diff, progress);

        progress.accept(UpdateProgress.indeterminate("Verifying integrity..."));
        updateManager.verify(manifest);

        if (!diff.orphaned().isEmpty()) {
            progress.accept(UpdateProgress.indeterminate(
                    "Cleaning up",
                    diff.orphaned().size() + " orphaned files"
            ));
            updateManager.deleteOrphans(diff.orphaned());
        }

        recordVersion(tagName);
        progress.accept(UpdateProgress.of("Update complete", tagName, 1.0));
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
     * in the archive are skipped. Reports per-file progress.
     */
    private void extractOutdatedFiles(byte[] zipData, UpdateCheckResult diff,
                                      Consumer<UpdateProgress> progress) throws IOException {
        Set<String> outdatedPaths = diff.outdated().stream()
                .map(FileEntry::path)
                .collect(Collectors.toSet());

        int total = outdatedPaths.size();
        int[] extracted = {0};

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String name = entry.getName().replace('\\', '/');
                if (!outdatedPaths.contains(name)) continue;

                extracted[0]++;
                String fileName = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
                double ratio = (double) extracted[0] / total;
                progress.accept(UpdateProgress.of(
                        "Extracting files",
                        fileName + " (" + extracted[0] + "/" + total + ")",
                        ratio
                ));

                Path target = appDirectory.resolve(name);
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
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
