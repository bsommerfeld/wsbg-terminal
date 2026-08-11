package de.bsommerfeld.wsbg.terminal.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Timer;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Keeps every {@link GraphicsConfiguration} the JVM ever hands out alive, so
 * the JDK never tears down a Metal graphics config — because tearing one down
 * kills the process.
 *
 * <h2>The crash</h2>
 * Unplugging a monitor (and, less reliably, a display sleep/wake) killed the
 * terminal with a native SIGSEGV, three times on record — 02.07. after 1h42,
 * 15.07. after 1h17, 09.08. after 2 minutes — every single time with the exact
 * same frame:
 *
 * <pre>
 * C  objc_release+0x8                      &lt;- SIGSEGV, x0=0x2121212121212121
 * C  -[MTLContext dealloc]+0x40
 * C  MTLGC_DestroyMTLGraphicsConfig+0x20
 * C  Java_sun_java2d_metal_MTLRenderQueue_flushBuffer
 * J  sun.java2d.metal.MTLRenderQueue.flushBuffer()   [thread "Java2D Queue Flusher"]
 * </pre>
 *
 * An already-freed Objective-C object gets released a second time while the
 * JDK dismantles a Metal graphics config. The bug is in the JDK's Metal
 * pipeline (seen on JDK 25+37 / macOS 26 / Apple Silicon), not in our code —
 * we can only make sure that teardown never runs.
 *
 * <h2>Why holding a reference is enough</h2>
 * Read from the JDK 25 sources, not guessed:
 * <ul>
 *   <li>{@code MTLGraphicsConfig} registers its teardown exactly once, as
 *       {@code Disposer.addRecord(disposerReferent, new MTLGCDisposerRecord(..))},
 *       where {@code disposerReferent} is an INSTANCE field of the config.</li>
 *   <li>{@code MTLRenderQueue.disposeGraphicsConfig(..)} — the call that queues
 *       the fatal {@code DISPOSE_CONFIG} — has exactly ONE caller: that disposer
 *       record. There is no explicit teardown path anywhere else.</li>
 * </ul>
 * So a strongly reachable config keeps its referent reachable, the disposer
 * never fires, and the native destroy never happens. Metal stays on; the
 * OpenGL fallback (see {@code AppMain.configureAwtSystemProperties}) would cost
 * ~15ms on the EDT per full-window OSR blit.
 *
 * <h2>Why we sweep ALL screens instead of pinning the window's config</h2>
 * The config is not dropped when a display changes: {@code CGraphicsDevice
 * .displayChanged()} leaves it alone (the JDK source literally says
 * {@code //TODO configs?}). It dies one level up — {@code CGraphicsEnvironment
 * .initDevices()} rebuilds the device map on a display change, and a device
 * that is NOT reused (the monitor you just unplugged) falls out of the map and
 * is collected, taking its Metal config with it.
 *
 * <p>
 * That is the config of the display that DISAPPEARS — which is generally not
 * the one our window renders on. Pinning what the browser panel draws on would
 * therefore have missed the very case that crashed. Hence: pin the config of
 * every attached screen, and re-sweep, so a display is already pinned from an
 * earlier pass by the time it is unplugged.
 *
 * <h2>The price, stated plainly</h2>
 * A pinned config keeps its native Metal context alive forever. The set grows
 * with the number of DISTINCT configs the JVM creates — i.e. per display
 * add/remove, not per unit of time. A handful per day of hot-plugging is
 * irrelevant next to a hard crash.
 *
 * <p>
 * Known gap: a display that appears AND disappears entirely between two sweeps
 * is never pinned and would still crash. Bounded by {@link #SWEEP_MS}.
 *
 * <p>
 * EDT-confined: {@link #install()} is called on the EDT and the sweep runs on a
 * Swing {@link Timer}, so the set needs no synchronization.
 */
final class GraphicsConfigPin {

    private static final Logger LOG = LoggerFactory.getLogger(GraphicsConfigPin.class);

    /** Sweep interval. Cheap enough to be frequent: on macOS {@code getScreenDevices()}
     *  is a read off an already-built map, no native call per sweep. */
    private static final int SWEEP_MS = 5_000;

    /** Identity-based: two configs are the same pin only if they are the SAME object. */
    private static final Set<GraphicsConfiguration> PINNED =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private GraphicsConfigPin() {
    }

    /** Pins what is attached now and keeps sweeping. Call once, on the EDT, after the window is up. */
    static void install() {
        if (!isMetalPipelineActive()) {
            LOG.debug("Java2D: graphics-config pin not needed (Metal pipeline inactive).");
            return;
        }
        sweep();
        Timer timer = new Timer(SWEEP_MS, e -> sweep());
        timer.setRepeats(true);
        timer.start();
        LOG.info("Java2D: pinning graphics configurations (guard against the Metal teardown crash on display change).");
    }

    private static void sweep() {
        try {
            GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
            for (GraphicsDevice device : env.getScreenDevices()) {
                for (GraphicsConfiguration config : device.getConfigurations()) {
                    if (config != null && PINNED.add(config)) {
                        LOG.info("Java2D: pinned graphics config of screen {} ({} pinned in total).",
                                device.getIDstring(), PINNED.size());
                    }
                }
            }
        } catch (Throwable t) {
            // A failed sweep must never take the app with it — the worst case is
            // that we are back to the JDK's crash, not a new one of our own.
            LOG.warn("Java2D: graphics-config sweep failed — teardown crash guard is degraded.", t);
        }
    }

    /**
     * True when the crashing pipeline is the one actually in use: macOS, and the
     * OpenGL opt-out in {@code AppMain} not taken. Elsewhere the pin would only
     * hold objects for nothing.
     */
    private static boolean isMetalPipelineActive() {
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        return mac && !"false".equalsIgnoreCase(System.getProperty("sun.java2d.metal"));
    }
}
