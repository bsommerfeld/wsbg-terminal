package de.bsommerfeld.wsbg.orb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static de.bsommerfeld.wsbg.orb.Native.call;
import static de.bsommerfeld.wsbg.orb.Native.downcall;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * macOS: a CGL context against {@code OpenGL.framework}. CGL needs no window, so
 * the context can be made current on any thread; GLFW would insist on the main
 * thread, which JavaFX already owns.
 */
final class CGL implements GL {

    private static final int kCGLPFAAccelerated = 73;
    private static final int kCGLPFAAllowOfflineRenderers = 96;
    private static final int kCGLPFAOpenGLProfile = 99;
    private static final int kCGLOGLPVersion_3_2_Core = 0x3200; // yields the newest core profile, 4.1 on macOS

    /*
     * By name, not by Path: since macOS 11 system frameworks live only in the dyld
     * shared cache, so there is no file to find - dlopen resolves the name anyway.
     */
    private static final SymbolLookup LIBRARY = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/OpenGL.framework/OpenGL", Arena.global());

    private static final MethodHandle CHOOSE_PIXEL_FORMAT = downcall(LIBRARY, "CGLChoosePixelFormat", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle CREATE_CONTEXT = downcall(LIBRARY, "CGLCreateContext", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle DESTROY_PIXEL_FORMAT = downcall(LIBRARY, "CGLDestroyPixelFormat", JAVA_INT, ADDRESS);
    private static final MethodHandle SET_CURRENT_CONTEXT = downcall(LIBRARY, "CGLSetCurrentContext", JAVA_INT, ADDRESS);
    private static final MethodHandle DESTROY_CONTEXT = downcall(LIBRARY, "CGLDestroyContext", JAVA_INT, ADDRESS);

    private final MemorySegment context;

    /** Creates a windowless, hardware-accelerated core-profile context. */
    CGL() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment attributes = arena.allocateFrom(JAVA_INT,
                    kCGLPFAOpenGLProfile, kCGLOGLPVersion_3_2_Core,
                    kCGLPFAAccelerated, kCGLPFAAllowOfflineRenderers,
                    0);
            MemorySegment pixelFormat = arena.allocate(ADDRESS);
            MemorySegment count = arena.allocate(JAVA_INT);
            check((int) call(CHOOSE_PIXEL_FORMAT, attributes, pixelFormat, count), "CGLChoosePixelFormat");
            MemorySegment format = pixelFormat.get(ADDRESS, 0);
            MemorySegment created = arena.allocate(ADDRESS);
            check((int) call(CREATE_CONTEXT, format, MemorySegment.NULL, created), "CGLCreateContext");
            call(DESTROY_PIXEL_FORMAT, format);
            context = created.get(ADDRESS, 0);
        }
    }

    @Override
    public void makeCurrent() {
        check((int) call(SET_CURRENT_CONTEXT, context), "CGLSetCurrentContext");
    }

    /** The framework exports every GL function it has, so a plain symbol lookup finds them all. */
    @Override
    public MemorySegment function(String name) {
        return LIBRARY.find(name).orElseThrow(() -> new IllegalStateException("OpenGL function missing: " + name));
    }

    @Override
    public void close() {
        call(SET_CURRENT_CONTEXT, MemorySegment.NULL);
        call(DESTROY_CONTEXT, context);
    }

    private static void check(int error, String what) {
        if (error != 0) {
            throw new IllegalStateException(what + " failed with CGL error " + error);
        }
    }
}
