package de.bsommerfeld.tinybrowser;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * Starts a new profile as a copy of an existing one - for the terminal, the
 * profile its old embedded browser has grown since it was installed. A site
 * that knows that browser knows this one from the first request.
 *
 * <p>Copied once, while the target is still empty; afterwards the two live
 * separate lives. Chromium's process locks and its GPU and code caches are
 * left behind: the locks would claim the copy for a process that is not
 * running, the caches are rebuilt on demand.
 */
final class ProfileSeed {

    private static final Set<String> SKIPPED = Set.of(
            "SingletonLock", "SingletonSocket", "SingletonCookie", "RunningChromeVersion",
            "Cache", "Code Cache", "GPUCache", "GrShaderCache", "GraphiteDawnCache", "ShaderCache",
            "DawnGraphiteCache", "DawnWebGPUCache", "Crashpad");

    private ProfileSeed() {
    }

    /** @return whether a copy was made */
    static boolean seed(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source) || (Files.isDirectory(target) && !isEmpty(target))) {
            return false;
        }
        Path staging = target.resolveSibling(target.getFileName() + ".seeding");
        deleteQuietly(staging);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.equals(source) && SKIPPED.contains(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(staging.resolve(source.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String name = file.getFileName().toString();
                if (!SKIPPED.contains(name) && !name.startsWith("BrowserMetrics") && attributes.isRegularFile()) {
                    Files.copy(file, staging.resolve(source.relativize(file).toString()),
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        // Only a complete copy becomes the profile; a half one would be worse than none.
        deleteQuietly(target);
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    private static void deleteQuietly(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.delete(entry);
                } catch (IOException ignored) {
                    // left for the next attempt
                }
            });
        }
    }
}
