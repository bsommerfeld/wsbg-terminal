package de.bsommerfeld.updater.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Rolls per-session {@code latest.log} files inside a log directory: archives
 * the previous session's {@code latest.log} to a timestamped name and prunes
 * the oldest archives beyond {@link #MAX_LOG_FILES}. Shared by
 * {@link SessionLog} (the launcher's own log) and {@link AppLauncher} (the
 * spawned terminal's log), which previously carried byte-for-byte duplicates.
 */
final class LogRotator {

    static final int MAX_LOG_FILES = 10;

    private static final DateTimeFormatter LOG_FILENAME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private LogRotator() {
    }

    /**
     * Archives the previous session's {@code latest.log} by renaming it to a
     * timestamp derived from its last-modified time, so {@code latest.log}
     * always represents the current session alone.
     */
    static void archiveLatestLog(Path logsDir) {
        Path latest = logsDir.resolve("latest.log");
        if (!Files.exists(latest)) return;
        try {
            String timestamp = Files.getLastModifiedTime(latest).toInstant()
                    .atZone(ZoneId.systemDefault())
                    .format(LOG_FILENAME);
            Files.move(latest, logsDir.resolve(timestamp + ".log"));
        } catch (IOException ignored) {
        }
    }

    /**
     * Deletes the oldest archived {@code .log} files when the count exceeds
     * {@link #MAX_LOG_FILES}. {@code latest.log} is excluded — it is the active
     * session, not an archive. Cleanup failure must never prevent a start.
     */
    static void purgeOldLogs(Path logsDir) {
        try (Stream<Path> files = Files.list(logsDir)) {
            var logs = files
                    .filter(p -> p.toString().endsWith(".log"))
                    .filter(p -> !p.getFileName().toString().equals("latest.log"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();

            if (logs.size() >= MAX_LOG_FILES) {
                for (int i = 0; i < logs.size() - MAX_LOG_FILES + 1; i++) {
                    Files.deleteIfExists(logs.get(i));
                }
            }
        } catch (IOException ignored) {
            // Cleanup failure must never prevent the launcher from starting
        }
    }
}
