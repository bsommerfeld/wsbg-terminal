package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.api.ConnectivityProbe;
import de.bsommerfeld.updater.api.GitHubRepository;
import de.bsommerfeld.updater.api.TinyUpdateClient;
import de.bsommerfeld.updater.api.UpdateResult;
import de.bsommerfeld.updater.update.VersionFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/**
 * Launcher self-update: the two-stage bootstrap that makes the native hull
 * (jpackage installer: runtime + embedded jar) effectively immortal.
 *
 * <p>It is the terminal's own update pattern applied one level down: the hull
 * is the immutable shell, {@code <appDir>/launcher/} is its OTA-synced payload
 * directory, and {@link #sync} keeps that payload current through a second
 * {@link TinyUpdateClient} channel ({@code launcher-update.json} +
 * {@code launcher-files.zip} — separate release assets, so launchers that
 * predate this class keep seeing exactly the assets they know).
 *
 * <h3>Handoff ({@link #handoff})</h3>
 * At startup the hull re-execs the staged jar via its bundled {@code java}
 * when — and only when — the staged version is <em>strictly newer</em> than
 * the hull's own install version (jpackage's {@code jpackage.app-version}
 * system property, baked into the generated app {@code .cfg}).
 * Equal or older stays embedded: a freshly reinstalled hull is never
 * downgraded by a stale staged jar. Every failure path — no jar, no version
 * marker, unknown hull version (dev runs), corrupt jar, instant child death —
 * falls back to the embedded jar, never to a broken start.
 *
 * <h3>Sync ({@link #sync})</h3>
 * Runs inside the normal update phase, behind the same
 * {@code user.auto-update} gate (and {@code --force-update} override) as the
 * terminal update. A newly staged jar takes over on the <em>next</em> start —
 * no mid-flight restart. The staged jar runs this same class, so it keeps
 * itself current too; {@code WSBG_STAGED_LAUNCHER} guards against re-exec
 * recursion.
 *
 * <h3>Discipline</h3>
 * The staged jar runs on the OLDEST shipped hull runtime — launcher code must
 * stay compatible with that Java release and must not adopt preview features.
 */
final class StagedLauncher {

    /** Set on the re-exec'd child so it never tries to hand off again. */
    static final String STAGED_ENV = "WSBG_STAGED_LAUNCHER";

    /**
     * The hull's native executable path, forwarded to the staged child so
     * {@link AppLauncher} can hand the terminal a relaunchable path — the
     * staged child's own process is just the bundled {@code java} binary.
     */
    static final String HULL_EXECUTABLE_ENV = "WSBG_HULL_EXECUTABLE";

    static final String MANIFEST_ASSET = "launcher-update.json";
    static final String ARCHIVE_ASSET = "launcher-files.zip";
    static final String JAR_NAME = "launcher.jar";

    /**
     * How long the hull watches the freshly spawned child before trusting it.
     * A healthy launcher run takes seconds (network update check), so any exit
     * inside this window — corrupt jar, bytecode too new for this hull's
     * runtime — means "fall back to the embedded jar" rather than leaving the
     * user with nothing.
     */
    private static final long INSTANT_DEATH_WINDOW_MS = 1500;

    private StagedLauncher() {
    }

    /** The OTA payload directory — deliberately beside {@code lib/}, never inside it. */
    static Path stagingDir(Path appDir) {
        return appDir.resolve("launcher");
    }

    // =====================================================================
    // Handoff (start-time re-exec)
    // =====================================================================

