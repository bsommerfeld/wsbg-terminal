package de.bsommerfeld.wsbg.terminal.ui;

import com.google.inject.Guice;
import com.google.inject.Injector;
import de.bsommerfeld.wsbg.terminal.agent.AgentCoordinator;
import de.bsommerfeld.wsbg.terminal.agent.OllamaServerManager;
import de.bsommerfeld.wsbg.terminal.agent.PassiveMonitorService;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.ui.config.AppModule;
import de.bsommerfeld.wsbg.terminal.ui.web.AssetServer;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;

/**
 * Process entry point. Bootstraps Guice, starts the local HTTP+WebSocket
 * servers, then opens the JCEF window. No JavaFX involvement.
 *
 * <p>
 * Boot order matters: assets and push hub must be listening before the
 * browser navigates, otherwise the first request races.
 */
public final class AppMain {

    private static final Logger LOG = LoggerFactory.getLogger(AppMain.class);

    public static void main(String[] args) {
        // Must be set before ANY AWT/Toolkit init. Stops AWT from erasing
        // the heavyweight JCEF OSR GLCanvas background to the frame colour
        // on every live-resize step — that erase-then-repaint is the flash
        // that makes continuous resizing flicker. With it off, the canvas
        // keeps the last Chromium frame until the next one is presented.
        System.setProperty("sun.awt.noerasebackground", "true");

        // Force the OpenGL Java2D pipeline (disable Metal). The macOS Metal
        // pipeline crashes natively in MTLGC_DestroyMTLGraphicsConfig → -[MTLContext
        // dealloc] → objc_release on the "Java2D Queue Flusher" thread when a
        // graphics config is torn down (display sleep/wake / reconfig) — hit live
        // after ~3h uptime (SIGSEGV, hs_err). Our software-OSR browser paints
        // BufferedImages through Java2D continuously, which exercises that path
        // hard. Must be set before ANY AWT/Toolkit init. (OpenGL is deprecated on
        // macOS but stable here; revisit if a JDK fixes the Metal teardown.)
        System.setProperty("sun.java2d.metal", "false");

        System.setProperty("apple.awt.application.name", "WSBG Terminal");
        System.setProperty("apple.laf.useScreenMenuBar", "true");

        // Single-instance gate: a second double-click should raise the
        // running terminal, not start a parallel one with duplicate
        // ports, sockets, and a second OS dock icon.
        //
        // We claim the lock BEFORE constructing the Guice injector so
        // a rejected second instance pays nothing — no model load, no
        // Ollama probe, just a single connect-and-exit.
        BrowserWindow[] windowRef = new BrowserWindow[1];
        boolean isFirst = SingleInstance.claim(() -> {
            if (windowRef[0] != null) windowRef[0].raise();
        });
        if (!isFirst) {
            LOG.info("Another instance already running — raised it and exiting.");
            SingleInstance.pingExisting();
            System.exit(0);
        }

        LOG.info("Bootstrapping WSBG Terminal");
        Injector injector = Guice.createInjector(new AppModule());

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
        // The JVM hook stays as a fallback for SIGTERM/kill; SERVICES_DOWN
        // makes the work idempotent so it never runs twice.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownServices(injector);
            safeStop(() -> injector.getInstance(CefHost.class).dispose(), "CefHost");
        }, "wsbg-shutdown"));

        String entryUrl = String.format("http://127.0.0.1:%d/?ws=%d",
                assetServer.port(), pushHub.port());
        LOG.info("Entry URL: {}", entryUrl);

        SwingUtilities.invokeLater(() -> {
            BrowserWindow window = injector.getInstance(BrowserWindow.class);
            window.setOnClose(() -> shutdownServices(injector));
            window.open(entryUrl);
            windowRef[0] = window;  // hand to the raise listener
        });
    }

    private static final java.util.concurrent.atomic.AtomicBoolean SERVICES_DOWN =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Stops every service that must die with the app — most importantly the
     * spawned Ollama process — but NOT CefHost (the caller drives the CEF
     * teardown afterwards). Idempotent: the first caller wins, later calls
     * (e.g. the JVM shutdown hook after the window already ran it) are no-ops.
     */
    private static void shutdownServices(Injector injector) {
        if (!SERVICES_DOWN.compareAndSet(false, true)) return;
        LOG.info("Shutting down services...");
        // Stop the scan loop first so no fresh embedding/vision/cluster work is
        // submitted while the services it depends on are torn down below.
        safeStop(() -> injector.getInstance(PassiveMonitorService.class).shutdown(), "PassiveMonitorService");
        safeStop(() -> injector.getInstance(AgentCoordinator.class).shutdown(), "AgentCoordinator");
        safeStop(() -> injector.getInstance(RedditRepository.class).shutdown(), "RedditRepository");
        safeStop(() -> injector.getInstance(AgentRepository.class).shutdown(), "AgentRepository");
        safeStop(() -> injector.getInstance(OllamaServerManager.class).shutdown(), "OllamaServerManager");
        safeStop(() -> injector.getInstance(TimeTracker.class).shutdown(), "TimeTracker");
        safeStop(() -> injector.getInstance(PushHub.class).stop(), "PushHub");
        safeStop(() -> injector.getInstance(AssetServer.class).stop(), "AssetServer");
        safeStop(SingleInstance::release, "SingleInstance");
    }

    private static void safeStop(Runnable r, String name) {
        try {
            r.run();
        } catch (Throwable t) {
            LOG.warn("Shutdown step '{}' failed: {}", name, t.getMessage());
        }
    }

    private AppMain() {}
}
