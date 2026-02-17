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

/**
 * HTTP download utility built on {@link HttpClient}.
 *
 * <p>
 * Supports three modes: streaming to file (with atomic rename),
 * in-memory byte download, and convenience string download. All modes
 * report progress via {@link DownloadProgressListener} when applicable.
 *
 * <h3>Redirect handling</h3>
 * The shared {@link HttpClient} follows redirects automatically. This is
 * required for GitHub release asset URLs, which redirect from the API
 * domain to the CDN.
 */
public final class Downloader {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Downloader() {
    }

    /**
     * Downloads a URL to the given target file.
     *
     * <p>
     * The download streams into a {@code .tmp} sibling first, then
     * atomically renames it to the target path. This prevents partially
     * downloaded files from being picked up by hash checks or the
     * application classloader.
     */
    public static void toFile(String url, Path target, DownloadProgressListener listener) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());

            validateStatus(response.statusCode(), url);

            long totalBytes = response.headers()
                    .firstValueAsLong("Content-Length")
                    .orElse(-1);

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
     * Downloads a URL entirely into memory and returns the raw bytes.
     *
     * <p>
     * Pre-allocates the buffer to Content-Length when known to avoid
     * repeated array resizing during large downloads.
     */
    public static byte[] toBytes(String url, DownloadProgressListener listener) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());

            validateStatus(response.statusCode(), url);

            long totalBytes = response.headers()
                    .firstValueAsLong("Content-Length")
                    .orElse(-1);

            try (InputStream in = response.body()) {
                return readWithProgress(in, totalBytes, listener);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + url, e);
        }
    }

    /**
     * Downloads a URL as a UTF-8 string. Progress is not reported since
     * this is used for small payloads (manifest JSON, release metadata).
     */
    public static String toString(String url) throws IOException {
        return new String(toBytes(url, (_, _) -> {
        }));
    }

    /**
     * Streams bytes from the input to the target file while reporting
     * progress. Uses an 8 KB buffer — large enough for throughput,
     * small enough for responsive progress updates.
     */
    private static void transferWithProgress(InputStream in, Path target, long totalBytes,
            DownloadProgressListener listener) throws IOException {
        try (var out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long transferred = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                transferred += read;
                listener.onProgress(transferred, totalBytes);
            }
        }
    }

    /**
     * Reads the entire input stream into a byte array while reporting
     * progress. Pre-allocates to Content-Length when available to avoid
     * repeated internal array copies in the output stream.
     */
    private static byte[] readWithProgress(InputStream in, long totalBytes,
            DownloadProgressListener listener) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(totalBytes > 0 ? (int) totalBytes : 8192);
        byte[] chunk = new byte[8192];
        long transferred = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
            transferred += read;
            listener.onProgress(transferred, totalBytes);
        }
        return buffer.toByteArray();
    }

    /** Validates that the HTTP status code is in the 2xx success range. */
    private static void validateStatus(int status, String url) throws IOException {
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " for " + url);
        }
    }
}
