package de.bsommerfeld.starter;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Where an application keeps its per-user files, by each platform's own
 * convention:
 *
 * <pre>
 * Windows   %LOCALAPPDATA%\&lt;Name&gt;
 * macOS     ~/Library/Application Support/&lt;Name&gt;
 * Linux     $XDG_DATA_HOME/&lt;name&gt;, else ~/.local/share/&lt;name&gt;
 * </pre>
 *
 * On Linux the name is lower-cased, as directories there are.
 */
public final class DataDirectory {

    private DataDirectory() {
    }

    /** The data directory of the named application on this machine. */
    public static Path of(String applicationName) {
        return of(applicationName, System.getProperty("os.name"), System.getProperty("user.home"), System.getenv());
    }

    static Path of(String applicationName, String osName, String userHome, Map<String, String> environment) {
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalArgumentException("applicationName must not be blank");
        }
        String os = osName.toLowerCase(Locale.ROOT);
        Path home = Path.of(userHome);

        if (os.startsWith("windows")) {
            String localAppData = environment.get("LOCALAPPDATA");
            Path base = isSet(localAppData) ? Path.of(localAppData) : home.resolve("AppData").resolve("Local");
            return base.resolve(applicationName);
        }
        if (os.startsWith("mac")) {
            return home.resolve("Library").resolve("Application Support").resolve(applicationName);
        }
        String xdgDataHome = environment.get("XDG_DATA_HOME");
        Path base = isSet(xdgDataHome) ? Path.of(xdgDataHome) : home.resolve(".local").resolve("share");
        return base.resolve(applicationName.toLowerCase(Locale.ROOT));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
