package de.bsommerfeld.updater.api;

import de.bsommerfeld.updater.download.Downloader;
import de.bsommerfeld.updater.json.JsonParser;
import de.bsommerfeld.updater.model.UpdateManifest;
import de.bsommerfeld.updater.update.UpdateCheckResult;
import de.bsommerfeld.updater.update.UpdateManager;
import de.bsommerfeld.updater.update.VersionFile;
import de.bsommerfeld.updater.update.ZipExtractor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

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
 *
 * <h3>Collaborators</h3>
 * This class orchestrates the pipeline; the mechanical concerns are delegated:
 * {@link ArchiveDownloader} (archive fetch + speed), {@link ZipExtractor}
 * (extraction), {@link VersionFile} (version.txt I/O), and {@link ReleaseAssets}
 * (asset lookup).
 */
public final class TinyUpdateClient implements UpdateClient {

    private final GitHubRepository repository;
    private final UpdateManager updateManager;
    private final VersionFile versionFile;
    private final ZipExtractor zipExtractor;
    private final ArchiveDownloader archiveDownloader;

    private final String manifestAsset;
    private final String archiveAsset;
    private final String appArchiveAsset;
    private final String depsArchiveAsset;

    /** The standard application channel: {@code update.json} + {@code files.zip} (+ split zips). */
    public TinyUpdateClient(GitHubRepository repository, Path appDirectory) {
        this(repository, appDirectory, "update.json", "files.zip", "app.zip", "deps.zip");
    }

    /**
     * A channel with custom release-asset names — the same pipeline pointed at
     * a different asset pair (e.g. the launcher's self-update channel,
     * {@code launcher-update.json} + {@code launcher-files.zip}). Channels are
     * fully independent: separate target directory, separate {@code version.txt}.
     *
     * @param appArchiveAsset  optional split-zip asset; {@code null} disables the
     *                         app/deps split and always downloads {@code archiveAsset}
     * @param depsArchiveAsset see {@code appArchiveAsset}
     */
    public TinyUpdateClient(GitHubRepository repository, Path appDirectory,
            String manifestAsset, String archiveAsset,
            String appArchiveAsset, String depsArchiveAsset) {
        this.repository = repository;
        this.updateManager = new UpdateManager(appDirectory);
        this.versionFile = new VersionFile(appDirectory);
        this.zipExtractor = new ZipExtractor(appDirectory, TinyUpdateClient::trace);
        this.archiveDownloader = new ArchiveDownloader(TinyUpdateClient::trace);
        this.manifestAsset = manifestAsset;
        this.archiveAsset = archiveAsset;
        this.appArchiveAsset = appArchiveAsset;
        this.depsArchiveAsset = depsArchiveAsset;
    }

    /**
     * Runs the full update pipeline.
     *
     * <p>
     * Returns early if the local version already matches the latest
     * release tag, or if all file hashes match despite a version
     * mismatch (e.g. re-release with same content).
     *
     * @param extraSteps additional pipeline steps beyond the downloads to fold
     *                   into the displayed step total (see {@link UpdateClient})
     * @return an {@link UpdateResult}: whether files were updated, the installed
     *         version, and how many download steps were shown
     */
    @Override
    public UpdateResult update(Consumer<UpdateProgress> progress, int extraSteps) throws Exception {
        progress.accept(UpdateProgress.indeterminate(UpdatePhase.CHECKING.token()));

        trace("Fetching release info from " + repository.latestReleaseUrl());
        String releaseJson = Downloader.toString(repository.latestReleaseUrl());
        String tagName = JsonParser.extractString(releaseJson, "tag_name");
        trace("Remote tag: " + tagName + ", local: " + currentVersion());

        // We deliberately do NOT skip on tag equality. The tag is not
        // authoritative about the on-disk state:
        //   - a re-release under the same tag (common during testing) changes
        //     the artifacts while the tag stays the same, and
        //   - a locally deleted/corrupt file (e.g. someone wiped lib/) leaves
        //     the tag intact but the install broken.
        // The manifest hash diff is the source of truth, so it both applies
        // same-tag content changes AND self-heals missing/corrupt files
        // (auto-repair). When everything already matches, the diff is empty and
        // we no-op below — so the cost of always checking is one small manifest
        // download plus hashing the local files.

        // Race guard: a release can be *published* before its CI has finished
        // uploading the artifacts (update.json / *.zip). In that window the tag
        // is newer but the assets don't exist yet. Treat that as "nothing to do
        // yet" and run the cached version, instead of erroring — the next launch
        // retries once the upload completes.
        if (!hasAsset(releaseJson, manifestAsset)) {
            trace("Release " + tagName + " has no " + manifestAsset + " yet (still building?) — "
                    + "keeping current version");
            progress.accept(UpdateProgress.of(UpdatePhase.UP_TO_DATE.token(), 1.0));
            return new UpdateResult(false, currentVersion(), 0);
        }

        UpdateCheckResult diff = resolveChanges(releaseJson, progress);
        trace("Diff: " + diff.outdated().size() + " outdated, " + diff.orphaned().size() + " orphaned");

        if (diff.isUpToDate()) {
            trace("All files present and matching — nothing to do");
            recordVersion(tagName);
            progress.accept(UpdateProgress.of(UpdatePhase.UP_TO_DATE.token(), 1.0));
            return new UpdateResult(false, currentVersion(), 0);
        }

        int downloadSteps = applyUpdate(releaseJson, diff, progress, extraSteps);

        recordVersion(tagName);
        trace("Update complete — recorded version " + tagName);
        progress.accept(UpdateProgress.of(UpdatePhase.UPDATE_COMPLETE.token(), 1.0));
        return new UpdateResult(true, currentVersion(), downloadSteps);
    }

