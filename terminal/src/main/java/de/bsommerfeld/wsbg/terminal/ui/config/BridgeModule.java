package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import de.bsommerfeld.wsbg.terminal.ui.bridge.ArchiveQueryBridge;
import de.bsommerfeld.wsbg.terminal.ui.bridge.ChangelogBridge;
import de.bsommerfeld.wsbg.terminal.ui.bridge.CommandBridge;
import de.bsommerfeld.wsbg.terminal.ui.bridge.SettingsBridge;
import de.bsommerfeld.wsbg.terminal.ui.bridge.UninstallService;
import de.bsommerfeld.wsbg.terminal.ui.bridge.UpdateService;
import de.bsommerfeld.wsbg.terminal.ui.bridge.WatchlistBridge;
import de.bsommerfeld.wsbg.terminal.ui.bridge.WeatherReportBridge;

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
        // "Was hat sich geändert" overlay: pushes the GitHub release notes
        // once after a fresh update (installed version ≠ last seen).
        bind(ChangelogBridge.class).asEagerSingleton();
        // AI watchlist: add/remove/suggestions inbound, the standing dossiers
        // outbound (also constructs the WatchlistService + its revision loop).
        bind(WatchlistBridge.class).asEagerSingleton();
        // Wetterbericht widget backend: schedule state + report history out,
        // report-time setting in.
        bind(WeatherReportBridge.class).asEagerSingleton();
        // KI-DD backend: generate/get/export-pdf inbound, generation progress +
        // the archived reports outbound (also constructs the DeepDiveService).
        bind(de.bsommerfeld.wsbg.terminal.ui.bridge.DeepDiveBridge.class).asEagerSingleton();
    }
}
