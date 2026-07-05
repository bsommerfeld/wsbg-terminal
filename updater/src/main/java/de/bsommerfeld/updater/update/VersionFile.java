package de.bsommerfeld.updater.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes the {@code version.txt} marker in the app directory so
 * future runs can skip unchanged versions.
 *
 * <p>
 * Pure filesystem I/O for a single value, extracted out of the update
 * pipeline so the orchestrator carries no {@code Files} plumbing.
 */
public final class VersionFile {

    private final Path versionFile;

    public VersionFile(Path appDirectory) {
        this.versionFile = appDirectory.resolve("version.txt");
    }

    /**
     * Returns the recorded version tag, or {@code null} if no version file
     * exists yet or it cannot be read.
     */
    public String read() {
        if (!Files.exists(versionFile))
            return null;
        try {
            return Files.readString(versionFile).strip();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Writes the version tag to {@code version.txt}, creating the parent
     * directory if needed.
     */
    public void record(String version) throws IOException {
        Files.createDirectories(versionFile.getParent());
        Files.writeString(versionFile, version);
    }
}
