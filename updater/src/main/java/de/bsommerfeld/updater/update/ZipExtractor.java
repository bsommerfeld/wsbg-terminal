package de.bsommerfeld.updater.update;

import de.bsommerfeld.updater.model.FileEntry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts only the files flagged as outdated from an in-memory zip archive
 * into the app directory.
 *
 * <p>
 * No per-file progress — extraction is brief and the UI shows an
 * indeterminate dot during this phase.
 */
public final class ZipExtractor {

    private final Path appDirectory;
    private final Consumer<String> trace;

    public ZipExtractor(Path appDirectory, Consumer<String> trace) {
        this.appDirectory = appDirectory;
        this.trace = trace;
    }

    /**
     * Extracts every entry whose path is listed as outdated in {@code diff};
     * all other entries in the zip are skipped.
     */
    public void extractOutdated(byte[] zipData, UpdateCheckResult diff) throws IOException {
        Set<String> outdatedPaths = diff.outdated().stream()
                .map(FileEntry::path)
                .collect(Collectors.toSet());

        int total = outdatedPaths.size();
        int extracted = 0;
        trace.accept("Extracting " + total + " files");

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory())
                    continue;

                String name = entry.getName().replace('\\', '/');
                if (!outdatedPaths.contains(name))
                    continue;

                extracted++;
                Path target = appDirectory.resolve(name);
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        trace.accept("Extraction complete: " + extracted + "/" + total + " files");
    }
}
