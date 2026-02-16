package de.bsommerfeld.wsbg.terminal.launcher;

import de.bsommerfeld.tinyupdate.api.GitHubRepository;
import de.bsommerfeld.tinyupdate.api.TinyUpdateClient;

import java.nio.file.Path;

/**
 * WSBG-specific launcher configuration.
 * Wires TinyUpdate into the concrete project with the correct repository
 * and application directory.
 */
public final class LauncherApp {

    private static final GitHubRepository REPO = GitHubRepository.of("bsommerfeld/wsbg-terminal");

    private LauncherApp() {}

    public static void main(String[] args) {
        // Delegate to the TinyUpdate launcher with WSBG-specific config.
        // This class exists so the top-level launcher module can be used
        // independently for testing without the full native JPackage build.
        de.bsommerfeld.tinyupdate.launcher.LauncherMain.main(args);
    }
}
