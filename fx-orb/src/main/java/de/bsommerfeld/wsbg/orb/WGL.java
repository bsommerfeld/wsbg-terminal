package de.bsommerfeld.wsbg.orb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

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
 * The window uses the predefined {@code STATIC} class, which spares registering a
 * class of our own and the window procedure upcall that would take. It is never
 * shown; it dies with the context, on the thread that made it.
 */
final class WGL implements GL {

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
    private static final SymbolLookup OPENGL32 = SymbolLookup.libraryLookup("opengl32", Arena.global());

    private static final MethodHandle CREATE_WINDOW = downcall(USER32, "CreateWindowExW", ADDRESS,
            JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle DESTROY_WINDOW = downcall(USER32, "DestroyWindow", JAVA_INT, ADDRESS);
    private static final MethodHandle GET_DC = downcall(USER32, "GetDC", ADDRESS, ADDRESS);
    private static final MethodHandle RELEASE_DC = downcall(USER32, "ReleaseDC", JAVA_INT, ADDRESS, ADDRESS);
    private static final MethodHandle CHOOSE_PIXEL_FORMAT = downcall(GDI32, "ChoosePixelFormat", JAVA_INT, ADDRESS, ADDRESS);
    private static final MethodHandle SET_PIXEL_FORMAT = downcall(GDI32, "SetPixelFormat", JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
    private static final MethodHandle GET_MODULE_HANDLE = downcall(KERNEL32, "GetModuleHandleW", ADDRESS, ADDRESS);
    private static final MethodHandle CREATE_CONTEXT = downcall(OPENGL32, "wglCreateContext", ADDRESS, ADDRESS);
    private static final MethodHandle MAKE_CURRENT = downcall(OPENGL32, "wglMakeCurrent", JAVA_INT, ADDRESS, ADDRESS);
    private static final MethodHandle DELETE_CONTEXT = downcall(OPENGL32, "wglDeleteContext", JAVA_INT, ADDRESS);
    private static final MethodHandle GET_PROC_ADDRESS = downcall(OPENGL32, "wglGetProcAddress", ADDRESS, ADDRESS);

    private final MemorySegment window;
    private final MemorySegment deviceContext;
    private final MemorySegment context;

    WGL() {
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
            int format = (int) call(CHOOSE_PIXEL_FORMAT, deviceContext, descriptor);
            if (format == 0) {
                throw failure("ChoosePixelFormat");
            }
            check((int) call(SET_PIXEL_FORMAT, deviceContext, format, descriptor), "SetPixelFormat");

            MemorySegment legacy = created((MemorySegment) call(CREATE_CONTEXT, deviceContext), "wglCreateContext");
            try {
                check((int) call(MAKE_CURRENT, deviceContext, legacy), "wglMakeCurrent");
                MemorySegment createContextAttributes = procAddress("wglCreateContextAttribsARB");
                if (createContextAttributes.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("The driver offers no core-profile OpenGL (wglCreateContextAttribsARB missing)");
                }
                MemorySegment attributes = arena.allocateFrom(JAVA_INT,
                        WGL_CONTEXT_MAJOR_VERSION_ARB, 4,
                        WGL_CONTEXT_MINOR_VERSION_ARB, 1,
                        WGL_CONTEXT_PROFILE_MASK_ARB, WGL_CONTEXT_CORE_PROFILE_BIT_ARB,
                        0);
                MethodHandle createCore = downcall(createContextAttributes, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
                context = created((MemorySegment) call(createCore, deviceContext, MemorySegment.NULL, attributes),
                        "wglCreateContextAttribsARB");
            } finally {
                call(MAKE_CURRENT, MemorySegment.NULL, MemorySegment.NULL);
                call(DELETE_CONTEXT, legacy);
            }
        }
    }

    @Override
    public void makeCurrent() {
        check((int) call(MAKE_CURRENT, deviceContext, context), "wglMakeCurrent");
    }

    /**
     * {@code wglGetProcAddress} only knows what came after OpenGL 1.1; the 1.1
     * functions come straight from {@code opengl32.dll}. Some drivers answer an
     * unknown name with 1, 2, 3 or -1 instead of null, so those count as missing too.
     */
    @Override
    public MemorySegment function(String name) {
        MemorySegment address = procAddress(name);
        long raw = address.address();
        if (raw == 0 || raw == 1 || raw == 2 || raw == 3 || raw == -1) {
            return OPENGL32.find(name).orElseThrow(() -> new IllegalStateException("OpenGL function missing: " + name));
        }
        return address;
    }

    private static MemorySegment procAddress(String name) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) call(GET_PROC_ADDRESS, arena.allocateFrom(name));
        }
    }

    @Override
    public void close() {
        call(MAKE_CURRENT, MemorySegment.NULL, MemorySegment.NULL);
        call(DELETE_CONTEXT, context);
        call(RELEASE_DC, window, deviceContext);
        call(DESTROY_WINDOW, window);
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
