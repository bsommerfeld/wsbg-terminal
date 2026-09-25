package de.bsommerfeld.wsbg.orb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

import static de.bsommerfeld.wsbg.orb.Native.call;
import static de.bsommerfeld.wsbg.orb.Native.downcall;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Linux: an EGL context on a 1x1 pbuffer. EGL needs no window and binds to any
 * thread, and Mesa and the NVIDIA driver both ship it - under X11 and Wayland alike.
 */
final class EGL implements GL {

    private static final int EGL_NONE = 0x3038;
    private static final int EGL_SURFACE_TYPE = 0x3033;
    private static final int EGL_PBUFFER_BIT = 0x0001;
    private static final int EGL_RENDERABLE_TYPE = 0x3040;
    private static final int EGL_OPENGL_BIT = 0x0008;
    private static final int EGL_RED_SIZE = 0x3024;
    private static final int EGL_GREEN_SIZE = 0x3023;
    private static final int EGL_BLUE_SIZE = 0x3022;
    private static final int EGL_ALPHA_SIZE = 0x3021;
    private static final int EGL_WIDTH = 0x3057;
    private static final int EGL_HEIGHT = 0x3056;
    private static final int EGL_OPENGL_API = 0x30A2;
    private static final int EGL_CONTEXT_MAJOR_VERSION = 0x3098;
    private static final int EGL_CONTEXT_MINOR_VERSION = 0x30FB;
    private static final int EGL_CONTEXT_OPENGL_PROFILE_MASK = 0x30FD;
    private static final int EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT = 0x0001;

    private static final SymbolLookup LIBRARY = SymbolLookup.libraryLookup("libEGL.so.1", Arena.global());
    /*
     * Fallback for GL functions eglGetProcAddress does not hand out: drivers
     * without EGL_KHR_get_all_proc_addresses only resolve extensions there. The
     * GLVND name first, the classic one for systems without GLVND.
     */
    private static final Optional<SymbolLookup> GL_LIBRARY = openFirst("libOpenGL.so.0", "libGL.so.1");

    private static final MethodHandle GET_DISPLAY = downcall(LIBRARY, "eglGetDisplay", ADDRESS, ADDRESS);
    private static final MethodHandle INITIALIZE = downcall(LIBRARY, "eglInitialize", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle BIND_API = downcall(LIBRARY, "eglBindAPI", JAVA_INT, JAVA_INT);
    private static final MethodHandle CHOOSE_CONFIG = downcall(LIBRARY, "eglChooseConfig", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
    private static final MethodHandle CREATE_PBUFFER_SURFACE = downcall(LIBRARY, "eglCreatePbufferSurface", ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle CREATE_CONTEXT = downcall(LIBRARY, "eglCreateContext", ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle MAKE_CURRENT = downcall(LIBRARY, "eglMakeCurrent", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle DESTROY_SURFACE = downcall(LIBRARY, "eglDestroySurface", JAVA_INT, ADDRESS, ADDRESS);
    private static final MethodHandle DESTROY_CONTEXT = downcall(LIBRARY, "eglDestroyContext", JAVA_INT, ADDRESS, ADDRESS);
    private static final MethodHandle GET_PROC_ADDRESS = downcall(LIBRARY, "eglGetProcAddress", ADDRESS, ADDRESS);
    private static final MethodHandle GET_ERROR = downcall(LIBRARY, "eglGetError", JAVA_INT);

    private final MemorySegment display;
    private final MemorySegment surface;
    private final MemorySegment context;

    EGL() {
        display = (MemorySegment) call(GET_DISPLAY, MemorySegment.NULL);
        if (display.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("eglGetDisplay found no display");
        }
        try (Arena arena = Arena.ofConfined()) {
            check((int) call(INITIALIZE, display, MemorySegment.NULL, MemorySegment.NULL), "eglInitialize");
            check((int) call(BIND_API, EGL_OPENGL_API), "eglBindAPI");

            MemorySegment configAttributes = arena.allocateFrom(JAVA_INT,
                    EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                    EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
                    EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                    EGL_NONE);
            MemorySegment config = arena.allocate(ADDRESS);
            MemorySegment count = arena.allocate(JAVA_INT);
            check((int) call(CHOOSE_CONFIG, display, configAttributes, config, 1, count), "eglChooseConfig");
            if (count.get(JAVA_INT, 0) == 0) {
                throw new IllegalStateException("eglChooseConfig found no config for desktop OpenGL");
            }
            MemorySegment chosen = config.get(ADDRESS, 0);

            MemorySegment surfaceAttributes = arena.allocateFrom(JAVA_INT, EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE);
            surface = created((MemorySegment) call(CREATE_PBUFFER_SURFACE, display, chosen, surfaceAttributes),
                    "eglCreatePbufferSurface");

            MemorySegment contextAttributes = arena.allocateFrom(JAVA_INT,
                    EGL_CONTEXT_MAJOR_VERSION, 4,
                    EGL_CONTEXT_MINOR_VERSION, 1,
                    EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
                    EGL_NONE);
            context = created((MemorySegment) call(CREATE_CONTEXT, display, chosen, MemorySegment.NULL, contextAttributes),
                    "eglCreateContext");
        }
    }

    @Override
    public void makeCurrent() {
        check((int) call(MAKE_CURRENT, display, surface, surface, context), "eglMakeCurrent");
    }

    @Override
    public MemorySegment function(String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment address = (MemorySegment) call(GET_PROC_ADDRESS, arena.allocateFrom(name));
            if (!address.equals(MemorySegment.NULL)) {
                return address;
            }
        }
        return GL_LIBRARY.flatMap(library -> library.find(name))
                .orElseThrow(() -> new IllegalStateException("OpenGL function missing: " + name));
    }

    /*
     * The display stays initialised: it is shared process-wide, and terminating it
     * would pull it from under anyone else on it - JavaFX's own renderer included.
     */
    @Override
    public void close() {
        call(MAKE_CURRENT, display, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
        call(DESTROY_CONTEXT, display, context);
        call(DESTROY_SURFACE, display, surface);
    }

    private static Optional<SymbolLookup> openFirst(String... names) {
        for (String name : names) {
            try {
                return Optional.of(SymbolLookup.libraryLookup(name, Arena.global()));
            } catch (IllegalArgumentException missing) {
                // not installed - try the next name
            }
        }
        return Optional.empty();
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

    private static IllegalStateException failure(String what) {
        return new IllegalStateException(what + " failed with EGL error 0x" + Integer.toHexString((int) call(GET_ERROR)));
    }
}
