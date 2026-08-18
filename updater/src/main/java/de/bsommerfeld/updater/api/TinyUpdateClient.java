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
 * 1. Ask the channel for the release this install is entitled to
 * 2. Stop if that release carries no manifest yet (CI still uploading)
 * 3. Download update.json → diff its hashes against the local files
 * 4. Download files.zip (or app.zip + deps.zip) → extract only changed files
 * 5. Verify every manifest hash post-extraction
 * 6. Delete orphaned files no longer in the manifest
 * 7. Record the release tag in version.txt
 * </pre>
 *
 * <h3>Hashes decide, the tag only labels</h3>
 * The release tag never gates a run. A re-release under an unchanged tag and a
 * locally deleted file both leave the tag intact while the install is wrong, so
 * the manifest hash diff is the single source of truth about what has to
 * happen - which is also what makes the pipeline self-repairing.
 * {@code version.txt} is a record of what was applied, never an input to that
 * decision.
 *
 * <h3>Progress reporting</h3>
 * Each pipeline step reports its own 0.0→1.0 progress ratio independently.
 * The step counter (step/totalSteps) tells the UI where in the overall
 * pipeline we are, while the progress bar only reflects the current phase.
 *
 * <h3>Collaborators</h3>
 * This class orchestrates the pipeline; the mechanical concerns are delegated:
 * {@link ArchiveDownloader} (archive fetch + speed), {@link ZipExtractor}
 * (extraction), {@link VersionFile} (version.txt I/O), {@link UpdateManager}
 * (hash diff, verification, orphan removal) and {@link ReleaseAssets}
 * (asset lookup).
 */
public final class TinyUpdateClient implements UpdateClient {

    private final GitHubRepository repository;
    private final ReleaseChannel channel;
    private final UpdateManager updateManager;
    private final VersionFile versionFile;
    private final ZipExtractor zipExtractor;
    private final ArchiveDownloader archiveDownloader;

    /*
     * The asset names are instance state, not constants, because they are the
     * only thing that distinguishes one update stream from another: the same
     * pipeline serves the terminal and the launcher's own staging stream purely
     * by being pointed at a different set of release assets.
    */
    private final String manifestAsset;
    private final String archiveAsset;
    private final String appArchiveAsset;
    private final String depsArchiveAsset;

    /** The standard application assets: {@code update.json} + {@code files.zip} (+ split zips). */
    public TinyUpdateClient(GitHubRepository repository, Path appDirectory, ReleaseChannel channel) {

        /*
         * The asset names the release workflow publishes for the terminal
         * itself. They are pinned here instead of at every call site so no
         * caller can drift away from what CI actually uploads.
        */
        this(repository, appDirectory, channel, "update.json", "files.zip", "app.zip", "deps.zip");
    }

    /**
     * A stream with custom release-asset names - the same pipeline pointed at
     * a different asset pair (e.g. the launcher's self-update stream,
     * {@code launcher-update.json} + {@code launcher-files.zip}). The two are
     * fully independent: separate target directory, separate {@code version.txt}.
     *
     * @param channel          which releases to accept - {@link ReleaseChannel}
     * @param appArchiveAsset  optional split-zip asset; {@code null} disables the
     *                         app/deps split and always downloads {@code archiveAsset}
     * @param depsArchiveAsset see {@code appArchiveAsset}
     */
    public TinyUpdateClient(GitHubRepository repository, Path appDirectory, ReleaseChannel channel,
            String manifestAsset, String archiveAsset,
            String appArchiveAsset, String depsArchiveAsset) {

        /*
         * Where the releases come from, and which of them this install accepts.
         * The channel is kept as-is rather than resolved to a URL now: it is
         * asked for a release on every run, so a stale endpoint can never be
         * baked into a long-lived client.
        */
        this.repository = repository;
        this.channel = channel;

        /*
         * Every collaborator is bound to appDirectory right here, and that is
         * what makes two clients over the same repository independent of each
         * other: diffing, extraction and the version record all happen inside
         * the directory this client was handed, so the terminal stream and the
         * launcher's staging stream cannot reach into each other's tree.
        */
        this.updateManager = new UpdateManager(appDirectory);
        this.versionFile = new VersionFile(appDirectory);

        /*
         * The trace sink is injected as a method reference instead of letting
         * the collaborators print for themselves, so the whole updater has
         * exactly one place that decides where diagnostics go.
        */
        this.zipExtractor = new ZipExtractor(appDirectory, TinyUpdateClient::trace);
        this.archiveDownloader = new ArchiveDownloader(TinyUpdateClient::trace);

        /*
         * The split pair stays nullable on purpose: a stream that publishes a
         * single archive must not be forced to invent a second one, and null
         * here is what later switches the pipeline to the unsplit path.
        */
        this.manifestAsset = manifestAsset;
        this.archiveAsset = archiveAsset;
        this.appArchiveAsset = appArchiveAsset;
        this.depsArchiveAsset = depsArchiveAsset;
    }

