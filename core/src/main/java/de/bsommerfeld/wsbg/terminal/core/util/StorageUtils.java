package de.bsommerfeld.wsbg.terminal.core.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public class StorageUtils {

    private StorageUtils() {
        // static utility
    }

    public static Path getAppDataDir(String appName) {
        String os = System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH);
        Path path;

        if ((os.contains("mac")) || (os.contains("darwin"))) {
            // MacOS: ~/Library/Application Support/AppName
            path = Paths.get(System.getProperty("user.home"), "Library", "Application Support", appName);
        } else if (os.contains("win")) {
            // Windows: %APPDATA%\AppName
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                path = Paths.get(appData, appName);
            } else {
                path = Paths.get(System.getProperty("user.home"), "AppData", "Roaming", appName);
            }
        } else {
            // Linux/Unix: ~/.local/share/AppName or XDG_DATA_HOME
            String xdgData = System.getenv("XDG_DATA_HOME");
            if (xdgData != null && !xdgData.isEmpty()) {
                path = Paths.get(xdgData, appName);
            } else {
                path = Paths.get(System.getProperty("user.home"), ".local", "share", appName);
            }
        }
        return path;
    }

    public static Path getLogsDir(String appName) {
        // Keep logs inside AppData for portability/cleanliness as requested
        return getAppDataDir(appName).resolve("logs");
    }
}
