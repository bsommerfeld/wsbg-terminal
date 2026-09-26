package de.bsommerfeld.tinyfetch.curl;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * One libcurl easy handle - in browser terms, one tab that is reused for
 * every request to its host.
 *
 * <h3>Reuse is the point</h3>
 * Between transfers the handle is {@code curl_easy_reset}, which clears the
 * options but keeps the open connection, so consecutive requests ride one
 * HTTP/2 connection the way a tab does, instead of a fresh TLS handshake per
 * request - the pattern of a script, not of a person.
 *
 * <h3>Not thread-safe</h3>
 * A handle must never run two transfers at once; the caller serialises (in
 * TinyFetch, the per-host lock does).
 *
 * <h3>Callbacks</h3>
 * Body, headers and progress arrive through three static upcall stubs shared
 * by every handle. Each transfer registers its sink under a numeric id, which
 * libcurl hands back as the callback's user pointer. The upcalls catch
 * everything: an exception escaping into native code would take the JVM down.
 */
public final class CurlEasy implements AutoCloseable {

    static {
        if (CurlLibrary.C_SIZE_T.byteSize() != 8) {
            throw new IllegalStateException("TinyFetch needs a 64-bit platform");
        }
    }

    private static final Map<Long, Sink> SINKS = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong(1);