    /**
     * Runs the full update pipeline.
     *
     * <p>
     * Returns early when the release has no manifest yet, or when every file
     * already matches the manifest - the latter regardless of what the tag
     * says, so a re-release with identical content costs a manifest download
     * and nothing else.
     *
     * @param extraSteps additional pipeline steps beyond the downloads to fold
     *                   into the displayed step total (see {@link UpdateClient})
     * @return an {@link UpdateResult}: whether files were updated, the installed
     *         version, and how many download steps were shown
     */
    @Override
    public UpdateResult update(Consumer<UpdateProgress> progress, int extraSteps) throws Exception {

        /*
         * Something has to be on screen before the first network call. The
         * release fetch has no measurable ratio, so the UI is handed an
         * indeterminate CHECKING phase rather than an empty window that looks
         * like a hang.
        */
        progress.accept(UpdateProgress.indeterminate(UpdatePhase.CHECKING.token()));

        /*
         * One release fetch for the entire run: the resulting JSON is threaded
         * through every later phase, so a release published while the update is
         * in flight cannot swap the manifest out from under an archive that was
         * already downloaded against the old one.
        */
        String releaseJson = fetchRelease();

        /*
         * The tag is read once, up front, but only for the trace line and the
         * version record at the very end - nothing in between branches on it.
        */
        String tagName = JsonParser.extractString(releaseJson, "tag_name");
        trace("Remote tag: " + tagName + ", local: " + currentVersion());

        /*
         * We deliberately do NOT skip on tag equality. The tag is not
         * authoritative about the on-disk state:
         *   - a re-release under the same tag (common during testing) changes
         *     the artifacts while the tag stays the same, and
         *   - a locally deleted/corrupt file (e.g. someone wiped lib/) leaves
         *     the tag intact but the install broken.
         * The manifest hash diff is the source of truth, so it both applies
         * same-tag content changes AND self-heals missing/corrupt files
         * (auto-repair). When everything already matches, the diff is empty and
         * we no-op below - so the cost of always checking is one small manifest
         * download plus hashing the local files.
        */

        /*
         * Race guard: a release can be *published* before its CI has finished
         * uploading the artifacts (update.json / *.zip). In that window the tag
         * is newer but the assets don't exist yet. Treat that as "nothing to do
         * yet" and run the cached version, instead of erroring - the next launch
         * retries once the upload completes. The manifest is the asset probed
         * for because every path below starts by reading it.
        */
        if (!hasAsset(releaseJson, manifestAsset)) {
            trace("Release " + tagName + " has no " + manifestAsset + " yet (still building?) - "
                    + "keeping current version");

            /*
             * Reported as a finished UP_TO_DATE phase rather than as an error:
             * from the user's side nothing is pending, and the launcher goes on
             * to start what is installed.
            */
            progress.accept(UpdateProgress.of(UpdatePhase.UP_TO_DATE.token(), 1.0));
            return new UpdateResult(false, currentVersion(), 0);
        }

        /*
         * The actual decision: what is missing, changed, or left over. Every
         * branch below reads off this diff and none of them looks at the tag
         * again.
        */
        UpdateCheckResult diff = resolveChanges(releaseJson, progress);
        trace("Diff: " + diff.outdated().size() + " outdated, " + diff.orphaned().size() + " orphaned");

        if (diff.isUpToDate()) {
            trace("All files present and matching - nothing to do");

            /*
             * The version is recorded even though no file was touched: the
             * content on disk provably belongs to this release, so the label
             * gets corrected to match it. That covers the re-tagged release and
             * the install whose version.txt was lost, and it keeps the launcher
             * from displaying a stale or missing version forever.
            */
            recordVersion(tagName);
            progress.accept(UpdateProgress.of(UpdatePhase.UP_TO_DATE.token(), 1.0));
            return new UpdateResult(false, currentVersion(), 0);
        }

        /*
         * From here on files change on disk. The number of download steps the
         * user was shown comes back out, because the launcher continues its own
         * step numbering right after them.
        */
        int downloadSteps = applyUpdate(releaseJson, diff, progress, extraSteps);

        /*
         * Recorded only after download, extraction and verification have all
         * succeeded. Any failure above leaves version.txt on the old value, so
         * a half-applied install is never labelled as the new version and the
         * next start simply re-diffs and finishes the job.
        */
        recordVersion(tagName);
        trace("Update complete - recorded version " + tagName);
        progress.accept(UpdateProgress.of(UpdatePhase.UPDATE_COMPLETE.token(), 1.0));
        return new UpdateResult(true, currentVersion(), downloadSteps);
    }

