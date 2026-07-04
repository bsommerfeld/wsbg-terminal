package de.bsommerfeld.updater.api;

import java.util.function.Consumer;

/**
 * Orchestrates the full update lifecycle: check → download → verify → apply.
 * Implementations are expected to operate against a specific distribution backend
 * (e.g. GitHub Releases).
 */
public interface UpdateClient {

    /**
     * Runs the complete update flow and reports the outcome.
     *
     * @param progressCallback receives progress events for UI display
     * @param extraSteps       additional pipeline steps beyond the downloads
     *                         (e.g. environment-setup steps) to fold into the
     *                         step counter's total, so the "(n/total)" label is
     *                         consistent across the update and setup phases
     * @return an {@link UpdateResult} carrying whether files were applied, the
     *         installed version, and the number of download steps shown
     * @throws Exception on network or I/O failure
     */
    UpdateResult update(Consumer<UpdateProgress> progressCallback, int extraSteps) throws Exception;

    /**
     * Returns the currently installed version string, or {@code null} if no version is recorded.
     */
    String currentVersion();
}
