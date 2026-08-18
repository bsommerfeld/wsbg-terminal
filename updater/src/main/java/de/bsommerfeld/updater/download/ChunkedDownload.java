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

    /*
     * 8 is the industry standard (IDM, aria2) and well within
     * GitHub CDN's per-IP connection limits (~16). Going past that limit does
     * not just stop helping - the CDN starts refusing connections.
    */
    static final int MAX_CONNECTIONS = 8;

    /*
     * Below 2 MB, the overhead of N TLS handshakes + thread setup
     * exceeds the gain from parallel transfer.
    */
    static final long MIN_PARALLEL_SIZE = 2_000_000;

    /*
     * One connection per megabyte. The ratio is what keeps small-but-eligible
     * files from opening the full eight sockets for a transfer that ends before
     * the handshakes have paid for themselves.
    */
    private static final long BYTES_PER_CONNECTION = 1_000_000;

    /*
     * HTTP/1.1 forces separate TCP sockets per request.
     * HTTP/2 would multiplex all chunks over one socket,
     * defeating the per-connection throttle bypass. This is also why the client
     * is separate from Downloader.HTTP rather than shared with it.
    */
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

        /*
         * Also the unknown-size exit: Content-Length missing arrives here as -1,
         * falls under the threshold, and takes the single-connection path - which
         * is the only correct answer, since ranges cannot be cut without a size.
        */
        if (totalBytes < MIN_PARALLEL_SIZE) return 1;
        return Math.clamp((int) (totalBytes / BYTES_PER_CONNECTION), 1, MAX_CONNECTIONS);
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

        /*
         * Allocated up front at full size so every chunk can write straight into
         * its own slice - that is what makes the merge step unnecessary. The int
         * cast caps the payload at 2 GB, which an update archive stays well
         * under; anything larger would have to go the streaming route instead.
        */
        byte[] result = new byte[(int) totalBytes];
        AtomicLong globalTransferred = new AtomicLong(0);
        AtomicLongArray chunkProgress = new AtomicLongArray(connections);

        /*
         * Integer division drops the remainder, so this is the size of all
         * chunks but the last - the last one absorbs what is left over below.
        */
        long chunkSize = totalBytes / connections;

        /*
         * Closing the pool is what waits for the threads. On the
         * try-with-resources exit the executor shuts down and blocks until every
         * chunk has finished, so no task can outlive the array it writes into.
        */
        try (ExecutorService pool = Executors.newFixedThreadPool(connections)) {
            @SuppressWarnings("unchecked")
            Future<Void>[] futures = new Future[connections];

            for (int i = 0; i < connections; i++) {

                /*
                 * Half-open ranges would leave gaps: HTTP ranges are inclusive on
                 * both ends, so each chunk stops one byte short of the next one's
                 * start. The last chunk runs to the true end rather than to its
                 * calculated one, picking up the remainder that the division above
                 * discarded - without it the file's tail would never be requested.
                */
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

                    /*
                     * get() is what surfaces a chunk's exception: a failure inside
                     * a submitted task is otherwise held in its Future and would
                     * pass unnoticed, leaving a hole of zero bytes in the result.
                    */
                    f.get();
                } catch (Exception e) {

                    /*
                     * One dead chunk makes the whole array worthless, so the
                     * remaining ones are cancelled rather than left to finish -
                     * otherwise the close above would still wait out every one of
                     * them before the exception could propagate.
                    */
                    pool.shutdownNow();
                    throw new IOException("Parallel download failed: " + e.getMessage(), e);
                }
            }
        }

        /*
         * The per-chunk updates are throttled and interleaved, so the last one
         * reported may sit just short of the total. This sets the bar to
         * complete once the data actually is.
        */
        listener.onProgress(totalBytes, totalBytes);
        log("Parallel download complete: " + formatBytes(totalBytes));
        return result;
    }

    private static void downloadChunk(String url, byte[] target,
            int offset, long rangeStart, long rangeEnd, int chunkIndex,
            AtomicLongArray chunkProgress, AtomicLong globalTransferred,
            long totalBytes, DownloadProgressListener listener) throws IOException {
        try {

            /*
             * The Range header is the whole mechanism: without it every thread
             * would fetch the identical full file. Both ends are inclusive, as
             * the ranges cut in execute() assume.
            */
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", Downloader.USER_AGENT)
                    .header("Range", "bytes=" + rangeStart + "-" + rangeEnd)
                    .GET().build();
            HttpResponse<InputStream> response = HTTP11.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            /*
             * 206 is the answer to a Range request; a server that ignores the
             * header answers 200 with the entire file instead.
            */
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

                    /*
                     * Every thread writes into its own disjoint stretch of the
                     * shared array, so no two ever touch the same index and no
                     * lock is needed. Visibility of those writes to the caller is
                     * carried by the Future.get() in execute().
                    */
                    System.arraycopy(buffer, 0, target, pos, read);
                    pos += read;

                    /*
                     * The listener is handed the aggregate across all chunks, not
                     * this chunk's own count - a bar per connection would be
                     * meaningless to whoever is watching one download.
                    */
                    long total = globalTransferred.addAndGet(read);
                    chunkProgress.addAndGet(chunkIndex, read);

                    /*
                     * 100ms here against the 50ms of the single-connection path,
                     * because N threads report in parallel: at eight connections
                     * this still amounts to roughly eighty updates a second.
                    */
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate > 100) {
                        listener.onProgress(total, totalBytes);
                        lastUpdate = now;
                    }
                }
            }
        } catch (InterruptedException e) {

            /*
             * Restores the flag send() cleared, so that a shutdownNow() from
             * execute() is not silently absorbed by this chunk.
            */
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
