package de.bsommerfeld.wsbg.terminal.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the running terminal is installed - known only when it was started
 * the installed way: by the native launcher (which sets
 * {@code jpackage.app-path}) through the starter (which sets
 * {@code starter.data.dir} and {@code starter.install.dir}). A terminal
 * started from the build has no installation, and nothing to update.
 *
 * @param dataDirectory the terminal's data directory, holding every install
 * @param install       the terminal's own install
 * @param launcher      the native launcher the terminal was started with
 */
record Installation(Path dataDirectory, Path install, Path launcher) {

    /** The updater's install, next to the terminal's. */
    Path updaterInstall() {
        return dataDirectory.resolve("updater");
    }

    /** The updater's native launcher, next to the terminal's. */
    Path updaterLauncher() {
        String name = launcher.getFileName().toString();
        String extension = name.endsWith(".exe") ? ".exe" : "";
        return launcher.resolveSibling("WSBG Updater" + extension);
    }

    Path logDirectory() {
        return dataDirectory.resolve("logs");
    }

    static Optional<Installation> current() {
        String data = System.getProperty("starter.data.dir");
        String install = System.getProperty("starter.install.dir");
        String launcher = System.getProperty("jpackage.app-path");
        if (data == null || install == null || launcher == null || !Files.isRegularFile(Path.of(launcher))) {
            return Optional.empty();
        }
        return Optional.of(new Installation(Path.of(data), Path.of(install), Path.of(launcher)));
    }
}
