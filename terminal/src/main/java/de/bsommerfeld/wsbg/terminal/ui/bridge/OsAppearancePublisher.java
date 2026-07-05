package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pushes the host OS's dark/light appearance to the page as an {@code os-appearance}
 * envelope ({@code {mode:"dark"|"light"}}). The actual detection lives in
 * {@link OsAppearanceDetector}; this class only polls it and broadcasts changes.
 *
 * <p><b>Why this exists.</b> The browser runs in software OSR (off-screen) mode
 * (CefHost: {@code windowless_rendering_enabled = true}). An off-screen Chromium has
 * no native window to read the OS appearance from, so the page's
 * {@code matchMedia('(prefers-color-scheme: dark)')} reports a fixed default and
 * never fires on a real theme change — which is why "Aus Systemeinstellungen
 * übernehmen" stayed stuck in light mode when macOS switched to dark on a schedule
 * ("Automatic" appearance). We detect the appearance on the Java side instead and
 * push it; {@code theme.js} treats this value as authoritative.
 */
@Singleton
public final class OsAppearancePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(OsAppearancePublisher.class);

    /** How often to re-check the OS appearance, to catch the scheduled (dusk) switch. */
    private static final long POLL_SECONDS = 30;

    private final PushHub hub;

    /** Last detected appearance ("dark"/"light"), or {@code null} if undetermined. */
    private volatile String appearance;

    @Inject
    public OsAppearancePublisher(PushHub hub) {
        this.hub = hub;
        this.appearance = OsAppearanceDetector.detect();
        // A freshly loaded page gets the current appearance immediately.
        hub.onClientOpen(this::pushCurrent);

        ScheduledExecutorService exec = DaemonSchedulers.scheduled("os-appearance-poll");
        exec.scheduleWithFixedDelay(this::poll, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
    }

    private void poll() {
        String now = OsAppearanceDetector.detect();
        if (now != null && !now.equals(appearance)) {
            appearance = now;
            broadcast(now);
            LOG.info("OS appearance changed -> {}", now);
        }
    }

    private void pushCurrent() {
        if (appearance != null) broadcast(appearance);
    }

    private void broadcast(String mode) {
        hub.broadcastSafe("os-appearance", () -> Map.of("mode", mode));
    }
}
