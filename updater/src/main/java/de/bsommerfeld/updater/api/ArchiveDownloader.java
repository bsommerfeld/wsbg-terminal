package de.bsommerfeld.updater.api;

import de.bsommerfeld.updater.download.Downloader;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Downloads a named release archive with per-phase progress (0.0→1.0) and
 * speed reporting.
 *
 * <p>
 * Speed is computed from total bytes / elapsed time — inherently race-free
 * with parallel downloads since {@code read} is backed by an AtomicLong.
 * Isolated from the update pipeline so progress-reporting stays out of the
 * orchestrator.
 */
final class ArchiveDownloader {

    private final Consumer<String> trace;

    ArchiveDownloader(Consumer<String> trace) {
        this.trace = trace;
    }

    /**
     * Resolves {@code assetName} in the release JSON and downloads it,
     * emitting {@link UpdateProgress} events under {@code phaseName} for the
     * given step position.
     */
    byte[] download(String releaseJson, String assetName, String phaseName,
            int step, int totalSteps, Consumer<UpdateProgress> progress) throws Exception {
        String zipUrl = ReleaseAssets.requireUrl(releaseJson, assetName);
        trace.accept("Downloading " + assetName + " from " + zipUrl);

        progress.accept(UpdateProgress.of(phaseName, step, totalSteps, 0.0));

        long startTime = System.currentTimeMillis();
        long[] tracker = {startTime, 0};

        byte[] data = Downloader.toBytes(zipUrl, (read, total) -> {
            double ratio = total > 0 ? (double) read / total : 0;

            long elapsed = System.currentTimeMillis() - startTime;
            long speed = elapsed > 500 ? (read * 1000) / elapsed : -1;

            progress.accept(UpdateProgress.download(phaseName, step, totalSteps, ratio,
                    speed >= 0 ? speed : UpdateProgress.SPEED_UNCHANGED));

            long now = System.currentTimeMillis();
            if (now - tracker[0] >= 2000) {
                long logElapsed = now - tracker[0];
                long logSpeed = logElapsed > 0 ? ((read - tracker[1]) * 1000) / logElapsed : 0;
                trace.accept(assetName + ": " + formatBytes(read) + " / "
                        + (total > 0 ? formatBytes(total) : "?")
                        + " (" + formatBytes(logSpeed) + "/s)");
                tracker[0] = now;
                tracker[1] = read;
            }
        });

        trace.accept("Downloaded " + assetName + ": " + data.length + " bytes");
        return data;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
