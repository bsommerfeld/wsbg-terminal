package de.bsommerfeld.wsbg.terminal.ui;

import com.google.inject.Injector;
import de.bsommerfeld.wsbg.terminal.agent.AgentCoordinator;
import de.bsommerfeld.wsbg.terminal.agent.EditorialPipeline;
import de.bsommerfeld.wsbg.terminal.agent.MarketMemoryService;
import de.bsommerfeld.wsbg.terminal.agent.OllamaServerManager;
import de.bsommerfeld.wsbg.terminal.agent.PassiveMonitorService;
import de.bsommerfeld.wsbg.terminal.agent.WeatherReportService;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.ui.web.AssetServer;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the app's teardown: the injector reference, the idempotent
 * service-shutdown, and the three exit orchestrations (shutdown-hook,
 * in-app-update relaunch, uninstall). Extracted from {@link AppMain}, which
 * keeps thin {@code public static} delegators for the two cross-class entry
 * points ({@code relaunchForUpdate}/{@code uninstallAndExit}) consumed by
 * {@code UpdateService}/{@code UninstallService}.
 *
 * <p>ORDER IS LOAD-BEARING on every exit path: stop services (this kills the
 * spawned Ollama and releases the single-instance lock) → run the path's spawn
 * step → tear CEF down → reap the Chromium helpers → {@code System.exit(0)}.
 */
final class AppLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(AppLifecycle.class);

    private final Injector injector;
    private final AtomicBoolean servicesDown = new AtomicBoolean(false);

    AppLifecycle(Injector injector) {
        this.injector = injector;
    }

    /**
     * Registers the JVM shutdown hook — the fallback for SIGTERM/kill, which the
     * window-close and app-quit paths never reach. {@link #shutdownServices()} is
     * idempotent so it never double-runs when the window path already ran it.
     */
    void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownServices();
            safeStop(() -> injector.getInstance(CefHost.class).dispose(), "CefHost");
            // A bare SIGTERM/kill reaches only this hook; cefApp.dispose() is
            // async, so reap the Chromium helpers before the JVM exits — else
            // they orphan on Windows (no POSIX orphan reaping).
            safeStop(CefHost::reapHelperProcesses, "reapHelperProcesses");
        }, "wsbg-shutdown"));
    }

    /** The window's close callback ({@code BrowserWindow.setOnClose}): stop services only. */
    Runnable onWindowClose() {
        return this::shutdownServices;
    }

    /**
     * Closes the app cleanly and relaunches the launcher to apply a pending
     * update — the action behind the titlebar's green "update now" button
     * (UpdateService). Stop services FIRST (this releases the single-instance
     * lock, so the relaunched launcher won't just detect us and raise the old
     * window), THEN spawn the launcher with {@code --force-update} (so it updates
     * even though auto-update is off), THEN tear CEF down and exit. Runs on the
     * EDT to match the window-close path. Only ever reached when
     * {@code WSBG_LAUNCHER_EXECUTABLE} is set (UpdateService keeps the button
     * hidden otherwise).
     */
    void relaunchForUpdate() {
        exitAfter(this::spawnLauncherForceUpdate);
    }

    /**
     * Closes the app cleanly and hands the actual removal to a detached OS
     * process — the action behind the Settings view's "Deinstallieren" button
     * (UninstallService, which builds the platform-specific command). Same order
     * as {@link #relaunchForUpdate()}: stop services first, spawn the detached
     * cleanup (it sleeps before acting, so it always runs against a fully dead
     * install — anything earlier and our own snapshot writes or the CEF cache
     * flush would re-create files the wipe just removed), then tear CEF down and
     * exit.
     */
    void uninstallAndExit(List<String> detachedCleanup) {
        exitAfter(() -> spawnDetachedCleanup(detachedCleanup));
    }

    /** The shared exit orchestration: services → spawn step → CEF teardown → reap → exit. */
    private void exitAfter(Runnable spawnStep) {
        SwingUtilities.invokeLater(() -> {
            shutdownServices();
            spawnStep.run();
            safeStop(() -> injector.getInstance(CefHost.class).dispose(), "CefHost");
            // Reap the Chromium helpers before exiting so they don't orphan on
            // Windows. The reaper matches on the "jcef" command name only, so it
            // never touches a sibling we just spawned above.
            safeStop(CefHost::reapHelperProcesses, "reapHelperProcesses");
            System.exit(0);
        });
    }

    private void spawnLauncherForceUpdate() {
        String exe = System.getenv("WSBG_LAUNCHER_EXECUTABLE");
        if (exe == null || exe.isBlank()) {
            LOG.warn("Update relaunch requested but WSBG_LAUNCHER_EXECUTABLE is unset — cannot relaunch.");
            return;
        }
        try {
            new ProcessBuilder(exe, "--force-update")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            LOG.info("Relaunching launcher for update: {} --force-update", exe);
        } catch (Exception e) {
            LOG.error("Failed to relaunch launcher for update: {}", e.getMessage());
        }
    }

    private void spawnDetachedCleanup(List<String> detachedCleanup) {
        try {
            new ProcessBuilder(detachedCleanup)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            LOG.info("Spawned detached uninstall cleanup.");
        } catch (Exception e) {
            LOG.error("Failed to spawn uninstall cleanup: {}", e.getMessage());
        }
    }

    /**
     * Stops every service that must die with the app — most importantly the
     * spawned Ollama process — but NOT CefHost (the caller drives the CEF
     * teardown afterwards). Idempotent: the first caller wins, later calls
     * (e.g. the JVM shutdown hook after the window already ran it) are no-ops.
     */
    void shutdownServices() {
        if (!servicesDown.compareAndSet(false, true)) return;
        LOG.info("Shutting down services...");
        // Stop the scan loop first so no fresh embedding/vision/cluster work is
        // submitted while the services it depends on are torn down below.
        safeStop(() -> injector.getInstance(PassiveMonitorService.class).shutdown(), "PassiveMonitorService");
        safeStop(() -> injector.getInstance(WeatherReportService.class).shutdown(), "WeatherReportService");
        safeStop(() -> injector.getInstance(MarketMemoryService.class).shutdown(), "MarketMemoryService");
        safeStop(() -> injector.getInstance(EditorialPipeline.class).shutdown(), "EditorialPipeline");
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
}
