package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.impl.WebLifecycle;

/**
 * Boots the web world — the ONE arming point for the collector clock. Bound
 * eagerly in {@code BridgeModule}, LAST in the install order so the clock
 * starts only once the whole pipeline is wired. First passes are staggered
 * 5-90 s; a BROWSER-first source simply rides its DIRECT fallback until CEF
 * is warm.
 *
 * <p>Unbinding this one line disarms every collector at once — the gateway,
 * basin and fetcher stay wired and passive, and on-demand paths keep working.
 */
@Singleton
final class WebWorldStarter {

    @Inject
    WebWorldStarter(WebLifecycle lifecycle) {
        if (de.bsommerfeld.wsbg.terminal.ui.OfflineMode.skipping("the web collectors")) return;
        lifecycle.start();
        Runtime.getRuntime().addShutdownHook(new Thread(lifecycle::stop, "web-world-stop"));
    }
}
