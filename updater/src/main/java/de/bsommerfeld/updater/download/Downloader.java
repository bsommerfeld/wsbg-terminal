package de.bsommerfeld.updater.download;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * HTTP download utility built on {@link HttpClient}.
 *
 * <p>
 * Supports in-memory byte downloads, streaming to file, and string
 * downloads. Large files are automatically accelerated via
 * {@link ChunkedDownload} (parallel Range-Requests).
 *
 * <h3>Redirect handling</h3>
 * The shared {@link HttpClient} follows redirects automatically. This is
 * required for GitHub release asset URLs, which redirect from the API
 * domain to the CDN.
 */
public final class Downloader {

    /*
     * One shared client for the whole updater, so its connection pool is reused
     * across the manifest fetch and the payload download instead of paying a
     * fresh TLS handshake per call. The connect timeout bounds only reaching the
     * host - a slow transfer is not cut off by it.
    */
    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /*
     * The GitHub API rejects requests without a User-Agent outright, so this
     * header is mandatory rather than cosmetic.
    */
    static final String USER_AGENT = "WSBG-Terminal-Updater/1.0";

    private Downloader() {
    }

    /**
     * Downloads a URL to the given target file with atomic rename.
     *
     * <p>
     * Streams into a {@code .tmp} sibling first, then atomically
     * renames. This prevents partially downloaded files from being
     * picked up by hash checks or the application classloader.
     */
    public static void toFile(String url, Path target, DownloadProgressListener listener) throws IOException {
        try {
            HttpRequest request = newRequest(url).build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());

            /*
             * HttpClient treats 404 and 500 as perfectly good responses, so the
             * status has to be checked by hand. Without this the error page body
             * would be streamed to disk under the target's name.
            */
            validateStatus(response.statusCode(), url);

            long totalBytes = contentLength(response);

            /*
             * The target directory does not exist on a first install, and the
             * stream below would fail on it rather than create it.
            */
            Files.createDirectories(target.getParent());

            try (InputStream in = response.body()) {

                /*
                 * A sibling, not a system temp file: the rename below is only
                 * atomic within one filesystem, and a temp directory frequently
                 * sits on a different one.
                */
                Path temp = target.resolveSibling(target.getFileName() + ".tmp");
                transferWithProgress(in, temp, totalBytes, listener);

                /*
                 * The file appears at its final name complete or not at all, so
                 * a crash mid-download cannot leave a truncated file that later
                 * passes for a real one.
                */
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (InterruptedException e) {

            /*
             * send() clears the interrupt flag when it throws. Restoring it keeps
             * the shutdown signal alive for whoever called in - swallowing it
             * would leave the caller looping on a thread that was asked to stop.
            */
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + url, e);
        }
    }

    /**
     * Downloads a URL entirely into memory.
     *
     * <p>
     * Performs an initial GET to discover Content-Length. If the file
     * is large enough for parallel acceleration, the initial connection
     * is closed and re-downloaded via {@link ChunkedDownload}. The
     * ~200ms wasted on the probe is negligible against the minutes
     * saved by 8× throughput.
     */
    public static byte[] toBytes(String url, DownloadProgressListener listener) throws IOException {
        try {
            HttpRequest request = newRequest(url).build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            validateStatus(response.statusCode(), url);

            /*
             * A plain GET is used as the size probe rather than HEAD, because
             * some CDNs answer HEAD without a Content-Length - and the body of
             * this same response is what the single-connection path streams, so
             * for small files the probe costs nothing at all.
            */
            long totalBytes = contentLength(response);
            int connections = ChunkedDownload.calculateConnections(totalBytes);

            if (connections > 1) {

                /*
                 * Close probe connection - parallel path opens N new ones, and
                 * an abandoned unread body would hold a pooled connection open
                 * for the entire parallel transfer.
                */
                response.body().close();

                /*
                 * Use the final URL after redirects so Range requests
                 * hit the CDN directly, not the redirect origin. Re-running the
                 * redirect chain N times would cost N extra round trips, and the
                 * origin may not honour Range at all.
                */
                String finalUrl = response.uri().toString();
                log("Parallel download: " + connections + " connections for "
                        + formatBytes(totalBytes) + " → " + response.uri().getHost());

                return ChunkedDownload.execute(finalUrl, totalBytes, connections, listener);
            }

            if (totalBytes > 0) log("Single connection download: " + formatBytes(totalBytes));
            try (InputStream in = response.body()) {
                return readWithProgress(in, totalBytes, listener);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + url, e);
        }
    }

