package de.bsommerfeld.updater.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Runs the OS-appropriate setup script before the application launches, streams
 * its output, and honors the exit-code contract. The three sub-concerns are
 * delegated: {@link SetupScriptLocator} (which script + how to spawn it, incl.
 * the dev override), {@link IdleWatchdog} (kill a hung script), and
 * {@link ScriptOutputClassifier} (map each stdout line to a {@code (phase,
 * detail)} event). This class is left with the lifecycle: start, stream, await,
 * and forcible teardown.
 *
 * <h3>Watchdog</h3>
 * The launcher blocks until the script completes. There is no fixed overall
 * deadline — a first-run model download legitimately takes as long as the
 * connection needs — but an <em>idle</em> watchdog kills the script when it
 * produces no output for {@link #DEFAULT_IDLE_TIMEOUT}. {@link #TIMEOUT_MINUTES}
 * only caps the residual gap between stdout EOF and process exit.
 *
 * @see SetupScriptLocator
 * @see IdleWatchdog
 * @see ScriptOutputClassifier
 */
final class EnvironmentSetup {

    /** Caps only the stdout-EOF → process-exit gap, not the script runtime. */
    private static final long TIMEOUT_MINUTES = 15;

    /** Kill the script when it emits no output at all for this long. */
    static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(10);

    /**
     * Script exit code meaning "finished, but at least one step degraded"
     * (e.g. a font download failed, a model update was skipped offline).
     * Distinct from a hard failure so the launcher can phrase it as a
     * warning. Must match the {@code exit 10} in setup.sh / setup.ps1.
     */
    static final int EXIT_WITH_WARNINGS = 10;

    private final SetupScriptLocator scriptLocator;
    private final Duration idleTimeout;
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();

    /**
     * Resolved model tag ({@link ModelSelection}) handed to the setup script via
     * the {@code WSBG_REASONING_MODEL} env var. Null/blank = the script's own
     * default (standalone script runs keep working without the launcher).
     */
    private String reasoningModelTag;

    EnvironmentSetup(Path appDirectory) {
        this(appDirectory, DEFAULT_IDLE_TIMEOUT);
    }

    /** Test seam: inject a short idle timeout. */
    EnvironmentSetup(Path appDirectory, Duration idleTimeout) {
        this.scriptLocator = new SetupScriptLocator(appDirectory);
        this.idleTimeout = idleTimeout;
    }

    /**
     * Forcefully terminates the running setup process. Called by the launcher's
     * shutdown hook to prevent orphaned child processes (e.g. winget, ollama
     * pull) from running indefinitely after the user closes the window.
     */
    /** Sets the model tag the setup script should install (see {@link ModelSelection}). */
    void setReasoningModelTag(String tag) {
        this.reasoningModelTag = tag;
    }

    void killActiveProcess() {
        Process p = activeProcess.getAndSet(null);
        if (p != null && p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    /**
     * Runs the platform-specific setup script synchronously.
     *
     * @param outputConsumer receives {@code (phase, detail)} — phase identifies
     *                       the concrete action (e.g. "Pulling model:version"),
     *                       detail is context like "42% — 739 MB / 3.3 GB".
     *                       Detail may be {@code null}.
     * @return {@code true} if the script exited with code 0
     * @throws IOException          if the script cannot be started
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    boolean run(BiConsumer<String, String> outputConsumer) throws IOException, InterruptedException {
        Path script = scriptLocator.resolveScript();
        if (script == null || !Files.exists(script)) {
            outputConsumer.accept("No setup script found", "skipping environment check");
            return true;
        }

        outputConsumer.accept("Running environment setup", script.getFileName().toString());

        ProcessBuilder pb = scriptLocator.createProcessBuilder(script);
        if (reasoningModelTag != null && !reasoningModelTag.isBlank()) {
            pb.environment().put(ModelSelection.MODEL_ENV, reasoningModelTag);
        }
        Process process = pb.start();
        activeProcess.set(process);

        IdleWatchdog watchdog = new IdleWatchdog(idleTimeout);
        watchdog.start(process);
        try {
            streamOutput(process, watchdog, outputConsumer);
            return awaitCompletion(process, watchdog, outputConsumer);
        } finally {
            watchdog.stop();
            activeProcess.set(null);
        }
    }

    /**
     * Reads process stdout line-by-line, strips ANSI escapes, classifies each
     * line, and emits structured (phase, detail) events to the consumer. A
     * fresh {@link ScriptOutputClassifier} carries the per-run phase state.
     */
    private void streamOutput(Process process, IdleWatchdog watchdog,
            BiConsumer<String, String> consumer) throws IOException {
        ScriptOutputClassifier classifier = new ScriptOutputClassifier();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Any output — even noise — proves the script is alive.
                watchdog.markOutput();

                String clean = classifier.stripAnsi(line).strip();
                if (clean.isEmpty())
                    continue;

                classifier.classify(clean, consumer);
            }
        }
    }

    /**
     * Waits for the process to finish. Stdout is already at EOF here, so the
     * {@link #TIMEOUT_MINUTES} wait only covers the exit gap; a script hung
     * mid-run was already killed by the idle watchdog.
     *
     * @return {@code true} if the process exited with code 0
     */
    private boolean awaitCompletion(Process process, IdleWatchdog watchdog,
            BiConsumer<String, String> consumer) throws InterruptedException {
        boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);

        if (watchdog.timedOut()) {
            consumer.accept("Setup timed out",
                    "no output for " + watchdog.timeout().toMinutes() + " minutes");
            return false;
        }
        if (!finished) {
            process.destroyForcibly();
            consumer.accept("Setup timed out", "after " + TIMEOUT_MINUTES + " minutes");
            return false;
        }

        int exitCode = process.exitValue();
        if (exitCode == EXIT_WITH_WARNINGS) {
            consumer.accept("Setup completed with warnings", null);
            return false;
        }
        if (exitCode != 0) {
            consumer.accept("Setup warning", "exited with code " + exitCode);
        }
        return exitCode == 0;
    }
}
