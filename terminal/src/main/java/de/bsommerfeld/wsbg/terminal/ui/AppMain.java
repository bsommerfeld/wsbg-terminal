package de.bsommerfeld.wsbg.terminal.ui;

import com.google.inject.Guice;
import com.google.inject.Injector;
import de.bsommerfeld.wsbg.terminal.ui.config.AppModule;
import de.bsommerfeld.wsbg.terminal.ui.web.AssetServer;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;

/**
 * Process entry point. Bootstraps Guice, starts the local HTTP+WebSocket
 * servers, then opens the JCEF window. No JavaFX involvement. The teardown
 * (service shutdown + the three exit paths) lives in {@link AppLifecycle}; this
 * class keeps only the two {@code public static} cross-class entry points as
 * thin delegators.
 *
 * <p>
 * Boot order matters: assets and push hub must be listening before the
 * browser navigates, otherwise the first request races.
 */
public final class AppMain {

    private static final Logger LOG = LoggerFactory.getLogger(AppMain.class);

    /** The app teardown owner; set once in {@link #main} before any exit path can fire. */
    private static volatile AppLifecycle LIFECYCLE;

    public static void main(String[] args) {
        configureAwtSystemProperties();

        // Single-instance gate: a second double-click should raise the
        // running terminal, not start a parallel one with duplicate
        // ports, sockets, and a second OS dock icon.
        //
        // We claim the lock BEFORE constructing the Guice injector so
        // a rejected second instance pays nothing — no model load, no
        // Ollama probe, just a single connect-and-exit.
        //
        // The same channel carries the launcher's quit request: when the
        // launcher found an update it must replace the very files this
        // process runs off, so it closes us first and starts the fresh
        // build itself afterwards. We shut down exactly like a window
        // close — services first, so Ollama dies with us.
        BrowserWindow[] windowRef = new BrowserWindow[1];
        boolean isFirst = SingleInstance.claim(() -> {
            if (windowRef[0] != null) windowRef[0].raise();
        }, AppMain::quitForLauncherUpdate);
        if (!isFirst) {
            LOG.info("Another instance already running — raised it and exiting.");
            SingleInstance.pingExisting();
            System.exit(0);
        }

        LOG.info("Bootstrapping WSBG Terminal");
        Injector injector = Guice.createInjector(new AppModule());
        AppLifecycle lifecycle = new AppLifecycle(injector);
        LIFECYCLE = lifecycle; // for the in-app update relaunch / uninstall entry points

        AssetServer assetServer = injector.getInstance(AssetServer.class);
        PushHub pushHub = injector.getInstance(PushHub.class);
        try {
            assetServer.start();
            pushHub.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start local servers", e);
        }

        // The window's close gesture runs the graceful service shutdown
        // SYNCHRONOUSLY before it tears CEF down. This matters because CEF's
        // native shutdown (N_Shutdown) hard-exits the process — it never
        // reaches setState(TERMINATED), so CefHost's System.exit(0) and any
        // JVM shutdown hook do NOT run. If we left Ollama's stop to the hook,
        // the spawned `ollama serve` would be orphaned (the model keeps
        // running in the background after the window is gone). Running it up
        // front, before cefHost.dispose(), guarantees the child dies with us.
        // The JVM hook stays as a fallback for SIGTERM/kill; it is idempotent
        // so it never runs the work twice.
        lifecycle.installShutdownHook();

        String entryUrl = String.format("http://127.0.0.1:%d/?ws=%d",
                assetServer.port(), pushHub.port());
        LOG.info("Entry URL: {}", entryUrl);

        SwingUtilities.invokeLater(() -> {
            BrowserWindow window = injector.getInstance(BrowserWindow.class);
            window.setOnClose(lifecycle.onWindowClose());
            // The close veto, consulted before the teardown.
            window.setCloseGuard(lifecycle.closeGuard());
            window.open(entryUrl);  // brings JCEF up SYNCHRONOUSLY on this (EDT) thread
            windowRef[0] = window;  // hand to the raise listener

            // Unplugging a monitor used to kill the process outright (native
            // SIGSEGV in the JDK's Metal graphics-config teardown). Armed here,
            // once there is a window: it needs a live AWT, and the screens it
            // pins must be pinned BEFORE one of them is pulled.
            GraphicsConfigPin.install();

            // CEF's native init is now done, on the AWT thread, via the window —
            // so the hidden-browser fetchers (FX, Fear&Greed) may finally run.
            // As eager singletons they used to poll from their own threads during
            // DI construction, before the window, and the first fetch would
            // trigger JCEF init off the AWT thread, racing the EDT init and
            // deadlocking the window (no UI, unkillable JVM).
            //
            // They — and the rest of the workload — now wait one step longer
            // still: the gate opens when the startup intro is off the screen, so
            // the animation gets an idle machine instead of a model load. Armed
            // here, because only now is there a page that can report in.
            new BootGate(lifecycle::startBackgroundWork).arm(pushHub);
        });
    }

