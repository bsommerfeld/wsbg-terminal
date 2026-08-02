package de.bsommerfeld.updater.launcher;

import java.nio.file.Path;

/**
 * Persists the model choice from the launcher's model-choice UI as
 * {@code agent.model-tag} in {@code config.toml} — the single key both the
 * setup script (via {@link ModelSelection}) and the terminal runtime read.
 * The line-surgery itself lives in {@link ConfigWriter}, shared with the
 * language choice.
 */
final class ModelConfigWriter {

    private ModelConfigWriter() {
    }

    /**
     * Writes the chosen tag. Returns false (after logging) when the file
     * cannot be written — the chosen tag still drives THIS run via the env
     * var; only persistence for the next start is lost.
     */
    static boolean write(Path appDir, String tag, SessionLog log) {
        return ConfigWriter.write(appDir, "[agent]", "agent.model-tag", tag, log);
    }
}
