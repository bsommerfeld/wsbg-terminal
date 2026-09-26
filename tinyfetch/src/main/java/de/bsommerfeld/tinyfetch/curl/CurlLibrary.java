package de.bsommerfeld.tinyfetch.curl;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The slice of libcurl(-impersonate) TinyFetch calls, as FFM downcalls.
 *
 * <h3>One library per process</h3>
 * libcurl keeps process-wide state ({@code curl_global_init}), so the library
 * is loaded and initialised exactly once, by the first {@link #load} - later
 * calls get that same instance, whatever path they name.
 *
 * <h3>C {@code long} is not Java {@code long}</h3>
 * {@code curl_easy_setopt} takes a C {@code long} for numeric options, and that
 * is 32 bits on Windows, 64 everywhere else. The layout is taken from the
 * native linker instead of assumed, so the variadic call pushes exactly what
 * libcurl's {@code va_arg(long)} reads on every platform.
 */
public final class CurlLibrary {

    private static final Linker LINKER = Linker.nativeLinker();

    /** C {@code long}: JAVA_INT on Windows, JAVA_LONG elsewhere. */
    static final ValueLayout C_LONG = (ValueLayout) LINKER.canonicalLayouts().get("long");

    /** C {@code size_t}, the unit of the write and header callbacks. */
    static final ValueLayout C_SIZE_T = (ValueLayout) LINKER.canonicalLayouts().get("size_t");

    /** {@code char *} that may be read as a C string of any length. */
    static final AddressLayout C_STRING =
            ADDRESS.withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, ValueLayout.JAVA_BYTE));

    /** {@code CURL_GLOBAL_DEFAULT} = {@code CURL_GLOBAL_ALL}: SSL + Win32 sockets. */
    private static final long CURL_GLOBAL_DEFAULT = 3;

    private static CurlLibrary instance;

    final Path path;
    final String version;

    final MethodHandle easyInit;
    final MethodHandle easyCleanup;
    final MethodHandle easyReset;
    final MethodHandle easyImpersonate;
    final MethodHandle easyPerform;
    final MethodHandle easyStrerror;
    final MethodHandle setoptLong;
    final MethodHandle setoptPointer;
    final MethodHandle setoptOffT;
    final MethodHandle getinfoPointer;
    final MethodHandle slistAppend;
    final MethodHandle slistFreeAll;
    final MethodHandle shareInit;
    final MethodHandle shareSetoptInt;
    final MethodHandle shareSetoptPointer;
    final MethodHandle shareCleanup;

    private CurlLibrary(Path path) throws Throwable {
        this.path = path;

        /*
         * Global arena: the library stays mapped for the life of the process,
         * which is also what libcurl's global state expects - there is no
         * point at which unloading it would be safe.
        */
        SymbolLookup symbols = SymbolLookup.libraryLookup(path, Arena.global());

        MethodHandle globalInit = downcall(symbols, "curl_global_init", FunctionDescriptor.of(JAVA_INT, C_LONG));
        MethodHandle versionOf = downcall(symbols, "curl_version", FunctionDescriptor.of(C_STRING));

        this.easyInit = downcall(symbols, "curl_easy_init", FunctionDescriptor.of(ADDRESS));
        this.easyCleanup = downcall(symbols, "curl_easy_cleanup", FunctionDescriptor.ofVoid(ADDRESS));
        this.easyReset = downcall(symbols, "curl_easy_reset", FunctionDescriptor.ofVoid(ADDRESS));
        this.easyImpersonate = downcall(symbols, "curl_easy_impersonate",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        this.easyPerform = downcall(symbols, "curl_easy_perform", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        this.easyStrerror = downcall(symbols, "curl_easy_strerror", FunctionDescriptor.of(C_STRING, JAVA_INT));

        /*
         * setopt and getinfo are variadic: the third argument is the variadic
         * one, and its type decides how it is passed. One handle per type.
        */
        Linker.Option variadicThird = Linker.Option.firstVariadicArg(2);
        this.setoptLong = downcall(symbols, "curl_easy_setopt",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, C_LONG), variadicThird);
        this.setoptPointer = downcall(symbols, "curl_easy_setopt",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), variadicThird);
        this.setoptOffT = downcall(symbols, "curl_easy_setopt",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG), variadicThird);
        this.getinfoPointer = downcall(symbols, "curl_easy_getinfo",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), variadicThird);

        this.slistAppend = downcall(symbols, "curl_slist_append",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        this.slistFreeAll = downcall(symbols, "curl_slist_free_all", FunctionDescriptor.ofVoid(ADDRESS));

        this.shareInit = downcall(symbols, "curl_share_init", FunctionDescriptor.of(ADDRESS));
        this.shareSetoptInt = downcall(symbols, "curl_share_setopt",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), variadicThird);
        this.shareSetoptPointer = downcall(symbols, "curl_share_setopt",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), variadicThird);
        this.shareCleanup = downcall(symbols, "curl_share_cleanup", FunctionDescriptor.of(JAVA_INT, ADDRESS));

        int code = C_LONG == JAVA_INT
                ? (int) globalInit.invokeExact((int) CURL_GLOBAL_DEFAULT)
                : (int) globalInit.invokeExact(CURL_GLOBAL_DEFAULT);
        if (code != 0) {
            throw new CurlException("curl_global_init failed", code);
        }
        this.version = ((MemorySegment) versionOf.invokeExact()).getString(0);
    }

    /**
     * Loads and initialises the library at {@code path}, or returns the one
     * already loaded.
     *
     * @throws CurlException if the file is not a usable libcurl-impersonate
     */
    public static synchronized CurlLibrary load(Path path) throws CurlException {
        if (instance != null) {
            return instance;
        }
        try {
            instance = new CurlLibrary(path);
            return instance;
        } catch (CurlException e) {
            throw e;
        } catch (Throwable t) {
            throw new CurlException("cannot load " + path + ": " + t.getMessage(), t);
        }
    }

    /** The {@code curl_version()} line, e.g. {@code libcurl/8.x BoringSSL ...}. */
    public String version() {
        return version;
    }

    /** Where the library was loaded from. */
    public Path path() {
        return path;
    }

    /** Human-readable text for a {@code CURLcode}. */
    String describe(int code) {
        try {
            return ((MemorySegment) easyStrerror.invokeExact(code)).getString(0);
        } catch (Throwable t) {
            return "CURLcode " + code;
        }
    }

    // ---- setopt / getinfo, typed ------------------------------------------

    void setLong(MemorySegment handle, int option, long value) throws CurlException {
        int code;
        try {
            code = C_LONG == JAVA_INT
                    ? (int) setoptLong.invokeExact(handle, option, (int) value)
                    : (int) setoptLong.invokeExact(handle, option, value);
        } catch (Throwable t) {
            throw new CurlException("setopt " + option + ": " + t, t);
        }
        check(code, "setopt " + option);
    }

    void setOffT(MemorySegment handle, int option, long value) throws CurlException {
        int code;
        try {
            code = (int) setoptOffT.invokeExact(handle, option, value);
        } catch (Throwable t) {
            throw new CurlException("setopt " + option + ": " + t, t);
        }
        check(code, "setopt " + option);
    }

    void setPointer(MemorySegment handle, int option, MemorySegment value) throws CurlException {
        int code;
        try {
            code = (int) setoptPointer.invokeExact(handle, option, value);
        } catch (Throwable t) {
            throw new CurlException("setopt " + option + ": " + t, t);
        }
        check(code, "setopt " + option);
    }

    /** Strings are copied by libcurl on setopt; the arena only has to outlive the call. */
    void setString(MemorySegment handle, int option, String value, Arena arena) throws CurlException {
        setPointer(handle, option, arena.allocateFrom(value));
    }

    long infoLong(MemorySegment handle, int info) throws CurlException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(C_LONG);
            int code = (int) getinfoPointer.invokeExact(handle, info, out);
            check(code, "getinfo " + info);
            return C_LONG == JAVA_INT ? out.get(JAVA_INT, 0) : out.get(JAVA_LONG, 0);
        } catch (CurlException e) {
            throw e;
        } catch (Throwable t) {
            throw new CurlException("getinfo " + info + ": " + t, t);
        }
    }

    String infoString(MemorySegment handle, int info) throws CurlException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(C_STRING);
            int code = (int) getinfoPointer.invokeExact(handle, info, out);
            check(code, "getinfo " + info);
            MemorySegment text = out.get(C_STRING, 0);
            return text.equals(MemorySegment.NULL) ? null : text.getString(0);
        } catch (CurlException e) {
            throw e;
        } catch (Throwable t) {
            throw new CurlException("getinfo " + info + ": " + t, t);
        }
    }

    /** Reads a {@code struct curl_slist *} result into Java strings and frees it. */
    List<String> infoSlist(MemorySegment handle, int info) throws CurlException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int code = (int) getinfoPointer.invokeExact(handle, info, out);
            check(code, "getinfo " + info);
            MemorySegment head = out.get(ADDRESS, 0);
            List<String> lines = readSlist(head);
            freeSlist(head);
            return lines;
        } catch (CurlException e) {
            throw e;
        } catch (Throwable t) {
            throw new CurlException("getinfo " + info + ": " + t, t);
        }
    }

    // ---- curl_slist -------------------------------------------------------

    /** {@code struct curl_slist { char *data; struct curl_slist *next; }} */
    private static final long SLIST_NEXT_OFFSET = ADDRESS.byteSize();

    /** Builds a native list; libcurl copies each string, the arena may close after. */
    MemorySegment slist(List<String> lines, Arena arena) throws CurlException {
        MemorySegment list = MemorySegment.NULL;
        for (String line : lines) {
            try {
                MemorySegment appended = (MemorySegment) slistAppend.invokeExact(list, arena.allocateFrom(line));
                if (appended.equals(MemorySegment.NULL)) {
                    freeSlist(list);
                    throw new CurlException("curl_slist_append ran out of memory", 27);
                }
                list = appended;
            } catch (CurlException e) {
                throw e;
            } catch (Throwable t) {
                freeSlist(list);
                throw new CurlException("curl_slist_append: " + t, t);
            }
        }
        return list;
    }

    void freeSlist(MemorySegment list) {
        if (list.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            slistFreeAll.invokeExact(list);
        } catch (Throwable ignored) {
            // Freeing cannot meaningfully fail; nothing to recover.
        }
    }

    private static List<String> readSlist(MemorySegment head) {
        List<String> lines = new ArrayList<>();
        MemorySegment node = head;
        while (!node.equals(MemorySegment.NULL)) {
            MemorySegment entry = node.reinterpret(2 * ADDRESS.byteSize());
            MemorySegment data = entry.get(C_STRING, 0);
            if (!data.equals(MemorySegment.NULL)) {
                lines.add(data.getString(0));
            }
            node = entry.get(ADDRESS, SLIST_NEXT_OFFSET);
        }
        return lines;
    }

    // ---- helpers ----------------------------------------------------------

    void check(int code, String what) throws CurlException {
        if (code != 0) {
            throw new CurlException(what + ": " + describe(code), code);
        }
    }

    /**
     * @param lookup the owner's own lookup - upcall targets stay private to it
     */
    static MethodHandle upcallTarget(MethodHandles.Lookup lookup, String name, Class<?> returnType,
            Class<?>... parameters) {
        Class<?> owner = lookup.lookupClass();
        try {
            return lookup.findStatic(owner, name, MethodType.methodType(returnType, parameters));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("upcall target " + owner.getSimpleName() + "." + name, e);
        }
    }

    static MemorySegment upcall(MethodHandle target, FunctionDescriptor descriptor) {
        return LINKER.upcallStub(target, descriptor, Arena.global());
    }

    private static MethodHandle downcall(SymbolLookup symbols, String name, FunctionDescriptor descriptor,
            Linker.Option... options) {
        MemorySegment symbol = symbols.find(name)
                .orElseThrow(() -> new IllegalStateException("symbol " + name + " missing - not libcurl-impersonate?"));
        return LINKER.downcallHandle(symbol, descriptor, options);
    }
}
