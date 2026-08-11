package de.bsommerfeld.wsbg.terminal.ui.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * One-time cleanup of the data left behind by the removed features (deep dive,
 * watchlist, weather report). Whoever updates would otherwise keep those
 * leftovers forever - the deep-dive workspace alone reaches hundreds of
 * megabytes.
 *
 * <p>The run is marked with a stamp file in the app-data dir, so it happens
 * exactly once per installation; every later start returns immediately. It is
 * strictly additive to the boot path: every failure is logged and swallowed,
 * nothing here may keep the app from starting.
 *
 * <p><b>The path list is exhaustive on purpose.</b> Only the entries named
 * below are touched - the live data ({@code archive/headlines.jsonl},
 * {@code snapshots/}, the event journals, {@code instruments/}, the runtime
 * dirs and {@code config.toml}) is never looked at.
 */
final class RemovedFeatureCleanup {

    private static final Logger LOG = LoggerFactory.getLogger(RemovedFeatureCleanup.class);

    /** Marker: its presence means this cleanup already ran on this installation. */
    private static final String MARKER = ".removed-features-cleaned";

    /** Directories of removed features - deleted recursively, with their content. */
    private static final List<String> DIRECTORIES = List.of(
            "deepdive-neubau",
            "images/logos");

    /** Single files of removed features. */
    private static final List<String> FILES = List.of(
            "watchlist.json",
            "watchlist.json.tmp",
            "archive/deepdive-reports.jsonl",
            "archive/weather-reports.jsonl",
            "archive/flow-snapshots.jsonl",
            "dd-phase-times.csv");

    private RemovedFeatureCleanup() {
    }

    /**
     * Removes the leftovers once. Idempotent: the marker short-circuits every
     * later call, and a missing path is simply skipped.
     *
     * @param appDataDir the application data directory
     */
    static void run(Path appDataDir) {
        Path marker = appDataDir.resolve(MARKER);
        if (Files.exists(marker))
            return;

        int removed = 0;
        for (String dir : DIRECTORIES)
            removed += deleteRecursively(appDataDir.resolve(dir));
        for (String file : FILES)
            removed += deleteFile(appDataDir.resolve(file));
        removed += deletePrintTemps(appDataDir.resolve("tmp"));

        if (removed > 0)
            LOG.info("Removed {} leftover entries of the retired deep dive / watchlist / weather features", removed);

        try {
            Files.createDirectories(appDataDir);
            Files.writeString(marker, "");
        } catch (Exception e) {
            LOG.warn("Could not write cleanup marker at {}: {}", marker, e.getMessage());
        }
    }

    /** Deletes the {@code tmp/dd-print-*.html} spool files, nothing else in {@code tmp/}. */
    private static int deletePrintTemps(Path tmpDir) {
        if (!Files.isDirectory(tmpDir))
            return 0;
        int removed = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(tmpDir, "dd-print-*.html")) {
            for (Path entry : entries)
                removed += deleteFile(entry);
        } catch (Exception e) {
            LOG.warn("Could not scan {} for deep-dive print temporaries: {}", tmpDir, e.getMessage());
        }
        return removed;
    }

    private static int deleteFile(Path file) {
        if (!Files.isRegularFile(file))
            return 0;
        try {
            Files.delete(file);
            return 1;
        } catch (Exception e) {
            LOG.warn("Could not remove leftover file {}: {}", file, e.getMessage());
            return 0;
        }
    }

    private static int deleteRecursively(Path dir) {
        if (!Files.isDirectory(dir))
            return 0;
        int removed = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> deepestFirst = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : deepestFirst) {
                try {
                    Files.delete(path);
                    removed++;
                } catch (IOException e) {
                    LOG.warn("Could not remove leftover {}: {}", path, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not walk leftover directory {}: {}", dir, e.getMessage());
        }
        return removed;
    }
}
