package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.impl.WebLifecycle;

/**
 * Boots the web world — the ONE arming point for the collector clock.
 * Deliberately NOT bound eagerly right now (user decision 2026-08-12: nothing
 * is armed yet, the world signals included); when the time comes, arming is a
 * single {@code bind(WebWorldStarter.class).asEagerSingleton()} in
 * {@code BridgeModule}, LAST in the install order so the clock starts only
 * once the whole pipeline is wired. First passes are staggered 5-90 s; a
 * BROWSER-first source simply rides its DIRECT fallback until CEF is warm.
 */
@Singleton
final class WebWorldStarter {

    @Inject
    WebWorldStarter(WebLifecycle lifecycle) {
        lifecycle.start();
        Runtime.getRuntime().addShutdownHook(new Thread(lifecycle::stop, "web-world-stop"));
    }
}
