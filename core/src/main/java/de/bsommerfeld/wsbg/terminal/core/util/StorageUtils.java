package de.bsommerfeld.wsbg.terminal.core.util;

import de.bsommerfeld.wsbg.terminal.core.config.OperatingSystem;

import java.nio.file.Path;
import java.nio.file.Paths;

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
        String home = System.getProperty("user.home");

        return switch (OperatingSystem.current()) {
            case MACOS -> Paths.get(home, "Library", "Application Support", APP_NAME);
            case WINDOWS -> {
                String appData = System.getenv("APPDATA");
                yield appData != null
                        ? Paths.get(appData, APP_NAME)
                        : Paths.get(home, "AppData", "Roaming", APP_NAME);
            }
            case LINUX -> {
                String xdgData = System.getenv("XDG_DATA_HOME");
                yield xdgData != null && !xdgData.isEmpty()
                        ? Paths.get(xdgData, APP_NAME)
                        : Paths.get(home, ".local", "share", APP_NAME);
            }
        };
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