    /**
     * Boot-time AWT/Java2D system properties. Must all be set before ANY
     * AWT/Toolkit init — hence the first thing {@link #main} does.
     */
    private static void configureAwtSystemProperties() {
        // Stops AWT from erasing the heavyweight JCEF OSR GLCanvas background to
        // the frame colour on every live-resize step — that erase-then-repaint is
        // the flash that makes continuous resizing flicker. With it off, the
        // canvas keeps the last Chromium frame until the next one is presented.
        System.setProperty("sun.awt.noerasebackground", "true");

        // Java2D pipeline choice (macOS). Our software-OSR browser blits EVERY
        // Chromium frame through Java2D, so the pipeline is on the hot path for
        // the whole UI. Metal (the Apple-Silicon default) accelerates that; the
        // deprecated OpenGL pipeline is markedly slower and makes scroll/click/
        // animations feel laggy. So we keep Metal by DEFAULT.
        //
        // Metal DOES carry a native crash: the JDK over-releases an Objective-C
        // object while destroying a Metal graphics config, and the process dies
        // (objc_release in MTLGC_DestroyMTLGraphicsConfig, on the Java2D Queue
        // Flusher). Confirmed, three identical hs_err logs — 02.07., 15.07.,
        // 09.08. — and the trigger is a DISPLAY CHANGE, not uptime: the last one
        // hit 2 minutes in, when a monitor was unplugged. NOT "after hours" and
        // NOT external, as this comment used to claim.
        //
        // We do not pay for that with the pipeline: GraphicsConfigPin (armed in
        // main) keeps the configs alive so the JDK's teardown never runs. Metal
        // therefore stays ON by default — the OpenGL fallback makes every
        // full-window OSR blit ~15ms on the EDT.
        //
        // The switch survives as an OPT-IN (-Dwsbg.j2d.opengl=true or
        // WSBG_J2D_OPENGL=true) — an escape hatch should the Metal teardown find
        // a second way to die that a pinned config cannot close.
        if (Boolean.getBoolean("wsbg.j2d.opengl")
                || "true".equalsIgnoreCase(System.getenv("WSBG_J2D_OPENGL"))) {
            System.setProperty("sun.java2d.metal", "false");
            LOG.info("Java2D: OpenGL pipeline forced (Metal disabled) via opt-in flag.");
        }

        System.setProperty("apple.awt.application.name", "WSBG Terminal");
        System.setProperty("apple.laf.useScreenMenuBar", "true");
    }

    /**
     * Closes the app cleanly and relaunches the launcher to apply a pending
     * update — the titlebar's green "update now" button (UpdateService).
     * Delegates to {@link AppLifecycle}; kept as a {@code public static} entry
     * point for that cross-class consumer.
     */
    public static void relaunchForUpdate() {
        LIFECYCLE.relaunchForUpdate();
    }

    /**
     * Closes the app cleanly because the launcher wants to apply an update
     * over our install. Called from the single-instance listener thread; the
     * teardown itself hops to the EDT. Before the lifecycle exists (a quit
     * arriving in the first moments of boot) there is nothing to tear down —
     * exit straight away so the launcher isn't left waiting.
     */
    private static void quitForLauncherUpdate() {
        AppLifecycle lifecycle = LIFECYCLE;
        if (lifecycle == null) {
            LOG.info("Launcher asked us to quit during boot — exiting immediately.");
            System.exit(0);
            return;
        }
        LOG.info("Launcher asked us to quit for an update — shutting down.");
        lifecycle.quitForLauncherUpdate();
    }

    /**
     * Closes the app cleanly and hands removal to a detached OS process — the
     * Settings view's "Deinstallieren" button (UninstallService). Delegates to
     * {@link AppLifecycle}; kept as a {@code public static} entry point for that
     * cross-class consumer.
     */
    public static void uninstallAndExit(java.util.List<String> detachedCleanup) {
        LIFECYCLE.uninstallAndExit(detachedCleanup);
    }

    private AppMain() {}
}