    /**
     * The version recorded on disk, re-read on every call rather than cached -
     * callers ask for it right after {@link #update} has written it, and a
     * cached field would hand them the value from before the run.
     */
    @Override
    public String currentVersion() {
        return versionFile.read();
    }

    /**
     * Read-only probe: would {@link #update} actually change files on disk?
     * Runs the same source of truth as the real pipeline - the manifest hash
     * diff, not the release tag - but stops before the first archive download,
     * so it costs one release-JSON fetch, one manifest fetch and the local
     * hashing.
     *
     * <p>
     * Exists for the caller that must decide something <em>before</em> touching
     * the install: the launcher closes a running terminal only when there is
     * genuinely something to apply (see {@code LauncherMain}). A release whose
     * assets are still uploading counts as "nothing pending" - same race guard
     * as {@link #update}.
     *
     * @throws Exception on network or I/O failure - callers decide whether a
     *                   failed probe is worth acting on
     */
    public boolean isUpdatePending() throws Exception {

        /*
         * A fetch of its own: the probe runs long before the update itself
         * (the launcher asks first, updates later), so there is no release
         * object it could sensibly share with that later run.
        */
        String releaseJson = fetchRelease();

        /*
         * Same race guard as the pipeline, and for the same reason: a release
         * whose artifacts are still uploading must not make the launcher tear
         * down a running terminal for an update that cannot be applied yet.
        */
        if (!hasAsset(releaseJson, manifestAsset)) {
            trace("Latest release has no " + manifestAsset + " yet - nothing pending");
            return false;
        }

        /*
         * The progress consumer is a no-op because the probe has no UI attached
         * - it runs before the window exists. Reusing resolveChanges rather than
         * re-implementing a lighter check is the point: the probe and the update
         * can never disagree about whether something is pending.
        */
        return !resolveChanges(releaseJson, p -> { }).isUpToDate();
    }

    // =====================================================================
    // Pipeline Phases
    // =====================================================================

