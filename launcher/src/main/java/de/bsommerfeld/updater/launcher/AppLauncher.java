package de.bsommerfeld.updater.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Spawns the main application in a separate JVM process with the correct
 * classpath and JVM flags. The entry point is discovered by
 * {@link MainClassResolver} (keeping the launcher decoupled from the terminal's
 * class name) and the {@code java} binary by {@link JavaExecutableResolver};
 * log rotation is shared with the launcher via {@link LogRotator}. What remains
 * here is the spawn concern: command assembly, redirection, and the in-app
 * update handshake.
 *
 * @see EnvironmentSetup
 * @see LauncherMain
 */
final class AppLauncher {

    /**
     * JDK-internal packages JCEF reaches into for native AWT integration:
     * <ul>
     *   <li>{@code sun.awt}            — common AWT internals</li>
     *   <li>{@code sun.lwawt}          — lightweight AWT base class</li>
     *   <li>{@code sun.lwawt.macosx}   — Cocoa peer (no-op on Win/Linux)</li>
     *   <li>{@code java.awt.peer}      — heavyweight peer access</li>
     * </ul>
     * Without these {@code --add-opens}, the Chromium-NSWindow reparent
     * inside {@code CefBrowserWindowMac.getWindowHandle()} fails with
     * {@code IllegalAccessError} on macOS + JDK 17+. The Linux/Windows
     * paths don't strictly need {@code sun.lwawt.macosx} but opening a
     * non-existent module is silently ignored, so the same flag set is
     * safe on every platform.
     */
    private static final String[] JCEF_OPENS = {
            "java.desktop/sun.awt",
            "java.desktop/sun.lwawt",
            "java.desktop/sun.lwawt.macosx",
            "java.desktop/java.awt.peer"
    };

    private static final String APP_ICON_NAME = "app-icon.png";
    private static final String APP_NAME = "WSBG Terminal";

    private final Path appDirectory;

    AppLauncher(Path appDirectory) {
        this.appDirectory = appDirectory;
    }

    /**
     * Launches the application in a detached process. Returns immediately
     * after the process is started — does not wait for exit.
     *
     * @param extraArgs forwarded to the application's {@code main(String[])} as-is
     * @throws IOException if {@code lib/} is missing, no JARs are found, or
     *                     the main class cannot be resolved
     */
    void launch(String... extraArgs) throws IOException {
        Path libDir = appDirectory.resolve("lib");
        if (!Files.isDirectory(libDir)) {
            throw new IOException("lib/ directory not found — update may have failed");
        }

        Path logDir = appDirectory.resolve("logs/terminal");
        Files.createDirectories(logDir);
        LogRotator.archiveLatestLog(logDir);
        LogRotator.purgeOldLogs(logDir);

        Path logFile = logDir.resolve("latest.log");

        String mainClass = MainClassResolver.resolve(libDir);
        String classpath = buildClasspath(libDir);

        List<String> command = buildCommand(mainClass, classpath, extraArgs);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(appDirectory.toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
        pb.redirectError(ProcessBuilder.Redirect.to(logFile.toFile()));

        PathEnricher.enrich(pb);

        // Hand the terminal the launcher executable path so its in-app "update
        // now" button can relaunch us (cleanly close → restart the launcher,
        // which applies the update). When we run as the staged OTA jar, our own
        // process is just the bundled java binary — useless for a relaunch — so
        // the hull forwards its native executable via WSBG_HULL_EXECUTABLE and
        // we pass that on instead. Absent in dev runs (run.sh starts the
        // terminal directly) — the terminal hides the button when unset.
        String hullExe = System.getenv(StagedLauncher.HULL_EXECUTABLE_ENV);
        if (hullExe != null && !hullExe.isBlank()) {
            pb.environment().put("WSBG_LAUNCHER_EXECUTABLE", hullExe);
        } else {
            ProcessHandle.current().info().command().ifPresent(
                    exe -> pb.environment().put("WSBG_LAUNCHER_EXECUTABLE", exe));
        }

        // Hull-generation handshake for the terminal's amber "renew launcher"
        // button: launchers that never set this count as generation 1. The
        // terminal compares against its required generation and, when the
        // installed hull is older, offers the one-time guided reinstall via
        // the platform installer. Bump ONLY in lockstep with
        // LauncherUpdateService.REQUIRED_GENERATION in the terminal module —
        // and only for hull changes an installed launcher cannot pick up
        // itself (runtime bump, packaging change), never for jar-level logic.
        pb.environment().put("WSBG_LAUNCHER_GENERATION", "2");

        pb.start();
    }

    /**
     * Assembles the full JVM command line. Separated from {@link #launch} to
     * keep the orchestration method focused on I/O and process lifecycle.
     */
    private List<String> buildCommand(String mainClass, String classpath, String[] extraArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(JavaExecutableResolver.resolve());

        addDockIconFlags(cmd);

        // The Metal Java2D pipeline crashes the JVM after ~1-2h (SIGSEGV in the
        // Queue Flusher: MTLContext dealloc over-releases, a JDK bug). The OSR
        // blit is plain BufferedImage work, so the OpenGL pipeline is fine.
        if (isMacOS()) {
            cmd.add("-Dsun.java2d.metal=false");
        }

        // JCEF native bridge needs unrestricted access to OS APIs (Cocoa,
        // GDI, etc.). Required for the embedded Chromium browser to
        // initialise; without it, the JNI helpers log a warning and the
        // browser refuses to load on JDK 22+.
        cmd.add("--enable-native-access=ALL-UNNAMED");
        for (String open : JCEF_OPENS) {
            cmd.add("--add-opens");
            cmd.add(open + "=ALL-UNNAMED");
        }

        cmd.add("-cp");
        cmd.add(classpath);

        cmd.add(mainClass);
        cmd.addAll(List.of(extraArgs));

        return cmd;
    }

    /**
     * Adds macOS dock icon/name flags. Silently skipped on other platforms
     * or if the icon file is absent.
     */
    private void addDockIconFlags(List<String> cmd) {
        if (!isMacOS())
            return;

        Path icon = appDirectory.resolve("lib/" + APP_ICON_NAME);
        if (!Files.exists(icon))
            icon = appDirectory.resolve("images/" + APP_ICON_NAME);
        if (!Files.exists(icon))
            return;

        cmd.add("-Xdock:name=" + APP_NAME);
        cmd.add("-Xdock:icon=" + icon);
    }

    // =====================================================================
    // Classpath
    // =====================================================================

    /**
     * Builds a classpath from every JAR in {@code lib/}. The terminal
     * runs entirely off the unnamed module — no module-path setup
     * required, no platform-specific filtering. Stale JavaFX JARs from
     * pre-WebView installs are silently included but unused; the
     * updater drops them on the next sync anyway.
     */
    private String buildClasspath(Path libDir) throws IOException {
        try (Stream<Path> jars = Files.list(libDir)) {
            return jars.filter(p -> p.toString().endsWith(".jar"))
                    .map(Path::toString)
                    .reduce((a, b) -> a + File.pathSeparator + b)
                    .orElseThrow(() -> new IOException("No JARs found in lib/"));
        }
    }

    // =====================================================================
    // Platform Detection
    // =====================================================================

    private boolean isMacOS() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
