package de.bsommerfeld.wsbg.terminal.ui;

import com.google.inject.Guice;
import com.google.inject.Injector;
import de.bsommerfeld.wsbg.terminal.agent.AgentCoordinator;
import de.bsommerfeld.wsbg.terminal.agent.ClusterRebalancer;
import de.bsommerfeld.wsbg.terminal.agent.OllamaServerManager;
import de.bsommerfeld.wsbg.terminal.core.config.ApplicationMode;
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

        LOG.info("Bootstrapping WSBG Terminal (mode={})", ApplicationMode.get());
        Injector injector = Guice.createInjector(new AppModule());

        AssetServer assetServer = injector.getInstance(AssetServer.class);
        PushHub pushHub = injector.getInstance(PushHub.class);
        try {
            assetServer.start();
            pushHub.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start local servers", e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(injector), "wsbg-shutdown"));

        String entryUrl = String.format("http://127.0.0.1:%d/?ws=%d",
                assetServer.port(), pushHub.port());
        LOG.info("Entry URL: {}", entryUrl);

        SwingUtilities.invokeLater(() -> {
            BrowserWindow window = injector.getInstance(BrowserWindow.class);
            window.open(entryUrl);
            windowRef[0] = window;  // hand to the raise listener
        });
    }

    private static void shutdown(Injector injector) {
        LOG.info("Shutting down services...");
        safeStop(() -> injector.getInstance(AgentCoordinator.class).shutdown(), "AgentCoordinator");
        safeStop(() -> injector.getInstance(ClusterRebalancer.class).shutdown(), "ClusterRebalancer");
        safeStop(() -> injector.getInstance(RedditRepository.class).shutdown(), "RedditRepository");
        safeStop(() -> injector.getInstance(AgentRepository.class).shutdown(), "AgentRepository");
        safeStop(() -> injector.getInstance(OllamaServerManager.class).shutdown(), "OllamaServerManager");
        safeStop(() -> injector.getInstance(UserSessionTracker.class).shutdown(), "UserSessionTracker");
        safeStop(() -> injector.getInstance(PushHub.class).stop(), "PushHub");
        safeStop(() -> injector.getInstance(AssetServer.class).stop(), "AssetServer");
        safeStop(() -> injector.getInstance(CefHost.class).dispose(), "CefHost");
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
