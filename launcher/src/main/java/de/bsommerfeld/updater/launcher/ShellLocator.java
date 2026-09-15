package de.bsommerfeld.updater.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds the native shell binary ({@code shell/} module: the window with the
 * system webview) inside the installed app directory. When it is there, the
 * launcher starts it instead of the terminal JVM and the shell starts the JVM as
 * its sidecar; when it is absent, the terminal opens its own JCEF window as
 * before. Either way the same jars run.
 *
 * <p>Layout, per platform because one OTA package serves every OS:
 * <pre>
 *   &lt;appDir&gt;/bin/shell/&lt;platform&gt;/wsbg-shell[.exe]   shipped with the package
 *   &lt;appDir&gt;/shell/wsbg-shell[.exe]                  a locally placed build (dev, tests)
 * </pre>
 * {@code &lt;platform&gt;} is {@code macos-aarch64}, {@code macos-x86_64},
 * {@code windows-x86_64}, {@code linux-x86_64} or {@code linux-aarch64} - the
 * names the release workflow uses for the shell artifacts.
 *
 * <p>{@code WSBG_USE_SHELL=false} in the launcher's environment skips the shell
 * even when the binary is present: the escape hatch back to the JCEF window.
 */
final class ShellLocator {

    static final String EXECUTABLE = "wsbg-shell";
    static final String OPT_OUT_ENV = "WSBG_USE_SHELL";

    /**
     * Platforms the release builds a shell for. macOS on Apple Silicon is the one
     * it was measured on (2026-09-15); Windows and Linux were implemented without
     * a test machine and go out on that basis - {@link #OPT_OUT_ENV} is the way
     * back to the JCEF window if one of them misbehaves.
     */
    static final java.util.Set<String> SUPPORTED_PLATFORMS =
            java.util.Set.of("macos-aarch64", "windows-x86_64", "linux-x86_64");

    private ShellLocator() {}

    /** The shell for this machine, if installed and not opted out. */
    static Optional<Path> find(Path appDirectory) {
        if ("false".equalsIgnoreCase(System.getenv(OPT_OUT_ENV))) return Optional.empty();
        return find(appDirectory, System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static Optional<Path> find(Path appDirectory, String osName, String osArch) {
        String platform = platform(osName, osArch);
        if (!SUPPORTED_PLATFORMS.contains(platform)) return Optional.empty();
        String exe = isWindows(osName) ? EXECUTABLE + ".exe" : EXECUTABLE;
        Path packaged = appDirectory.resolve("bin").resolve("shell").resolve(platform).resolve(exe);
        Path local = appDirectory.resolve("shell").resolve(exe);
        for (Path candidate : new Path[] {packaged, local}) {
            if (Files.isRegularFile(candidate)) {
                ensureExecutable(candidate);
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** {@code <os>-<arch>} the way the release artifacts are named. */
    static String platform(String osName, String osArch) {
        String os = isWindows(osName) ? "windows"
                : osName.toLowerCase(Locale.ROOT).contains("mac") ? "macos" : "linux";
        String arch = switch (osArch.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "aarch64";
            case "amd64", "x86_64", "x64" -> "x86_64";
            default -> osArch.toLowerCase(Locale.ROOT);
        };
        return os + "-" + arch;
    }

    private static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    /** Zip extraction drops the execute bit; a binary that cannot run is no shell. */
    private static void ensureExecutable(Path file) {
        if (Files.isExecutable(file)) return;
        try {
            file.toFile().setExecutable(true, false);
        } catch (SecurityException ignored) {
            // The spawn will fail loudly; nothing more to do here.
        }
    }

    /** Wraps the terminal JVM command so the shell starts it as its sidecar. */
    static java.util.List<String> wrap(Path shell, java.util.List<String> jvmCommand) {
        java.util.List<String> cmd = new java.util.ArrayList<>(jvmCommand.size() + 2);
        cmd.add(shell.toString());
        cmd.add("--backend");
        cmd.addAll(jvmCommand);
        return cmd;
    }

    /** For the session log: where a found shell came from. */
    static String describe(Path shell) throws IOException {
        return shell.toRealPath().toString();
    }
}
