package de.bsommerfeld.updater.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Splits a large download into parallel Range-Request chunks to
 * bypass per-connection CDN throttling.
 *
 * <h3>Why this exists</h3>
 * CDNs like GitHub's throttle individual HTTP connections to ~100 KB/s,
 * but each new connection gets its own bandwidth allowance. By opening
 * N connections with Range headers, effective throughput scales linearly
 * (8 connections × 100 KB/s = 800 KB/s).
 *
 * <h3>HTTP/1.1 requirement</h3>
 * A dedicated HTTP/1.1 client is used instead of the shared HTTP/2
 * client. HTTP/2 multiplexes all streams over a single TCP connection,
 * which the CDN treats as one connection and throttles accordingly.
 * HTTP/1.1 forces a separate TCP socket per chunk — each gets its
 * own bandwidth allowance.
 *
 * <h3>Connection scaling</h3>
 * Connection count scales with file size: 1 per MB, capped at
 * {@value #MAX_CONNECTIONS}. Files below {@value #MIN_PARALLEL_SIZE}
 * bytes should use {@link Downloader}'s single-connection path instead
 * — TLS handshake overhead (N × ~150ms) would negate any speed gain.
 */
final class ChunkedDownload {

    // 8 is the industry standard (IDM, aria2) and well within
    // GitHub CDN's per-IP connection limits (~16).
    static final int MAX_CONNECTIONS = 8;

    // Below 2 MB, the overhead of N TLS handshakes + thread setup
    // exceeds the gain from parallel transfer.
    static final long MIN_PARALLEL_SIZE = 2_000_000;

    private static final long BYTES_PER_CONNECTION = 1_000_000;

    // HTTP/1.1 forces separate TCP sockets per request.
    // HTTP/2 would multiplex all chunks over one socket,
    // defeating the per-connection throttle bypass.
    private static final HttpClient HTTP11 = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private ChunkedDownload() {
    }

    /**
     * Calculates the optimal number of parallel connections for
     * the given file size. Returns 1 if the file is too small
     * for parallel download to help.
     */
    static int calculateConnections(long totalBytes) {
        if (totalBytes <= 0 || totalBytes < MIN_PARALLEL_SIZE) return 1;
        return Math.min(MAX_CONNECTIONS, Math.max(1, (int) (totalBytes / BYTES_PER_CONNECTION)));
    }

    /**
     * Downloads the file at {@code url} using {@code connections}
     * parallel Range-Request connections. Each chunk writes directly
     * into its slice of a pre-allocated byte array — no copying
     * or merging needed.
     *
     * @param url         final CDN URL (after redirects)
     * @param totalBytes  exact file size from Content-Length
     * @param connections number of parallel connections (2–8)
     * @param listener    progress callback (receives aggregated bytes)
     * @return complete file contents
     */
    static byte[] execute(String url, long totalBytes, int connections,
            DownloadProgressListener listener) throws IOException {
        byte[] result = new byte[(int) totalBytes];
        AtomicLong globalTransferred = new AtomicLong(0);
        AtomicLongArray chunkProgress = new AtomicLongArray(connections);

        long chunkSize = totalBytes / connections;

        try (ExecutorService pool = Executors.newFixedThreadPool(connections)) {
            @SuppressWarnings("unchecked")
            Future<Void>[] futures = new Future[connections];

            for (int i = 0; i < connections; i++) {
                long start = i * chunkSize;
                long end = (i == connections - 1) ? totalBytes - 1 : (start + chunkSize - 1);
                int chunkIndex = i;

                futures[i] = pool.submit(() -> {
                    log("  Chunk " + (chunkIndex + 1) + "/" + connections
                            + ": " + formatRange(start, end));
                    downloadChunk(url, result, (int) start, start, end, chunkIndex,
                            chunkProgress, globalTransferred, totalBytes, listener);
                    log("  Chunk " + (chunkIndex + 1) + "/" + connections + " complete");
                    return null;
                });
            }

            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    pool.shutdownNow();
                    throw new IOException("Parallel download failed: " + e.getMessage(), e);
                }
            }
        }

        listener.onProgress(totalBytes, totalBytes);
        log("Parallel download complete: " + formatBytes(totalBytes));
        return result;
    }

    private static void downloadChunk(String url, byte[] target,
            int offset, long rangeStart, long rangeEnd, int chunkIndex,
            AtomicLongArray chunkProgress, AtomicLong globalTransferred,
            long totalBytes, DownloadProgressListener listener) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", Downloader.USER_AGENT)
                    .header("Range", "bytes=" + rangeStart + "-" + rangeEnd)
                    .GET().build();
            HttpResponse<InputStream> response = HTTP11.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();
            if (status != 206 && status != 200) {
                throw new IOException("HTTP " + status + " for range " + rangeStart + "-" + rangeEnd);
            }

            try (InputStream in = response.body()) {
                byte[] buffer = new byte[65536];
                int pos = offset;
                int read;
                long lastUpdate = 0;
                while ((read = in.read(buffer)) != -1) {
                    System.arraycopy(buffer, 0, target, pos, read);
                    pos += read;
                    long total = globalTransferred.addAndGet(read);
                    chunkProgress.addAndGet(chunkIndex, read);

                    long now = System.currentTimeMillis();
                    if (now - lastUpdate > 100) {
                        listener.onProgress(total, totalBytes);
                        lastUpdate = now;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Chunk download interrupted", e);
        }
    }

    private static String formatRange(long start, long end) {
        return formatBytes(start) + " – " + formatBytes(end)
                + " (" + formatBytes(end - start + 1) + ")";
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
