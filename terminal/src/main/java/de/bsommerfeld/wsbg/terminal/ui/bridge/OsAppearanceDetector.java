package de.bsommerfeld.wsbg.terminal.ui.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Detects the host OS's dark/light appearance by shelling out to the platform's
 * native query. Pure (no PushHub), extracted from {@link OsAppearancePublisher} so
 * the OS logic is testable and the publisher only schedules + broadcasts.
 *
 * <p>On macOS {@code defaults read -g AppleInterfaceStyle} prints {@code "Dark"} in
 * dark mode and exits non-zero (key absent) in light mode — and in "Automatic" mode
 * it reflects the <em>current effective</em> appearance, so polling it picks up the
 * scheduled dusk switch. Windows (registry) and Linux (gsettings) are best-effort;
 * when detection can't produce a definite answer it returns {@code null} and the page
 * falls back to {@code matchMedia}.
 */
final class OsAppearanceDetector {

    private static final Logger LOG = LoggerFactory.getLogger(OsAppearanceDetector.class);

    private OsAppearanceDetector() {
    }

    /** @return {@code "dark"}, {@code "light"}, or {@code null} when undetermined. */
    static String detect() {
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