    /**
     * Re-execs the staged jar when it is strictly newer than this hull.
     * Returns {@code true} if the child took over and the caller should exit;
     * {@code false} means "continue with the embedded jar" — always safe.
     */
    static boolean handoff(Path appDir, SessionLog log, String[] args) {
        if ("1".equals(System.getenv(STAGED_ENV)))
            return false; // we ARE the staged jar — no recursion

        Path jar = stagingDir(appDir).resolve(JAR_NAME);
        if (!Files.isRegularFile(jar))
            return false; // nothing staged (fresh install / first runs)

        String staged = new VersionFile(stagingDir(appDir)).read();
        if (staged == null) {
            log.log("Staged launcher jar has no version marker — running embedded.");
            return false;
        }

        String hull = hullVersion();
        if (hull == null) {
            // No .jpackage.xml beside our own jar: a dev run (run-launcher.sh)
            // or an exotic install. Without a hull version there is no safe
            // "strictly newer" comparison — prefer the code that IS running.
            log.log("Own install version unknown (dev run?) — staged launcher ignored.");
            return false;
        }

        if (compareVersions(staged, hull) <= 0) {
            log.log("Staged launcher " + staged + " not newer than hull " + hull + " — running embedded.");
            return false;
        }

        if (!isLaunchableJar(jar)) {
            log.log("Staged launcher jar is corrupt or has no Main-Class — running embedded.");
            return false;
        }

        log.log("Staged launcher " + staged + " > hull " + hull + " — handing off.");
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(JavaExecutableResolver.resolve());
            if (isMacOS())
                cmd.add("-Xdock:name=WSBG Terminal");
            cmd.add("-jar");
            cmd.add(jar.toString());
            cmd.addAll(Arrays.asList(args));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            pb.environment().put(STAGED_ENV, "1");
            ProcessHandle.current().info().command()
                    .ifPresent(exe -> pb.environment().put(HULL_EXECUTABLE_ENV, exe));

            Process child = pb.start();
            if (child.waitFor(INSTANT_DEATH_WINDOW_MS, TimeUnit.MILLISECONDS)) {
                log.log("Staged launcher died instantly (exit " + child.exitValue()
                        + ") — running embedded instead.");
                return false;
            }

            log.log("Staged launcher running (pid " + child.pid() + "), hull exiting.");
            return true;

        } catch (Exception e) {
            log.log("Staged launcher handoff failed: " + e.getMessage() + " — running embedded.");
            log.logStackTrace(e);
            return false;
        }
    }

    // =====================================================================
    // Sync (download for the NEXT start)
    // =====================================================================

    /**
     * Syncs the staged jar to the latest release — quiet (log-only), never a
     * stop: the running launcher works either way and the next start retries.
     * Honors the same auto-update opt-out as the terminal update; the in-app
     * "update now" relaunch ({@code --force-update}) pulls it regardless.
     */
    static void sync(GitHubRepository repo, Path appDir, SessionLog log,
            boolean autoUpdate, boolean forceUpdate) {
        if (!autoUpdate && !forceUpdate) {
            log.log("Auto-update disabled — launcher self-update skipped.");
            return;
        }
        if (!ConnectivityProbe.isOnline())
            return; // offline start: nothing to sync, no error noise

        try {
            Files.createDirectories(stagingDir(appDir));
            TinyUpdateClient client = new TinyUpdateClient(repo, stagingDir(appDir),
                    MANIFEST_ASSET, ARCHIVE_ASSET, null, null);
            UpdateResult result = client.update(progress -> { }, 0);
            if (result.updated()) {
                log.log("Launcher self-update staged: " + result.version() + " (takes over on next start)");
            } else if (result.version() == null) {
                // Race guard hit: the latest release carries no launcher
                // channel (yet) — pre-channel releases, or CI still uploading.
                log.log("No launcher channel in the latest release — nothing staged.");
            } else {
                log.log("Staged launcher up to date (" + result.version() + ")");
            }
        } catch (Exception e) {
            // Covers releases without the launcher assets, dropped connections,
            // and everything else — the embedded jar keeps working regardless.
            log.log("Launcher self-update failed (non-fatal): " + e.getMessage());
        }
    }

    // =====================================================================
    // Version plumbing
    // =====================================================================

    /**
     * The hull's install version, or {@code null} when unknown (dev runs).
     * jpackage bakes {@code -Djpackage.app-version=<version>} into the
     * generated app {@code .cfg} on every platform (verified against a real
     * macOS install), so the property is present exactly when we run as the
     * installed hull — and absent in the staged child (plain {@code java -jar})
     * and in dev runs, both of which must never hand off anyway.
     */
    private static String hullVersion() {
        String version = System.getProperty("jpackage.app-version");
        return version == null || version.isBlank() ? null : version.trim();
    }

    /**
     * Numeric segment-wise version comparison, tolerant of a leading
     * {@code v}/{@code V} (release tags) and missing segments ({@code 1.2}
     * equals {@code 1.2.0}). Unparseable segments count as zero — a garbled
     * version can therefore never look "newer" than a clean one.
     */
    static int compareVersions(String a, String b) {
        int[] x = numbers(a);
        int[] y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? x[i] : 0;
            int yi = i < y.length ? y[i] : 0;
            if (xi != yi)
                return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static int[] numbers(String version) {
        return Arrays.stream(version.trim().replaceFirst("^[vV]", "").split("\\D+"))
                .filter(s -> !s.isEmpty())
                .mapToInt(s -> {
                    try {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        return 0; // absurdly long digit run — treat as zero
                    }
                })
                .toArray();
    }

    // =====================================================================
    // Sanity checks
    // =====================================================================

    /** Cheap pre-flight: readable zip with a {@code Main-Class} manifest entry. */
    private static boolean isLaunchableJar(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            return jarFile.getManifest() != null
                    && jarFile.getManifest().getMainAttributes().getValue("Main-Class") != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isMacOS() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
