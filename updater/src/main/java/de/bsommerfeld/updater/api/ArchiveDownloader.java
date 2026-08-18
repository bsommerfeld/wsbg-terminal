package de.bsommerfeld.updater.api;

import de.bsommerfeld.updater.download.Downloader;

import java.util.function.Consumer;

/**
 * Downloads a named release archive with per-phase progress (0.0→1.0) and
 * speed reporting.
 *
 * <p>
 * Isolated from the update pipeline so that progress reporting stays out of
 * the orchestrator: {@link TinyUpdateClient} decides <em>which</em> archive is
 * needed, this class turns the transfer into the events a UI can draw.
 *
 * <h3>Two speeds, two audiences</h3>
 * The UI is given the running average (total bytes / total elapsed), which
 * settles quickly and does not jitter with every CDN hiccup. The trace line
 * gets the delta since the previous line, which is the number that reveals a
 * stall. Both are derived from the aggregate byte count the downloader
 * reports, so the parallel-chunk path needs no extra bookkeeping here.
 */
final class ArchiveDownloader {

    /*
     * The trace sink is injected rather than printed directly, so this class
     * has no opinion on where diagnostics go and the whole updater keeps a
     * single place that decides it.
    */
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

        /*
         * The URL is taken from the release's own asset list instead of being
         * composed from a pattern: only the release knows which CDN link its
         * upload ended up at. requireUrl throws when the asset is absent, which
         * is correct this deep in the pipeline - by now the caller has already
         * decided the archive is needed.
        */
        String zipUrl = ReleaseAssets.requireUrl(releaseJson, assetName);
        trace.accept("Downloading " + assetName + " from " + zipUrl);

        /*
         * A 0 % event before the first byte moves. Resolving the redirect chain
         * and the TLS handshake can take a moment, and without this the UI
         * would keep showing the previous phase's label and step number for the
         * whole of it.
        */
        progress.accept(UpdateProgress.of(phaseName, step, totalSteps, 0.0));

        long startTime = System.currentTimeMillis();

        /*
         * State the callback has to carry between invocations: [0] is when the
         * last trace line was written, [1] the byte count at that moment. It is
         * an array because a lambda cannot capture a mutable local, and it is
         * unsynchronised on purpose - only the log throttle reads it, so a race
         * between chunk threads costs at most one skewed trace line, whereas a
         * lock would sit in the middle of the transfer's hot path.
        */
        long[] tracker = {startTime, 0};

        /*
         * Downloaded into memory rather than to a file: the archive is never
         * needed as a file, only as a source to extract selected entries from,
         * and keeping it out of the app directory means no half-written zip can
         * be left behind for a later run to trip over.
        */
        byte[] data = Downloader.toBytes(zipUrl, (read, total) -> {

            /*
             * A missing Content-Length arrives as -1 or 0, and dividing by it
             * would produce a nonsense bar. Reporting 0 instead keeps the phase
             * visible without claiming progress that cannot be known.
            */
            double ratio = total > 0 ? (double) read / total : 0;

            /*
             * Average speed over the whole transfer so far. The first half
             * second is suppressed (-1): with a handshake still inside the
             * measured window the figure would be wildly low, and a number that
             * starts at "12 KB/s" and then leaps is worse than none.
            */
            long elapsed = System.currentTimeMillis() - startTime;
            long speed = elapsed > 500 ? (read * 1000) / elapsed : -1;

            /*
             * SPEED_UNCHANGED rather than a zero: it tells the UI to keep
             * whatever it last displayed, so the readout does not blink empty
             * during the opening window.
            */
            progress.accept(UpdateProgress.download(phaseName, step, totalSteps, ratio,
                    speed >= 0 ? speed : UpdateProgress.SPEED_UNCHANGED));

            /*
             * The trace line is throttled to one every two seconds. The
             * callback fires dozens of times a second - eight chunk threads
             * each reporting on their own - and an unthrottled log would bury
             * every other line of the session.
            */
            long now = System.currentTimeMillis();
            if (now - tracker[0] >= 2000) {

                /*
                 * Speed since the previous line, not since the start: this is
                 * the diagnostic view, and only a delta shows a transfer that
                 * has gone quiet. A running average would keep looking healthy
                 * for minutes after the bytes stopped.
                */
                long logElapsed = now - tracker[0];
                long logSpeed = logElapsed > 0 ? ((read - tracker[1]) * 1000) / logElapsed : 0;
                trace.accept(assetName + ": " + formatBytes(read) + " / "
                        + (total > 0 ? formatBytes(total) : "?")
                        + " (" + formatBytes(logSpeed) + "/s)");

                /*
                 * The window closes here: both the timestamp and the byte mark
                 * move on, so the next line measures the stretch after this one
                 * instead of overlapping with it.
                */
                tracker[0] = now;
                tracker[1] = read;
            }
        });

        /*
         * The final size is traced because it is what the extraction and the
         * hash verification are about to work on - a truncated archive shows up
         * here as a number that does not match the release.
        */
        trace.accept("Downloaded " + assetName + ": " + data.length + " bytes");
        return data;
    }

    /**
     * Byte counts for the trace line. Private and untranslated on purpose:
     * these strings only ever reach a log, so they are not user-facing text and
     * carry no localization.
     */
    private static String formatBytes(long bytes) {

        /*
         * Fixed thresholds instead of a loop over unit prefixes: a download is
         * never smaller than a byte nor larger than a few hundred megabytes, so
         * three cases cover the entire range this class can ever print.
        */
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
