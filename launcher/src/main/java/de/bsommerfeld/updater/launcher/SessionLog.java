package de.bsommerfeld.updater.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Per-session append log for the launcher. Construction archives any previous
 * {@code latest.log} (via {@link LogRotator}), points at a fresh
 * {@code latest.log}, and prunes old archives — replacing the former static
 * {@code sessionLogFile} + {@code initLogging} state in {@code LauncherMain}
 * with an instance both the pipeline thread and the shutdown/error paths share.
 *
 * <p>Logging failures are silently ignored — the launcher must never crash
 * because of a log write.
 */
final class SessionLog {

    private static final DateTimeFormatter LOG_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path sessionLogFile;

    /**
     * Archives the previous {@code latest.log}, opens a fresh one under
     * {@code <appDir>/logs/launcher}, purges old archives, and writes the
     * session banner.
     */
    SessionLog(Path appDir) {
        Path logsDir = appDir.resolve("logs/launcher");
        LogRotator.archiveLatestLog(logsDir);
        this.sessionLogFile = logsDir.resolve("latest.log");
        LogRotator.purgeOldLogs(logsDir);
        log("--- Launcher session ---");
    }

    /** Appends a timestamped line to the current session's log file. */
    void log(String message) {
        try {
            String timestamp = LocalDateTime.now().format(LOG_TIMESTAMP);
            String line = "[" + timestamp + "] " + message + "\n";
            System.err.print(line);
            Files.writeString(sessionLogFile, line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Logging failure must never crash the launcher
        }
    }

    /** Writes the full (causal) stack trace to the session log file. */
    void logStackTrace(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
            for (StackTraceElement frame : t.getStackTrace()) {
                sb.append("  at ").append(frame).append('\n');
            }
        }
        log(sb.toString());
    }
}
