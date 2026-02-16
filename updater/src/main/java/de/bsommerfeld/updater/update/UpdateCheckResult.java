package de.bsommerfeld.updater.update;

import de.bsommerfeld.updater.model.FileEntry;

import java.util.List;

/**
 * Result of comparing local state against a remote manifest.
 *
 * @param outdated list of files that differ from (or are missing in) the local state
 * @param orphaned list of local file paths not present in the remote manifest
 */
public record UpdateCheckResult(List<FileEntry> outdated, List<String> orphaned) {

    public boolean isUpToDate() {
        return outdated.isEmpty() && orphaned.isEmpty();
    }

    public int totalChanges() {
        return outdated.size() + orphaned.size();
    }
}
