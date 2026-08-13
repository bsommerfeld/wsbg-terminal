package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import de.bsommerfeld.wsbg.terminal.ui.bridge.ArchiveQueryBridge;
import de.bsommerfeld.wsbg.terminal.ui.bridge.ChangelogBridge;
import de.bsommerfeld.wsbg.terminal.ui.bridge.CommandBridge;
import de.bsommerfeld.wsbg.terminal.ui.bridge.LauncherUpdateService;
import de.bsommerfeld.wsbg.terminal.ui.bridge.SettingsBridge;
import de.bsommerfeld.wsbg.terminal.ui.bridge.UninstallService;
import de.bsommerfeld.wsbg.terminal.ui.bridge.UpdateService;

/**
 * The inbound page→Java bridges (each owns its own {@code hub.on(...)} handlers).
 * All eager so their handlers exist before the first page loads.
 */
final class BridgeModule extends AbstractModule {

    @Override
    protected void configure() {
        // CommandBridge wires inbound window-control messages.
        bind(CommandBridge.class).asEagerSingleton();
        // Archive layer: search/byTicker queries + scroll-back pagination over
        // the permanent HeadlineArchive / the archive-seeded wire window.
        bind(ArchiveQueryBridge.class).asEagerSingleton();
        // Settings view backend: persists the config-backed preferences
        // (headline mode, language, auto-update) and pushes the current snapshot
        // on client open.
        bind(SettingsBridge.class).asEagerSingleton();
        // The Settings view's "Deinstallieren": one-click full removal
        // (launcher + data dir) via a detached OS-specific cleanup.
        bind(UninstallService.class).asEagerSingleton();
        // In-app update indicator (titlebar green button) + relaunch, the
        // counterpart to the launcher's auto-update opt-out.
        bind(UpdateService.class).asEagerSingleton();
        // Launcher-hull renewal indicator (titlebar amber button): one-time
        // guided reinstall for launchers too old to update themselves.
        // Deliberately separate from UpdateService — both may show at once.
        bind(LauncherUpdateService.class).asEagerSingleton();
        // "Was hat sich geändert" overlay: pushes the GitHub release notes
        // once after a fresh update (installed version ≠ last seen).
        bind(ChangelogBridge.class).asEagerSingleton();
        // The collector clock, ARMED (user decision 2026-08-12). Every
        // world-signal collector starts at boot; the basin stays RAM-only by
        // design, so what it holds is a sliding window of the last hours, not
        // an archive. LAST in the install order — the clock must not start
        // before the rest of the pipeline is wired.
        bind(WebWorldStarter.class).asEagerSingleton();
        // The debug data interface — DEV-ONLY, the WebWorldStarter pattern:
        // without this binding no "debug" handler exists on the PushHub and a
        // shipped build simply never answers the type. The registries behind
        // it are additionally write-guarded by the JIT-foldable Debug.ENABLED,
        // so even a mis-bound release would collect nothing.
        if (de.bsommerfeld.wsbg.terminal.core.debug.DevMode.active()) {
            bind(de.bsommerfeld.wsbg.terminal.ui.bridge.DebugBridge.class).asEagerSingleton();
        }
    }
}
