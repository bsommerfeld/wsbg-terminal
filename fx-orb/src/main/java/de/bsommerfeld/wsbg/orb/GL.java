package de.bsommerfeld.wsbg.orb;

import java.lang.foreign.MemorySegment;
import java.util.Locale;

/**
 * A windowless OpenGL 4.1 core context of the platform's own flavour - CGL on
 * macOS, WGL on Windows, EGL on Linux - bound through the FFM API, no native
 * library of our own. It is all that differs between the platforms: the GL calls
 * themselves are the same everywhere and live in {@link GLFunctions}.
 * <p>
 * Every call must come from the thread that created the context.
 */
sealed interface GL extends AutoCloseable permits CGL, WGL, EGL {

    /** Creates the context for the platform the JVM runs on. */
    static GL create() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.startsWith("mac")) {
            return new CGL();
        }
        if (os.startsWith("windows")) {
            return new WGL();
        }
        return new EGL();
    }

    /** Makes this context current on the calling thread. */
    void makeCurrent();

    /**
     * The entry point of a GL function, resolved for this context - on Windows
     * and Linux the address can depend on the context, so it has to be current.
     *
     * @throws IllegalStateException if the driver does not offer the function
     */
    MemorySegment function(String name);

    /** Releases the context; it must not be used afterwards. */
    @Override
    void close();
}
