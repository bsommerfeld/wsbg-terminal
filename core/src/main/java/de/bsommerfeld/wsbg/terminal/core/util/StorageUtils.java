package de.bsommerfeld.wsbg.terminal.core.util;

import de.bsommerfeld.wsbg.terminal.core.config.OperatingSystem;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

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
 * <li><strong>Windows</strong>: {@code %LOCALAPPDATA%\{appName}} (fallback:
 * {@code ~/AppData/Local})</li>
 * <li><strong>Linux</strong>: {@code $XDG_DATA_HOME/{appName}} (fallback:
 * {@code ~/.local/share})</li>
 * </ul>
 *
 * <p>
 * <strong>Windows uses Local, not Roaming, on purpose:</strong> the isolated
 * Ollama runtime and downloaded models under {@code ai/} are multiple gigabytes
 * and must not sync with a roaming profile. Keep this aligned with the launcher's
 * {@code StorageResolver} and the setup scripts.
 */
public final class StorageUtils {

    private static final String APP_NAME = "wsbg-terminal";

    private StorageUtils() {
    }

    /**
     * Returns the platform-specific application data directory, reading the live
     * OS, environment, and user home. The directory is not guaranteed to exist.
     *
     * @return absolute path to the application's data directory
     */
    public static Path getAppDataDir() {
        return getAppDataDir(OperatingSystem.current(), System.getenv(),
                System.getProperty("user.home"));
    }

    /**
     * Pure resolution logic, parameterised for testing across platforms.
     *
     * @return absolute path to the application's data directory
     */
    static Path getAppDataDir(OperatingSystem os, Map<String, String> env, String home) {
        return switch (os) {
            case MACOS -> Paths.get(home, "Library", "Application Support", APP_NAME);
            case WINDOWS -> {
                String localAppData = env.get("LOCALAPPDATA");
                yield localAppData != null && !localAppData.isBlank()
                        ? Paths.get(localAppData, APP_NAME)
                        : Paths.get(home, "AppData", "Local", APP_NAME);
            }
            case LINUX -> {
                String xdgData = env.get("XDG_DATA_HOME");
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

    /**
     * Returns the snapshot directory inside the application data directory.
     * Keeps the short-TTL session snapshots out of the app data root, alongside
     * (but not mixed with) the config file.
     *
     * @return absolute path to {@code {appDataDir}/snapshots}
     */
    public static Path getSnapshotsDir() {
        return getAppDataDir().resolve("snapshots");
    }
}
