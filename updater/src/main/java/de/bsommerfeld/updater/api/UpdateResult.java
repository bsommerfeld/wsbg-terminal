package de.bsommerfeld.updater.api;

/**
 * Outcome of a single {@link UpdateClient#update} run.
 *
 * <p>Replaces the former stateful back-channel on {@code TinyUpdateClient}
 * (the {@code setExtraSteps}/{@code lastDownloadStepCount} setter/getter pair):
 * the caller now reads the download-step count off the returned record instead
 * of a mutable field, so the launcher's UI step numbering is an explicit return
 * value rather than a side effect threaded through the client.
 *
 * @param updated       {@code true} if files were downloaded and applied,
 *                      {@code false} if already up-to-date
 * @param version       the installed version after the run (the tag just
 *                      recorded on an update, or the unchanged current version)
 * @param downloadSteps how many numbered download steps the run showed (0 when
 *                      nothing was downloaded); the launcher numbers the
 *                      environment-setup steps right after these
 */
public record UpdateResult(boolean updated, String version, int downloadSteps) {
}
