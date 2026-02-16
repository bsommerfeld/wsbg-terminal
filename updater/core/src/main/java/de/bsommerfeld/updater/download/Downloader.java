package de.bsommerfeld.updater.download;

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
 * Supports both streaming to file and in-memory byte downloads with progress reporting.
 */
public final class Downloader {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Downloader() {}

    /**
     * Downloads a URL to the given target file.
     * Parent directories are created automatically.
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
                // Atomic move prevents partial files from being picked up by hash checks
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + url, e);
        }
    }

    /**
     * Downloads a URL entirely into memory.
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
     * Downloads a URL as a UTF-8 string.
     */
    public static String toString(String url) throws IOException {
        return new String(toBytes(url, (_, _) -> {}));
    }

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

    private static byte[] readWithProgress(InputStream in, long totalBytes,
                                           DownloadProgressListener listener) throws IOException {
        // Pre-allocate if Content-Length is known; otherwise grow dynamically
        var buffer = new java.io.ByteArrayOutputStream(totalBytes > 0 ? (int) totalBytes : 8192);
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

    private static void validateStatus(int status, String url) throws IOException {
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " for " + url);
        }
    }
}
