package de.bsommerfeld.wsbg.orb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static de.bsommerfeld.wsbg.orb.Native.call;
import static de.bsommerfeld.wsbg.orb.Native.downcall;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Windows: a WGL context on a hidden 1x1 window. WGL cannot do without a window's
 * device context, and a modern core context only through
 * {@code wglCreateContextAttribsARB}, which a legacy context has to be current to
 * look up - so a throwaway legacy context comes first.
 * <p>
 * The system driver is tried first. Where it offers no OpenGL 4.1 - a virtual
 * machine, Remote Desktop, a missing graphics driver: Windows' own
 * {@code opengl32.dll} then stops at 1.1 - the context comes from Mesa's
 * software renderer instead, if the application ships it in the directory named
 * by the system property {@value #MESA_PROPERTY}.
 * <p>
 * The window uses the predefined {@code STATIC} class, which spares registering a
 * class of our own and the window procedure upcall that would take. It is never
 * shown; it dies with the context, on the thread that made it.
 */
final class WGL implements GL {

    /** The directory holding Mesa's {@code opengl32.dll} and {@code libgallium_wgl.dll}. */
    static final String MESA_PROPERTY = "de.bsommerfeld.wsbg.orb.mesa";

    private static final int WS_POPUP = 0x80000000;
    private static final int WS_CLIPSIBLINGS = 0x04000000;
    private static final int WS_CLIPCHILDREN = 0x02000000;
    private static final int PFD_DOUBLEBUFFER = 0x00000001;
    private static final int PFD_DRAW_TO_WINDOW = 0x00000004;
    private static final int PFD_SUPPORT_OPENGL = 0x00000020;
    private static final int PFD_SIZE = 40;
    private static final int WGL_CONTEXT_MAJOR_VERSION_ARB = 0x2091;
    private static final int WGL_CONTEXT_MINOR_VERSION_ARB = 0x2092;
    private static final int WGL_CONTEXT_PROFILE_MASK_ARB = 0x9126;
    private static final int WGL_CONTEXT_CORE_PROFILE_BIT_ARB = 0x0001;

    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());
    private static final SymbolLookup GDI32 = SymbolLookup.libraryLookup("gdi32", Arena.global());
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

    private static final MethodHandle CREATE_WINDOW = downcall(USER32, "CreateWindowExW", ADDRESS,
            JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle DESTROY_WINDOW = downcall(USER32, "DestroyWindow", JAVA_INT, ADDRESS);
    private static final MethodHandle GET_DC = downcall(USER32, "GetDC", ADDRESS, ADDRESS);
    private static final MethodHandle RELEASE_DC = downcall(USER32, "ReleaseDC", JAVA_INT, ADDRESS, ADDRESS);
    private static final MethodHandle GET_MODULE_HANDLE = downcall(KERNEL32, "GetModuleHandleW", ADDRESS, ADDRESS);

    /**
     * Where the WGL functions come from. The system's {@code opengl32.dll} leaves
     * the pixel format to GDI, which hands it to the installed driver; Mesa
     * brings its own and is asked directly, so GDI never has to pick between
     * two {@code opengl32.dll} in one process.
     */
    private record Library(String name, SymbolLookup opengl,
                           MethodHandle choosePixelFormat, MethodHandle setPixelFormat,
                           MethodHandle createContext, MethodHandle makeCurrent,
                           MethodHandle deleteContext, MethodHandle getProcAddress) {

        static Library system() {
            SymbolLookup opengl = SymbolLookup.libraryLookup("opengl32", Arena.global());
            return of("system OpenGL", opengl,
                    downcall(GDI32, "ChoosePixelFormat", JAVA_INT, ADDRESS, ADDRESS),
                    downcall(GDI32, "SetPixelFormat", JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        }

        /*
         * libgallium_wgl.dll first, by its full path: opengl32.dll imports it by
         * name, and a DLL of that name already in the process is what the loader
         * resolves the import to - the application directory is not where it
         * would look.
         */
        static Library mesa(Path directory) {
            SymbolLookup.libraryLookup(directory.resolve("libgallium_wgl.dll"), Arena.global());
            SymbolLookup opengl = SymbolLookup.libraryLookup(directory.resolve("opengl32.dll"), Arena.global());
            return of("Mesa (" + directory + ")", opengl,
                    downcall(opengl, "wglChoosePixelFormat", JAVA_INT, ADDRESS, ADDRESS),
                    downcall(opengl, "wglSetPixelFormat", JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        }

        private static Library of(String name, SymbolLookup opengl,
                                  MethodHandle choosePixelFormat, MethodHandle setPixelFormat) {
            return new Library(name, opengl, choosePixelFormat, setPixelFormat,
                    downcall(opengl, "wglCreateContext", ADDRESS, ADDRESS),
                    downcall(opengl, "wglMakeCurrent", JAVA_INT, ADDRESS, ADDRESS),
                    downcall(opengl, "wglDeleteContext", JAVA_INT, ADDRESS),
                    downcall(opengl, "wglGetProcAddress", ADDRESS, ADDRESS));
        }
    }

    private final Library library;
    private MemorySegment window = MemorySegment.NULL;
    private MemorySegment deviceContext = MemorySegment.NULL;
    private MemorySegment context = MemorySegment.NULL;

    /** The system driver's context, or Mesa's where the driver has no OpenGL 4.1 and Mesa is shipped. */
    static WGL create() {
        try {
            return new WGL(Library.system());
        } catch (RuntimeException systemFailure) {
            Path mesa = mesaDirectory();
            if (mesa == null) {
                throw systemFailure;
            }
            try {
                return new WGL(Library.mesa(mesa));
            } catch (RuntimeException mesaFailure) {
                mesaFailure.addSuppressed(systemFailure);
                throw mesaFailure;
            }
        }
    }

    private static Path mesaDirectory() {
        String property = System.getProperty(MESA_PROPERTY);
        if (property == null || property.isBlank()) {
            return null;
        }
        Path directory = Path.of(property);
        return Files.isRegularFile(directory.resolve("opengl32.dll")) ? directory : null;
    }

    /** Builds the context; whatever was made before a failure is released again. */
    private WGL(Library library) {
        this.library = library;
        try {
            open();
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private void open() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment module = (MemorySegment) call(GET_MODULE_HANDLE, MemorySegment.NULL);
            window = created((MemorySegment) call(CREATE_WINDOW, 0,
                    arena.allocateFrom("STATIC", StandardCharsets.UTF_16LE),
                    arena.allocateFrom("", StandardCharsets.UTF_16LE),
                    WS_POPUP | WS_CLIPSIBLINGS | WS_CLIPCHILDREN, 0, 0, 1, 1,
                    MemorySegment.NULL, MemorySegment.NULL, module, MemorySegment.NULL), "CreateWindowExW");
            deviceContext = created((MemorySegment) call(GET_DC, window), "GetDC");

            MemorySegment descriptor = arena.allocate(PFD_SIZE, 4);
            descriptor.set(JAVA_SHORT, 0, (short) PFD_SIZE);                        // nSize
            descriptor.set(JAVA_SHORT, 2, (short) 1);                               // nVersion
            descriptor.set(JAVA_INT, 4, PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER);
            descriptor.set(JAVA_BYTE, 8, (byte) 0);                                 // iPixelType: RGBA
            descriptor.set(JAVA_BYTE, 9, (byte) 32);                                // cColorBits
            descriptor.set(JAVA_BYTE, 16, (byte) 8);                                // cAlphaBits
            int format = (int) call(library.choosePixelFormat(), deviceContext, descriptor);
            if (format == 0) {
                throw failure("ChoosePixelFormat");
            }
            check((int) call(library.setPixelFormat(), deviceContext, format, descriptor), "SetPixelFormat");

            MemorySegment legacy = created((MemorySegment) call(library.createContext(), deviceContext), "wglCreateContext");
            try {
                check((int) call(library.makeCurrent(), deviceContext, legacy), "wglMakeCurrent");
                MemorySegment createContextAttributes = procAddress("wglCreateContextAttribsARB");
                if (missing(createContextAttributes)) {
                    throw new IllegalStateException(library.name()
                            + " offers no core-profile OpenGL (wglCreateContextAttribsARB missing)");
                }
                MemorySegment attributes = arena.allocateFrom(JAVA_INT,
                        WGL_CONTEXT_MAJOR_VERSION_ARB, 4,
                        WGL_CONTEXT_MINOR_VERSION_ARB, 1,
                        WGL_CONTEXT_PROFILE_MASK_ARB, WGL_CONTEXT_CORE_PROFILE_BIT_ARB,
                        0);
                MethodHandle createCore = downcall(createContextAttributes, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
                context = created((MemorySegment) call(createCore, deviceContext, MemorySegment.NULL, attributes),
                        "wglCreateContextAttribsARB (OpenGL 4.1 core)");
            } finally {
                call(library.makeCurrent(), MemorySegment.NULL, MemorySegment.NULL);
                call(library.deleteContext(), legacy);
            }
        }
    }

    @Override
    public void makeCurrent() {
        check((int) call(library.makeCurrent(), deviceContext, context), "wglMakeCurrent");
    }

    /**
     * {@code wglGetProcAddress} only knows what came after OpenGL 1.1; the 1.1
     * functions come straight from the {@code opengl32.dll} in use.
     */
    @Override
    public MemorySegment function(String name) {
        MemorySegment address = procAddress(name);
        if (missing(address)) {
            return library.opengl().find(name)
                    .orElseThrow(() -> new IllegalStateException("OpenGL function missing: " + name));
        }
        return address;
    }

    private MemorySegment procAddress(String name) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) call(library.getProcAddress(), arena.allocateFrom(name));
        }
    }

    /** Some drivers answer an unknown name with 1, 2, 3 or -1 instead of null. */
    private static boolean missing(MemorySegment address) {
        long raw = address.address();
        return raw == 0 || raw == 1 || raw == 2 || raw == 3 || raw == -1;
    }

    /** Releases whatever exists - also the parts of a context whose creation failed halfway. */
    @Override
    public void close() {
        if (!context.equals(MemorySegment.NULL)) {
            call(library.makeCurrent(), MemorySegment.NULL, MemorySegment.NULL);
            call(library.deleteContext(), context);
            context = MemorySegment.NULL;
        }
        if (!deviceContext.equals(MemorySegment.NULL)) {
            call(RELEASE_DC, window, deviceContext);
            deviceContext = MemorySegment.NULL;
        }
        if (!window.equals(MemorySegment.NULL)) {
            call(DESTROY_WINDOW, window);
            window = MemorySegment.NULL;
        }
    }

    private static MemorySegment created(MemorySegment handle, String what) {
        if (handle.equals(MemorySegment.NULL)) {
            throw failure(what);
        }
        return handle;
    }

    private static void check(int result, String what) {
        if (result == 0) {
            throw failure(what);
        }
    }

    /*
     * No GetLastError: called as a downcall of its own it may already read an
     * error the JVM caused in between, which would point the search the wrong way.
     */
    private static IllegalStateException failure(String what) {
        return new IllegalStateException(what + " failed");
    }
}