    /**
     * Fetches the manifest for the given release and diffs it against the
     * local files. The one place that turns "a release exists" into "this is
     * what would change".
     */
    private UpdateCheckResult resolveChanges(String releaseJson,
            Consumer<UpdateProgress> progress) throws Exception {

        /*
         * The phase is asserted here rather than assumed from the caller, so
         * that every entry point into the diff shows the same label - the
         * manifest fetch plus local hashing is the part that takes a
         * perceptible moment.
        */
        progress.accept(UpdateProgress.indeterminate(UpdatePhase.CHECKING.token()));

        /*
         * Resolved out of the release's asset list rather than composed from a
         * URL pattern: only the release itself knows the CDN link its manifest
         * was uploaded to.
        */
        String manifestUrl = findAssetUrl(releaseJson, manifestAsset);

        /*
         * Fetched as a string straight into memory - the manifest is a few
         * kilobytes of JSON and must never be written next to the install,
         * where a leftover copy could be mistaken for a managed file.
        */
        String manifestJson = Downloader.toString(manifestUrl);
        UpdateManifest manifest = JsonParser.parseManifest(manifestJson);

        /*
         * The hashing happens in UpdateManager, not here: this class decides
         * what to do with a diff, never how one is computed.
        */
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

        /*
         * The manifest is fetched a second time instead of being threaded out
         * of resolveChanges: the diff carries only the entries that changed,
         * while the verification below has to see every file the release
         * declares. A few kilobytes buys a check phase whose return value stays
         * the diff and nothing else.
        */
        String manifestUrl = findAssetUrl(releaseJson, manifestAsset);
        UpdateManifest manifest = JsonParser.parseManifest(Downloader.toString(manifestUrl));

        /*
         * Both halves have to be present for the split path. A release carrying
         * only one of them is treated as unsplit, because extracting app.zip
         * alone would leave the diff half-applied and the verification would
         * then fail on files whose archive was never downloaded.
        */
        boolean hasSplitZips = appArchiveAsset != null && depsArchiveAsset != null
                && hasAsset(releaseJson, appArchiveAsset) && hasAsset(releaseJson, depsArchiveAsset);

        /*
         * Only downloads are numbered: they are the long, measurable part,
         * while extraction and verification are too short to earn a step of
         * their own. extraSteps folds in the phases the launcher runs after
         * this one, so a single counter runs across update and setup instead of
         * restarting at 1 halfway through.
         *
         * The total is fixed before it is known whether the dependency half is
         * needed - a skipped deps download therefore leaves a gap in the
         * numbering, which is the cheaper of the two evils against renumbering
         * the steps under the user's eyes mid-run.
        */
        int downloadSteps = hasSplitZips ? 2 : 1;
        int totalSteps = downloadSteps + extraSteps;
        int step = 0;

        if (hasSplitZips) {

            /*
             * The application half first. It is the small one and it changes on
             * practically every release, so it is also the half that decides
             * whether the big one is needed at all.
            */
            step++;
            byte[] appZipData = archiveDownloader.download(releaseJson, appArchiveAsset,
                    UpdatePhase.DOWNLOADING_UPDATE.token(), step, totalSteps, progress);

            /*
             * Extraction is filtered by the diff: the archive holds the
             * complete half, but only the entries that actually changed are
             * written, so unchanged files keep their timestamps and a large
             * archive costs no more disk writes than the update needs.
            */
            progress.accept(UpdateProgress.indeterminate(UpdatePhase.EXTRACTING_FILES.token()));
            zipExtractor.extractOutdated(appZipData, diff);

            /*
             * Re-diff against the same manifest now that the app half is on
             * disk. What remains outdated is exactly what only deps.zip can
             * supply - and for a release that changed application code alone,
             * that list is empty and the far larger dependency archive is never
             * downloaded. It doubles as the extraction filter below.
            */
            UpdateCheckResult remainingDiff = updateManager.check(manifest);
            if (!remainingDiff.outdated().isEmpty()) {
                step++;
                byte[] depsZipData = archiveDownloader.download(releaseJson, depsArchiveAsset,
                        UpdatePhase.DOWNLOADING_DEPENDENCIES.token(), step, totalSteps, progress);

                /*
                 * Its own phase label: dependency archives are the long
                 * download, and a user staring at "Extracting files" for a
                 * minute would read it as a hang.
                */
                progress.accept(UpdateProgress.indeterminate(UpdatePhase.EXTRACTING_DEPENDENCIES.token()));
                zipExtractor.extractOutdated(depsZipData, remainingDiff);
            }
        } else {

            /*
             * The unsplit path: one archive holding everything, filtered by the
             * same diff. It exists for streams that publish a single zip - the
             * launcher's own - and as the fallback whenever a release lacks one
             * of the split assets.
            */
            step++;
            byte[] zipData = archiveDownloader.download(releaseJson, archiveAsset,
                    UpdatePhase.DOWNLOADING_UPDATE.token(), step, totalSteps, progress);

            progress.accept(UpdateProgress.indeterminate(UpdatePhase.EXTRACTING_FILES.token()));
            zipExtractor.extractOutdated(zipData, diff);
        }

        /*
         * Verification runs over the whole manifest, not just what was
         * extracted: it is the last chance to catch a truncated archive entry
         * or a file the extraction never saw. It throws, and that is the point
         * - a failed run leaves version.txt untouched and the next start
         * repairs the install instead of the launcher booting a broken one.
        */
        progress.accept(UpdateProgress.indeterminate(UpdatePhase.VERIFYING_INTEGRITY.token()));
        updateManager.verify(manifest);

        /*
         * Deletion comes last, after verification passed. Removing files is the
         * only destructive act in the pipeline, so it happens at the point
         * where the new install is already known to be complete - an update
         * that fails earlier leaves the old one intact. The guard keeps the
         * common case, where nothing became orphaned, from touching the
         * filesystem at all.
        */
        if (!diff.orphaned().isEmpty()) {
            updateManager.deleteOrphans(diff.orphaned());
        }

        /*
         * Only the announced download count goes back to the caller; the
         * extraction and verification phases were shown without a number and
         * must not shift the launcher's own step numbering.
        */
        return downloadSteps;
    }

