package de.bsommerfeld.wsbg.terminal.core.debug;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * On-disk size of one directory, broken down by its top-level entries.
 *
 * <p>This is measurable at all only because the app owns exactly one directory
 * (the app data dir): runtime, models, caches, logs and config all live below
 * it, so its total IS the app's disk footprint and deleting it IS the uninstall.
 *
 * <p><strong>Cached, because a poll must not walk 20 GB every two seconds.</strong>
 * A snapshot is reused for {@link #TTL_MS}; the walk itself is cheap in practice
 * (a few thousand entries, sizes come from the directory metadata, no file is
 * read), and symlinks are never followed so a stray link cannot send it off into
 * the rest of the filesystem.
 */
public final class DirectoryUsage {

    /** How long a walk's result stays good enough for a dashboard. */
    static final long TTL_MS = 15_000;

    private static final Map<Path, Snapshot> CACHE = new ConcurrentHashMap<>();

    private DirectoryUsage() {
    }

    /** One top-level entry of the walked directory. */
    public record Entry(String name, long bytes, boolean directory, int files) {
    }

    /**
     * @param totalBytes  sum over every entry
     * @param files       regular files counted
     * @param entries     top-level entries, largest first
     * @param unreadable  paths the walk could not stat (their size is missing from the total)
     * @param sampledAtMs when the walk ran — a cached answer is honest about its age
     */
    public record Snapshot(Path path, long totalBytes, int files, List<Entry> entries,
            int unreadable, long sampledAtMs) {
    }

    /** Walks {@code dir}, or returns the cached walk when it is younger than the TTL. */
    public static Snapshot of(Path dir) {
        Snapshot cached = CACHE.get(dir);
        if (cached != null && System.currentTimeMillis() - cached.sampledAtMs() < TTL_MS) {
            return cached;
        }
        Snapshot fresh = walk(dir);
        CACHE.put(dir, fresh);
        return fresh;
    }

    private static Snapshot walk(Path dir) {
        long now = System.currentTimeMillis();
        if (!Files.isDirectory(dir)) {
            return new Snapshot(dir, 0, 0, List.of(), 0, now);
        }
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        int files = 0;
        int unreadable = 0;
        try (var children = Files.list(dir)) {
            for (Path child : children.toList()) {
                Sum sum = new Sum();
                sizeOf(child, sum);
                boolean isDir = Files.isDirectory(child);
                entries.add(new Entry(child.getFileName().toString(), sum.bytes, isDir, sum.files));
                total += sum.bytes;
                files += sum.files;
                unreadable += sum.unreadable;
            }
        } catch (IOException e) {
            unreadable++;
        }
        entries.sort(Comparator.comparingLong(Entry::bytes).reversed());
        return new Snapshot(dir, total, files, List.copyOf(entries), unreadable, now);
    }

    private static final class Sum {
        long bytes;
        int files;
        int unreadable;
    }

    private static void sizeOf(Path path, Sum sum) {
        try {
            if (!Files.isDirectory(path)) {
                sum.bytes += Files.size(path);
                sum.files++;
                return;
            }
            Files.walkFileTree(path, java.util.Set.<FileVisitOption>of(), Integer.MAX_VALUE,
                    new FileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                            if (a.isRegularFile()) {
                                sum.bytes += a.size();
                                sum.files++;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path f, IOException e) {
                            sum.unreadable++;
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path d, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            sum.unreadable++;
        }
    }
}