    /**
     * Downloads a URL as a UTF-8 string. No progress reporting — used
     * for small payloads (manifest JSON, release metadata).
     */
    public static String toString(String url) throws IOException {
        return new String(toBytes(url, (_, _) -> {
        }));
    }

    // =====================================================================
    // Streaming helpers
    // =====================================================================

    private static void transferWithProgress(InputStream in, Path target, long totalBytes,
            DownloadProgressListener listener) throws IOException {
        try (var out = Files.newOutputStream(target)) {

            /*
             * 64 KiB rather than the 8 KiB used for hashing: this loop is bounded
             * by network round trips, not by CPU, so fewer and larger reads mean
             * fewer syscalls per megabyte.
            */
            byte[] buffer = new byte[65536];
            long transferred = 0;
            int read;
            long lastUpdate = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                transferred += read;

                /*
                 * The listener drives the progress bar, and this loop runs far
                 * more often per second than a display can show. Throttling to
                 * 20 updates per second keeps the UI thread from being flooded;
                 * the totalBytes comparison forces a final update through so the
                 * bar reaches the end even if the last read lands inside the window.
                */
                long now = System.currentTimeMillis();
                if (now - lastUpdate > 50 || transferred == totalBytes) {
                    listener.onProgress(transferred, totalBytes);
                    lastUpdate = now;
                }
            }

            /*
             * Repeated once outside the loop for the unknown-length case: with
             * totalBytes at -1 the comparison above can never match, so without
             * this the bar would stop wherever the last throttled tick left it.
            */
            listener.onProgress(transferred, totalBytes);
        }
    }

    private static byte[] readWithProgress(InputStream in, long totalBytes,
            DownloadProgressListener listener) throws IOException {

        /*
         * Pre-sized to the known length so the buffer never has to grow: each
         * growth reallocates and copies everything read so far, which for a
         * payload of tens of megabytes is a measurable cost. An unknown length
         * (-1) falls back to one chunk's worth.
        */
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(totalBytes > 0 ? (int) totalBytes : 65536);
        byte[] chunk = new byte[65536];
        long transferred = 0;
        int read;
        long lastUpdate = 0;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
            transferred += read;

            /*
             * Same 50ms throttle as the file path above, for the same reason.
            */
            long now = System.currentTimeMillis();
            if (now - lastUpdate > 50 || transferred == totalBytes) {
                listener.onProgress(transferred, totalBytes);
                lastUpdate = now;
            }
        }
        listener.onProgress(transferred, totalBytes);
        return buffer.toByteArray();
    }

    // =====================================================================
    // Shared utilities
    // =====================================================================

    static HttpRequest.Builder newRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET();
    }

    private static long contentLength(HttpResponse<?> response) {

        /*
         * -1 is the agreed "unknown" marker across this class: it disables the
         * parallel path (which needs an exact size to cut ranges) and puts the
         * progress bar into its indeterminate mode.
        */
        return response.headers().firstValueAsLong("Content-Length").orElse(-1);
    }

    private static void validateStatus(int status, String url) throws IOException {
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " for " + url);
        }
    }

    private static String formatBytes(long bytes) {

        /*
         * Log-only formatting, deliberately not localized and not shared with
         * the UI - these strings go to stderr for developers, never on screen.
        */
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static void log(String msg) {
        System.out.println("[downloader] " + msg);
    }
}