    // =====================================================================
    // Utilities
    // =====================================================================

    /**
     * The release this install is entitled to, as JSON - the one place the
     * channel is consulted. Everything downstream reads the returned object
     * without knowing where it came from.
     */
    private String fetchRelease() throws IOException {
        trace("Fetching release info (" + channel + ")");

        /*
         * The channel, not this class, decides what "current" means - stable
         * reads GitHub's latest endpoint, experimental the newest published
         * entry of the release list. Both hand back the same JSON shape, which
         * is why nothing below this line has to know which one ran.
        */
        return channel.fetchRelease(repository);
    }

    /**
     * Returns the {@code browser_download_url} of a named release asset.
     * Delegates to {@link ReleaseAssets}, which scopes the scan to the
     * {@code assets} array - the release {@code body} (free markdown text)
     * can never produce a false match.
     *
     * @throws IOException if the asset is not found in the release
     */
    private static String findAssetUrl(String releaseJson, String assetName) throws IOException {
        return ReleaseAssets.requireUrl(releaseJson, assetName);
    }

    /**
     * Whether the release carries an asset of that name. The non-throwing
     * counterpart to {@link #findAssetUrl}, which is what the race guards need:
     * a missing asset is a normal state there ("CI is still uploading"), not a
     * failure to report.
     */
    private static boolean hasAsset(String releaseJson, String assetName) {
        return ReleaseAssets.has(releaseJson, assetName);
    }

    /**
     * Stamps the install with the tag it now matches. Routed through
     * {@link VersionFile} so this class never learns where the marker lives -
     * which is what lets two clients keep separate records in separate
     * directories.
     */
    private void recordVersion(String version) throws IOException {
        versionFile.record(version);
    }

    /**
     * Diagnostics for the update run.
     *
     * <p>
     * Deliberately {@code System.err} and nothing else: this module has no
     * dependencies at all, and it runs inside the launcher before the
     * application's classpath even exists, so a logging framework is not
     * available to it. The launcher prints its own session lines to the same
     * stream, which keeps a console run in one chronological order.
     */
    private static void trace(String message) {
        System.err.println("[updater] " + message);
    }
}
