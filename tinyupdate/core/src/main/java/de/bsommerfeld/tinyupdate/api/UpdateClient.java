package de.bsommerfeld.tinyupdate.api;

import java.util.function.Consumer;

/**
 * Orchestrates the full update lifecycle: check → download → verify → apply.
 * Implementations are expected to operate against a specific distribution backend
 * (e.g. GitHub Releases).
 */
public interface UpdateClient {

    /**
     * Runs the complete update flow.
     * Returns {@code true} if an update was applied, {@code false} if already up-to-date.
     *
     * @param progressCallback receives progress events for UI display
     * @throws Exception on network or I/O failure
     */
    boolean update(Consumer<UpdateProgress> progressCallback) throws Exception;

    /**
     * Returns the currently installed version string, or {@code null} if no version is recorded.
     */
    String currentVersion();
}
