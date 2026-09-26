package de.bsommerfeld.tinyfetch.curl;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * One browser profile's shared state: the cookie jar, the DNS cache and the
 * TLS session cache, shared by every {@link CurlEasy} attached to it.
 *
 * <h3>Why shared</h3>
 * A browser has one cookie jar, not one per host: a cookie a site sets on
 * {@code www.example.com} for {@code .example.com} is sent to
 * {@code api.example.com} as well. TinyFetch keeps one easy handle per host
 * (so a host's requests queue like a tab's), and this share is what makes
 * those handles behave as one browser again. Resumed TLS sessions come along
 * for the same reason - a returning browser resumes, a fresh client does not.
 *
 * <h3>Locking</h3>
 * libcurl asks the application to lock shared data. The lock and unlock
 * callbacks are upcalls into one {@link ReentrantLock} per data kind; the
 * share's id travels as the callback's user pointer.
 */
public final class CurlShare implements AutoCloseable {

    private static final Map<Long, CurlShare> SHARES = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong(1);

    private static final MemorySegment LOCK_STUB = CurlLibrary.upcall(
            CurlLibrary.upcallTarget(MethodHandles.lookup(), "onLock",
                    void.class, MemorySegment.class, int.class, int.class, MemorySegment.class),
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MemorySegment UNLOCK_STUB = CurlLibrary.upcall(
            CurlLibrary.upcallTarget(MethodHandles.lookup(), "onUnlock",
                    void.class, MemorySegment.class, int.class, MemorySegment.class),
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS));

    private final CurlLibrary library;
    private final long id = IDS.getAndIncrement();
    private final ReentrantLock[] locks = new ReentrantLock[CurlConstants.LOCK_DATA_COUNT];
    private MemorySegment handle;

    public CurlShare(CurlLibrary library) throws CurlException {
        this.library = library;
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
        try {
            this.handle = (MemorySegment) library.shareInit.invokeExact();
        } catch (Throwable t) {
            throw new CurlException("curl_share_init: " + t, t);
        }
        if (handle.equals(MemorySegment.NULL)) {
            throw new CurlException("curl_share_init returned NULL", 27);
        }
        SHARES.put(id, this);
        try {
            setPointer(CurlConstants.SHOPT_USERDATA, MemorySegment.ofAddress(id));
            setPointer(CurlConstants.SHOPT_LOCKFUNC, LOCK_STUB);
            setPointer(CurlConstants.SHOPT_UNLOCKFUNC, UNLOCK_STUB);
            setInt(CurlConstants.SHOPT_SHARE, CurlConstants.LOCK_DATA_COOKIE);
            setInt(CurlConstants.SHOPT_SHARE, CurlConstants.LOCK_DATA_DNS);
            setInt(CurlConstants.SHOPT_SHARE, CurlConstants.LOCK_DATA_SSL_SESSION);
        } catch (CurlException e) {
            close();
            throw e;
        }
    }

    MemorySegment handle() {
        return handle;
    }

    /**
     * Every cookie in the jar, one Netscape cookie-file line each - the form
     * {@link #importCookies} takes back.
     */
    public List<String> exportCookies() throws CurlException {
        try (CurlEasy easy = new CurlEasy(library, this)) {
            return easy.cookies();
        }
    }

    /** Adds cookies in Netscape cookie-file form (or {@code Set-Cookie:} lines) to the jar. */
    public void importCookies(List<String> lines) throws CurlException {
        try (CurlEasy easy = new CurlEasy(library, this)) {
            easy.addCookies(lines);
        }
    }

    @Override
    public void close() {
        if (handle == null) {
            return;
        }
        try {
            int ignored = (int) library.shareCleanup.invokeExact(handle);
        } catch (Throwable ignored) {
            // A share still in use refuses cleanup; the handles holding it are gone at close.
        }
        handle = null;
        SHARES.remove(id);
    }

    private void setInt(int option, int value) throws CurlException {
        int code;
        try {
            code = (int) library.shareSetoptInt.invokeExact(handle, option, value);
        } catch (Throwable t) {
            throw new CurlException("curl_share_setopt " + option + ": " + t, t);
        }
        if (code != 0) {
            throw new CurlException("curl_share_setopt " + option + " failed (CURLSHcode " + code + ")", code);
        }
    }

    private void setPointer(int option, MemorySegment value) throws CurlException {
        int code;
        try {
            code = (int) library.shareSetoptPointer.invokeExact(handle, option, value);
        } catch (Throwable t) {
            throw new CurlException("curl_share_setopt " + option + ": " + t, t);
        }
        if (code != 0) {
            throw new CurlException("curl_share_setopt " + option + " failed (CURLSHcode " + code + ")", code);
        }
    }

    // ---- upcalls: must never throw into native code ------------------------

    private static void onLock(MemorySegment easy, int data, int access, MemorySegment user) {
        ReentrantLock lock = lockFor(data, user);
        if (lock != null) {
            lock.lock();
        }
    }

    private static void onUnlock(MemorySegment easy, int data, MemorySegment user) {
        ReentrantLock lock = lockFor(data, user);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private static ReentrantLock lockFor(int data, MemorySegment user) {
        CurlShare share = SHARES.get(user.address());
        if (share == null || data < 0 || data >= share.locks.length) {
            return null;
        }
        return share.locks[data];
    }
}