    @Override
    public String currentVersion() {
        return versionFile.read();
    }

    // =====================================================================
    // Pipeline Phases
    // =====================================================================

    private UpdateCheckResult resolveChanges(String releaseJson,
            Consumer<UpdateProgress> progress) throws Exception {
        progress.accept(UpdateProgress.indeterminate(UpdatePhase.CHECKING.token()));
        String manifestUrl = findAssetUrl(releaseJson, manifestAsset);
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
    private int applyUpdate(String releaseJson, UpdateCheckResult diff,
            Consumer<UpdateProgress> progress, int extraSteps) throws Exception {

        String manifestUrl = findAssetUrl(releaseJson, manifestAsset);
        UpdateManifest manifest = JsonParser.parseManifest(Downloader.toString(manifestUrl));

        boolean hasSplitZips = appArchiveAsset != null && depsArchiveAsset != null
                && hasAsset(releaseJson, appArchiveAsset) && hasAsset(releaseJson, depsArchiveAsset);

        // Only downloads count as steps
        int downloadSteps = hasSplitZips ? 2 : 1;
        int totalSteps = downloadSteps + extraSteps;
        int step = 0;

        if (hasSplitZips) {
            step++;
            byte[] appZipData = archiveDownloader.download(releaseJson, appArchiveAsset,
                    UpdatePhase.DOWNLOADING_UPDATE.token(), step, totalSteps, progress);

            progress.accept(UpdateProgress.indeterminate(UpdatePhase.EXTRACTING_FILES.token()));
            zipExtractor.extractOutdated(appZipData, diff);

            UpdateCheckResult remainingDiff = updateManager.check(manifest);
            if (!remainingDiff.outdated().isEmpty()) {
                step++;
                byte[] depsZipData = archiveDownloader.download(releaseJson, depsArchiveAsset,
                        UpdatePhase.DOWNLOADING_DEPENDENCIES.token(), step, totalSteps, progress);

                progress.accept(UpdateProgress.indeterminate(UpdatePhase.EXTRACTING_DEPENDENCIES.token()));
                zipExtractor.extractOutdated(depsZipData, remainingDiff);
            }
        } else {
            step++;
            byte[] zipData = archiveDownloader.download(releaseJson, archiveAsset,
                    UpdatePhase.DOWNLOADING_UPDATE.token(), step, totalSteps, progress);

            progress.accept(UpdateProgress.indeterminate(UpdatePhase.EXTRACTING_FILES.token()));
            zipExtractor.extractOutdated(zipData, diff);
        }

        progress.accept(UpdateProgress.indeterminate(UpdatePhase.VERIFYING_INTEGRITY.token()));
        updateManager.verify(manifest);

        if (!diff.orphaned().isEmpty()) {
            updateManager.deleteOrphans(diff.orphaned());
        }

        return downloadSteps;
    }

    // =====================================================================
    // Utilities
    // =====================================================================

    /**
     * Returns the {@code browser_download_url} of a named release asset.
     * Delegates to {@link ReleaseAssets}, which scopes the scan to the
     * {@code assets} array — the release {@code body} (free markdown text)
     * can never produce a false match.
     *
     * @throws IOException if the asset is not found in the release
     */
    private static String findAssetUrl(String releaseJson, String assetName) throws IOException {
        return ReleaseAssets.requireUrl(releaseJson, assetName);
    }

    private static boolean hasAsset(String releaseJson, String assetName) {
        return ReleaseAssets.has(releaseJson, assetName);
    }

    private void recordVersion(String version) throws IOException {
        versionFile.record(version);
    }

    private static void trace(String message) {
        System.err.println("[updater] " + message);
    }
}
