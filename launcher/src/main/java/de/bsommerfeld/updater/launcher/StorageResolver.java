package de.bsommerfeld.updater.launcher;

import java.nio.file.Path;
import java.util.Map;

/**
 * OS-aware path resolver for the writable application data directory.
 *
 * <p>
 * The resolved path is where the updater stores downloaded JARs, scripts,
 * logs, version metadata, and — under {@code ai/} — the isolated Ollama
 * runtime and model store. All other launcher classes receive this path as
 * their root.
 *
 * <p>
 * Platform conventions:
 * <ul>
 * <li>Windows: {@code %LOCALAPPDATA%\wsbg-terminal}</li>
 * <li>macOS: {@code ~/Library/Application Support/wsbg-terminal}</li>
 * <li>Linux: {@code $XDG_DATA_HOME/wsbg-terminal} or
 * {@code ~/.local/share/wsbg-terminal}</li>
 * </ul>
 *
 * <p>
 * <strong>Windows uses Local, not Roaming, on purpose:</strong> the bundled AI
 * runtime and downloaded models under {@code ai/} are multiple gigabytes. A
 * roaming profile would try to sync them on every login (domain/quota bloat),
 * so all app data lives in the non-roaming Local directory. Keep this in sync
 * with {@code StorageUtils} (core) and the setup scripts.
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
     * Resolves the root directory for all user-writable application data,
     * reading the live OS, environment, and user home.
     */
    static Path resolve() {
        return resolve(System.getProperty("os.name", ""), System.getenv(),
                System.getProperty("user.home"));
    }

    /**
     * Pure resolution logic, parameterised for testing across platforms.
     * Falls back to safe defaults when platform-specific environment variables
     * ({@code LOCALAPPDATA}, {@code XDG_DATA_HOME}) are absent.
     */
    static Path resolve(String osName, Map<String, String> env, String userHome) {
        String os = osName.toLowerCase();

        // C:\Users\<user>\AppData\Local\wsbg-terminal — Local, not Roaming
        // (see class javadoc: multi-GB ai/ must not roam).
        if (os.contains("win")) {
            String localAppData = env.get("LOCALAPPDATA");
            if (localAppData == null || localAppData.isBlank()) {
                localAppData = Path.of(userHome, "AppData", "Local").toString();
            }
            return Path.of(localAppData, APP_DIR_NAME);
        }

        // /Users/<user>/Library/Application Support/wsbg-terminal
        if (os.contains("mac")) {
            return Path.of(userHome, "Library", "Application Support", APP_DIR_NAME);
        }

        // /home/<user>/.local/share/wsbg-terminal (or $XDG_DATA_HOME/wsbg-terminal)
        String xdgDataHome = env.get("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            return Path.of(xdgDataHome, APP_DIR_NAME);
        }
        return Path.of(userHome, ".local", "share", APP_DIR_NAME);
    }
}
