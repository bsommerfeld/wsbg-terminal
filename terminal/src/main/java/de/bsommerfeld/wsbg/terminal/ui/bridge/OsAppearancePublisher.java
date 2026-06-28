package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pushes the host OS's dark/light appearance to the page as an {@code os-appearance}
 * envelope ({@code {mode:"dark"|"light"}}).
 *
 * <p><b>Why this exists.</b> The browser runs in software OSR (off-screen) mode
 * (CefHost: {@code windowless_rendering_enabled = true}). An off-screen Chromium has
 * no native window to read the OS appearance from, so the page's
 * {@code matchMedia('(prefers-color-scheme: dark)')} reports a fixed default and
 * never fires on a real theme change — which is why "Aus Systemeinstellungen
 * übernehmen" stayed stuck in light mode when macOS switched to dark on a schedule
 * ("Automatic" appearance). We detect the appearance on the Java side instead and
 * push it; {@code theme.js} treats this value as authoritative.
 *
 * <p>On macOS {@code defaults read -g AppleInterfaceStyle} prints {@code "Dark"} in
 * dark mode and exits non-zero (key absent) in light mode — and in "Automatic" mode
 * it reflects the <em>current effective</em> appearance, so polling it picks up the
 * scheduled dusk switch. Windows (registry) and Linux (gsettings) are best-effort;
 * when detection can't produce a definite answer nothing is pushed and the page
 * falls back to {@code matchMedia} (its prior behaviour).
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
        this.appearance = detectAppearance();
        // A freshly loaded page gets the current appearance immediately.
        hub.onClientOpen(this::pushCurrent);

        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "os-appearance-poll");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::poll, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
    }

    private void poll() {
        String now = detectAppearance();
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
        try {
            hub.broadcast("os-appearance", Map.of("mode", mode));
        } catch (Exception e) {
            LOG.warn("os-appearance broadcast failed: {}", e.getMessage());
        }
    }

    /** @return {@code "dark"}, {@code "light"}, or {@code null} when undetermined. */
    private static String detectAppearance() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                // Non-zero exit / empty output == light (key absent); "Dark" == dark.
                String out = exec("defaults", "read", "-g", "AppleInterfaceStyle");
                return out != null && out.toLowerCase(Locale.ROOT).contains("dark") ? "dark" : "light";
            }
            if (os.contains("win")) {
                String out = exec("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme");
                if (out == null || !out.contains("AppsUseLightTheme")) return null;
                // REG_DWORD 0x0 => apps use dark theme; 0x1 => light.
                return out.matches("(?s).*AppsUseLightTheme\\s+REG_DWORD\\s+0x0\\b.*") ? "dark" : "light";
            }
            if (os.contains("nux") || os.contains("nix")) {
                String out = exec("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
                if (out == null || out.isEmpty()) return null;
                return out.contains("prefer-dark") ? "dark" : "light";
            }
        } catch (Exception e) {
            LOG.debug("OS appearance detection failed: {}", e.getMessage());
        }
        return null;
    }

    /** Runs a short command; returns trimmed stdout, or {@code null} on failure/timeout. */
    private static String exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