    private static final FunctionDescriptor DATA_CALLBACK =
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS);

    private static final MemorySegment WRITE_STUB = CurlLibrary.upcall(
            CurlLibrary.upcallTarget(MethodHandles.lookup(), "onBody",
                    long.class, MemorySegment.class, long.class, long.class, MemorySegment.class),
            DATA_CALLBACK);
    private static final MemorySegment HEADER_STUB = CurlLibrary.upcall(
            CurlLibrary.upcallTarget(MethodHandles.lookup(), "onHeader",
                    long.class, MemorySegment.class, long.class, long.class, MemorySegment.class),
            DATA_CALLBACK);
    private static final MemorySegment PROGRESS_STUB = CurlLibrary.upcall(
            CurlLibrary.upcallTarget(MethodHandles.lookup(), "onProgress",
                    int.class, MemorySegment.class, long.class, long.class, long.class, long.class),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG));

    private static final String PROTOCOLS = "http,https";
    private static final int MAX_REDIRECTS = 10;

    private final CurlLibrary library;
    private final CurlShare share;
    private MemorySegment handle;

    public CurlEasy(CurlLibrary library, CurlShare share) throws CurlException {
        this.library = library;
        this.share = share;
        try {
            this.handle = (MemorySegment) library.easyInit.invokeExact();
        } catch (Throwable t) {
            throw new CurlException("curl_easy_init: " + t, t);
        }
        if (handle.equals(MemorySegment.NULL)) {
            throw new CurlException("curl_easy_init returned NULL", 2);
        }
    }

    /**
     * Runs one transfer, redirects included.
     *
     * @throws CurlException for anything that is not an HTTP answer: DNS, TLS,
     *                       timeout, cancellation, a body over its limit
     */
    public CurlResponse perform(CurlRequest request) throws CurlException {
        Sink sink = new Sink(request.maxBodyBytes(), request.cancelled());
        long sinkId = IDS.getAndIncrement();
        SINKS.put(sinkId, sink);
        MemorySegment headerList = MemorySegment.NULL;
        try (Arena arena = Arena.ofConfined()) {
            reset();
            configure(request, arena, sinkId);

            headerList = library.slist(request.headerLines(), arena);
            library.setPointer(handle, CurlConstants.OPT_HTTPHEADER, headerList);

            MemorySegment errorBuffer = arena.allocate(CurlConstants.ERROR_SIZE);
            library.setPointer(handle, CurlConstants.OPT_ERRORBUFFER, errorBuffer);

            int code;
            try {
                code = (int) library.easyPerform.invokeExact(handle);
            } catch (Throwable t) {
                throw new CurlException("curl_easy_perform: " + t, t);
            }
            if (code != 0) {
                throw new CurlException(failureText(code, sink, errorBuffer), code);
            }

            int status = (int) library.infoLong(handle, CurlConstants.INFO_RESPONSE_CODE);
            String effectiveUrl = library.infoString(handle, CurlConstants.INFO_EFFECTIVE_URL);
            String version = CurlConstants.httpVersionName(library.infoLong(handle, CurlConstants.INFO_HTTP_VERSION));
            return new CurlResponse(status, effectiveUrl == null ? request.url() : effectiveUrl,
                    version, List.copyOf(sink.headers), sink.body.toByteArray());
        } finally {
            SINKS.remove(sinkId);
            library.freeSlist(headerList);
            /*
             * The options pointed into the arena that just closed; reset now
             * so no pointer into freed memory survives until the next transfer.
            */
            reset();
        }
    }

    private void configure(CurlRequest request, Arena arena, long sinkId) throws CurlException {
        /*
         * Impersonation first: it sets the TLS and HTTP/2 fingerprint through
         * a batch of setopts. The 0 withholds the target's default headers -
         * the header set is ours, built per request kind, in its final order.
        */
        int code;
        try {
            code = (int) library.easyImpersonate.invokeExact(handle,
                    arena.allocateFrom(request.impersonateTarget()), 0);
        } catch (Throwable t) {
            throw new CurlException("curl_easy_impersonate: " + t, t);
        }
        library.check(code, "impersonate " + request.impersonateTarget());

        library.setPointer(handle, CurlConstants.OPT_SHARE, share.handle());
        library.setString(handle, CurlConstants.OPT_URL, request.url(), arena);
        library.setString(handle, CurlConstants.OPT_PROTOCOLS_STR, PROTOCOLS, arena);
        library.setString(handle, CurlConstants.OPT_REDIR_PROTOCOLS_STR, PROTOCOLS, arena);
        library.setLong(handle, CurlConstants.OPT_FOLLOWLOCATION, 1);
        library.setLong(handle, CurlConstants.OPT_MAXREDIRS, MAX_REDIRECTS);
        library.setLong(handle, CurlConstants.OPT_NOSIGNAL, 1);
        library.setLong(handle, CurlConstants.OPT_TIMEOUT_MS, request.timeout().toMillis());
        library.setLong(handle, CurlConstants.OPT_CONNECTTIMEOUT_MS, request.connectTimeout().toMillis());

        /*
         * The headers announce the encodings; this option is what makes
         * libcurl decode them. With a custom Accept-Encoding header present,
         * libcurl sends ours and still decodes.
        */
        library.setString(handle, CurlConstants.OPT_ACCEPT_ENCODING, request.acceptEncoding(), arena);

        // An empty cookie file switches the cookie engine on; the jar is the share's.
        library.setString(handle, CurlConstants.OPT_COOKIEFILE, "", arena);

        if (request.caBundle() != null) {
            library.setString(handle, CurlConstants.OPT_CAINFO, request.caBundle().toString(), arena);
        }

        switch (request.method()) {
            case "GET" -> library.setLong(handle, CurlConstants.OPT_HTTPGET, 1);
            case "POST" -> library.setLong(handle, CurlConstants.OPT_POST, 1);
            default -> library.setString(handle, CurlConstants.OPT_CUSTOMREQUEST, request.method(), arena);
        }
        if (request.body() != null) {
            // Size first: COPYPOSTFIELDS copies exactly that many bytes, binary-safe.
            library.setOffT(handle, CurlConstants.OPT_POSTFIELDSIZE_LARGE, request.body().length);
            MemorySegment body = arena.allocate(Math.max(1, request.body().length));
            MemorySegment.copy(request.body(), 0, body, JAVA_BYTE, 0, request.body().length);
            library.setPointer(handle, CurlConstants.OPT_COPYPOSTFIELDS, body);
        }

        MemorySegment user = MemorySegment.ofAddress(sinkId);
        library.setPointer(handle, CurlConstants.OPT_WRITEFUNCTION, WRITE_STUB);
        library.setPointer(handle, CurlConstants.OPT_WRITEDATA, user);
        library.setPointer(handle, CurlConstants.OPT_HEADERFUNCTION, HEADER_STUB);
        library.setPointer(handle, CurlConstants.OPT_HEADERDATA, user);
        library.setLong(handle, CurlConstants.OPT_NOPROGRESS, 0);
        library.setPointer(handle, CurlConstants.OPT_XFERINFOFUNCTION, PROGRESS_STUB);
        library.setPointer(handle, CurlConstants.OPT_XFERINFODATA, user);
    }

    /** Every cookie of the attached share, as Netscape cookie-file lines. */
    List<String> cookies() throws CurlException {
        try (Arena arena = Arena.ofConfined()) {
            library.setPointer(handle, CurlConstants.OPT_SHARE, share.handle());
            library.setString(handle, CurlConstants.OPT_COOKIEFILE, "", arena);
        }
        return library.infoSlist(handle, CurlConstants.INFO_COOKIELIST);
    }

    /** Adds cookie lines to the attached share. */
    void addCookies(List<String> lines) throws CurlException {
        try (Arena arena = Arena.ofConfined()) {
            library.setPointer(handle, CurlConstants.OPT_SHARE, share.handle());
            library.setString(handle, CurlConstants.OPT_COOKIEFILE, "", arena);
            for (String line : lines) {
                if (!line.isBlank()) {
                    library.setString(handle, CurlConstants.OPT_COOKIELIST, line, arena);
                }
            }
        }
    }

    @Override
    public void close() {
        if (handle == null) {
            return;
        }
        try {
            library.easyCleanup.invokeExact(handle);
        } catch (Throwable ignored) {
            // Nothing to recover from a failed cleanup.
        }
        handle = null;
    }

    private void reset() {
        try {
            library.easyReset.invokeExact(handle);
        } catch (Throwable ignored) {
            // curl_easy_reset has no failure mode.
        }
    }

    private String failureText(int code, Sink sink, MemorySegment errorBuffer) {
        if (sink.overflow) {
            return "body larger than " + sink.maxBytes + " bytes";
        }
        String detail = errorBuffer.getString(0);
        String reason = library.describe(code);
        return detail.isBlank() ? reason : reason + ": " + detail;
    }

    // ---- per-transfer sink ------------------------------------------------

    private static final class Sink {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final List<String> headers = new ArrayList<>();
        final long maxBytes;
        final BooleanSupplier cancelled;
        boolean overflow;

        Sink(long maxBytes, BooleanSupplier cancelled) {
            this.maxBytes = maxBytes;
            this.cancelled = cancelled;
        }
    }

    // ---- upcalls: must never throw into native code ------------------------

    private static long onBody(MemorySegment data, long size, long count, MemorySegment user) {
        try {
            Sink sink = SINKS.get(user.address());
            long length = size * count;
            if (sink == null) {
                return 0;
            }
            if (sink.body.size() + length > sink.maxBytes) {
                sink.overflow = true;
                return 0; // anything short of length makes libcurl abort with CURLE_WRITE_ERROR
            }
            sink.body.write(data.reinterpret(length).toArray(JAVA_BYTE), 0, (int) length);
            return length;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long onHeader(MemorySegment data, long size, long count, MemorySegment user) {
        try {
            Sink sink = SINKS.get(user.address());
            long length = size * count;
            if (sink == null) {
                return 0;
            }
            String line = new String(data.reinterpret(length).toArray(JAVA_BYTE), StandardCharsets.ISO_8859_1).strip();
            /*
             * Every response of a redirect chain passes through here. A status
             * line starts the next one, so only the final response's headers
             * are left at the end.
            */
            if (line.startsWith("HTTP/")) {
                sink.headers.clear();
            } else if (!line.isEmpty()) {
                sink.headers.add(line);
            }
            return length;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int onProgress(MemorySegment user, long downloadTotal, long downloadNow,
            long uploadTotal, long uploadNow) {
        try {
            Sink sink = SINKS.get(user.address());
            return sink != null && sink.cancelled.getAsBoolean() ? 1 : 0;
        } catch (Throwable t) {
            return 1;
        }
    }
}
