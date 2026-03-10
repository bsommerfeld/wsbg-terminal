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

    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

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
            validateStatus(response.statusCode(), url);

            long totalBytes = contentLength(response);
            Files.createDirectories(target.getParent());

            try (InputStream in = response.body()) {
                Path temp = target.resolveSibling(target.getFileName() + ".tmp");
                transferWithProgress(in, temp, totalBytes, listener);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (InterruptedException e) {
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

            long totalBytes = contentLength(response);
            int connections = ChunkedDownload.calculateConnections(totalBytes);

            if (connections > 1) {
                // Close probe connection — parallel path opens N new ones
                response.body().close();

                // Use the final URL after redirects so Range requests
                // hit the CDN directly, not the redirect origin.
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
            byte[] buffer = new byte[65536];
            long transferred = 0;
            int read;
            long lastUpdate = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                transferred += read;

                long now = System.currentTimeMillis();
                if (now - lastUpdate > 50 || transferred == totalBytes) {
                    listener.onProgress(transferred, totalBytes);
                    lastUpdate = now;
                }
            }
            listener.onProgress(transferred, totalBytes);
        }
    }

    private static byte[] readWithProgress(InputStream in, long totalBytes,
            DownloadProgressListener listener) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(totalBytes > 0 ? (int) totalBytes : 65536);
        byte[] chunk = new byte[65536];
        long transferred = 0;
        int read;
        long lastUpdate = 0;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
            transferred += read;

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
        return response.headers().firstValueAsLong("Content-Length").orElse(-1);
    }

    private static void validateStatus(int status, String url) throws IOException {
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " for " + url);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static void log(String msg) {
        System.out.println("[downloader] " + msg);
    }
}
