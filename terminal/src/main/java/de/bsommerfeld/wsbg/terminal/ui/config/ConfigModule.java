package de.bsommerfeld.wsbg.terminal.ui.config;

import com.google.inject.AbstractModule;
import de.bsommerfeld.wsbg.terminal.core.config.AgentConfig;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.ui.scroll.PixelScaledWheelScrollPolicy;
import de.bsommerfeld.wsbg.terminal.ui.scroll.WheelScrollPolicy;

/**
 * Config-instance binds + the OSR wheel-scroll seam derived from user prefs. The
 * loaded {@link GlobalConfig} is handed in by {@link AppModule} (which owns the
 * dir bootstrap + load), so this module is a pure binding declaration.
 *
 * <p>The off-screen browser gets no native OS wheel message, so we re-scale the
 * AWT delta: precise rotation × OS lines-per-notch × scroll-speed inherits both
 * the OS speed setting and (via the sign) the OS 'natural scrolling' setting.
 * Speed + invert come from UserConfig. Block-mode (Windows page-scroll) is rare;
 * derive it from the line speed.
 */
final class ConfigModule extends AbstractModule {

    private final GlobalConfig config;

    ConfigModule(GlobalConfig config) {
        this.config = config;
    }

    @Override
    protected void configure() {
        bind(GlobalConfig.class).toInstance(config);
        bind(AgentConfig.class).toInstance(config.getAgent());

        double scrollSpeed = config.getUser().getScrollSpeed();
        boolean scrollInvert = config.getUser().isScrollInvert();
        bind(WheelScrollPolicy.class).toInstance(new PixelScaledWheelScrollPolicy(
                scrollSpeed, scrollSpeed * 10.0, scrollInvert, scrollInvert));
    }
}
