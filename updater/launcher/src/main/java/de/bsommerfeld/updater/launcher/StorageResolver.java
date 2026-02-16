package de.bsommerfeld.updater.launcher;

import java.nio.file.Path;

/**
 * OS-aware path resolver for the writable application data directory.
 *
 * <p>Layout follows platform conventions:
 * <ul>
 *   <li>Windows: {@code %APPDATA%\wsbg-terminal}</li>
 *   <li>macOS:   {@code ~/Library/Application Support/wsbg-terminal}</li>
 *   <li>Linux:   {@code $XDG_DATA_HOME/wsbg-terminal} or {@code ~/.local/share/wsbg-terminal}</li>
 * </ul>
 */
final class StorageResolver {

    private static final String APP_DIR_NAME = "wsbg-terminal";

    private StorageResolver() {}

    /**
     * Resolves the root directory for all user-writable application data.
     * Does not create the directory — callers are responsible for ensuring existence.
     */
    static Path resolve() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData == null) {
                appData = Path.of(System.getProperty("user.home"), "AppData", "Roaming").toString();
            }
            return Path.of(appData, APP_DIR_NAME);
        }

        if (os.contains("mac")) {
            return Path.of(System.getProperty("user.home"), "Library", "Application Support", APP_DIR_NAME);
        }

        // Linux / other Unix
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome, APP_DIR_NAME);
        }
        return Path.of(System.getProperty("user.home"), ".local", "share", APP_DIR_NAME);
    }
}
