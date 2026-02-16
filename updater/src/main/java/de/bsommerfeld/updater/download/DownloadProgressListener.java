package de.bsommerfeld.updater.download;

/**
 * Callback for tracking download progress.
 */
@FunctionalInterface
public interface DownloadProgressListener {

    /**
     * Called periodically during a download.
     *
     * @param bytesRead  bytes transferred so far
     * @param totalBytes total expected size, or -1 if unknown
     */
    void onProgress(long bytesRead, long totalBytes);
}
