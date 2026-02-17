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

    private StorageUtils() {
    }

    /**
     * Returns the platform-specific application data directory for the given app
     * name.
     * The directory is not guaranteed to exist.
     *
     * @param appName application identifier used as the directory name
     * @return absolute path to the application's data directory
     */
    public static Path getAppDataDir(String appName) {
        String os = System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH);
        Path path;

        if ((os.contains("mac")) || (os.contains("darwin"))) {
            path = Paths.get(System.getProperty("user.home"), "Library", "Application Support", appName);
        } else if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                path = Paths.get(appData, appName);
            } else {
                path = Paths.get(System.getProperty("user.home"), "AppData", "Roaming", appName);
            }
        } else {
            String xdgData = System.getenv("XDG_DATA_HOME");
            if (xdgData != null && !xdgData.isEmpty()) {
                path = Paths.get(xdgData, appName);
            } else {
                path = Paths.get(System.getProperty("user.home"), ".local", "share", appName);
            }
        }
        return path;
    }

    /**
     * Returns the log directory inside the application data directory.
     * Keeps logs co-located with app data for portability.
     *
     * @param appName application identifier
     * @return absolute path to {@code {appDataDir}/logs}
     */
    public static Path getLogsDir(String appName) {
        return getAppDataDir(appName).resolve("logs");
    }
}
