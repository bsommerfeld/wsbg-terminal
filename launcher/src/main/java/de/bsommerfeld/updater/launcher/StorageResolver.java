package de.bsommerfeld.updater.launcher;

import java.nio.file.Path;

/**
 * OS-aware path resolver for the writable application data directory.
 *
 * <p>
 * The resolved path is where the updater stores downloaded JARs, scripts,
 * logs, and version metadata. All other launcher classes receive this path
 * as their root.
 *
 * <p>
 * Platform conventions:
 * <ul>
 * <li>Windows: {@code %APPDATA%\wsbg-terminal}</li>
 * <li>macOS: {@code ~/Library/Application Support/wsbg-terminal}</li>
 * <li>Linux: {@code $XDG_DATA_HOME/wsbg-terminal} or
 * {@code ~/.local/share/wsbg-terminal}</li>
 * </ul>
 *
 * <p>
 * This class does <strong>not</strong> create the directory — callers
 * are responsible for ensuring existence before use.
 */
final class StorageResolver {

    private static final String APP_DIR_NAME = "wsbg-terminal";

    private StorageResolver() {
    }

    /**
     * Resolves the root directory for all user-writable application data.
     * Falls back to safe defaults when platform-specific environment variables
     * ({@code APPDATA}, {@code XDG_DATA_HOME}) are absent.
     */
    static Path resolve() {
        String os = System.getProperty("os.name", "").toLowerCase();

        // C:\Users\<user>\AppData\Roaming\wsbg-terminal
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData == null) {
                appData = Path.of(System.getProperty("user.home"), "AppData", "Roaming").toString();
            }
            return Path.of(appData, APP_DIR_NAME);
        }

        // /Users/<user>/Library/Application Support/wsbg-terminal
        if (os.contains("mac")) {
            return Path.of(System.getProperty("user.home"), "Library", "Application Support", APP_DIR_NAME);
        }

        // /home/<user>/.local/share/wsbg-terminal (or $XDG_DATA_HOME/wsbg-terminal)
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome, APP_DIR_NAME);
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", APP_DIR_NAME);
    }
}
