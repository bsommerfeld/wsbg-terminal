package de.bsommerfeld.wsbg.terminal.core.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Resolves OS-specific application data directories following each platform's
 * native conventions. All paths are returned as absolute {@link Path} instances
 * but are <strong>not</strong> created — the caller is responsible for ensuring
 * the directory exists.
 *
 * <p>
 * Resolution order per platform:
 * <ul>
 * <li><strong>macOS</strong>:
 * {@code ~/Library/Application Support/{appName}}</li>
 * <li><strong>Windows</strong>: {@code %APPDATA%\{appName}} (fallback:
 * {@code ~/AppData/Roaming})</li>
 * <li><strong>Linux</strong>: {@code $XDG_DATA_HOME/{appName}} (fallback:
 * {@code ~/.local/share})</li>
 * </ul>
 */
public final class StorageUtils {

    private static final String APP_NAME = "wsbg-terminal";
    
    private StorageUtils() {
    }

    /**
     * Returns the platform-specific application data directory for the given app
     * name.
     * The directory is not guaranteed to exist.
     *
     * @return absolute path to the application's data directory
     */
    public static Path getAppDataDir() {
        String os = System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH);
        Path path;

        if ((os.contains("mac")) || (os.contains("darwin"))) {
            path = Paths.get(System.getProperty("user.home"), "Library", "Application Support", APP_NAME);
        } else if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                path = Paths.get(appData, APP_NAME);
            } else {
                path = Paths.get(System.getProperty("user.home"), "AppData", "Roaming", APP_NAME);
            }
        } else {
            String xdgData = System.getenv("XDG_DATA_HOME");
            if (xdgData != null && !xdgData.isEmpty()) {
                path = Paths.get(xdgData, APP_NAME);
            } else {
                path = Paths.get(System.getProperty("user.home"), ".local", "share", APP_NAME);
            }
        }
        return path;
    }

    /**
     * Returns the log directory inside the application data directory.
     * Keeps logs co-located with app data for portability.
     *
     * @return absolute path to {@code {appDataDir}/logs}
     */
    public static Path getLogsDir() {
        return getAppDataDir().resolve("logs");
    }
}
