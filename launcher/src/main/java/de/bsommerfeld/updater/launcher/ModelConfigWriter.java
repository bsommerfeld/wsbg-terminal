package de.bsommerfeld.updater.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists the model choice from the launcher's model-choice UI as
 * {@code agent.model-tag} in {@code config.toml} — the single key both the
 * setup script (via {@link ModelSelection}) and the terminal runtime read.
 *
 * <p>
 * The write is a minimal line-surgery, mirroring the launcher's line-scan
 * readers: an existing {@code agent.model-tag}/{@code model-tag} line has its
 * value replaced in place (comments and layout untouched, so jshepherd's
 * comment blocks survive); a missing key is inserted directly under the
 * {@code [agent]} section header; a missing file/section gets a minimal
 * {@code [agent]} skeleton appended. The terminal's config layer declares the
 * key, so a later jshepherd re-persist carries the value forward.
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
        Path configFile = appDir.resolve("config.toml");
        String keyLine = "agent.model-tag = \"" + tag + "\"";
        try {
            if (!Files.exists(configFile)) {
                Files.writeString(configFile, "[agent]\n" + keyLine + "\n");
                return true;
            }

            List<String> lines = new ArrayList<>(Files.readAllLines(configFile));
            int agentSection = -1;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).strip();
                if (trimmed.startsWith("agent.model-tag") || trimmed.startsWith("model-tag")) {
                    int eq = trimmed.indexOf('=');
                    if (eq > 0) {
                        String key = trimmed.substring(0, eq).strip();
                        lines.set(i, key + " = \"" + tag + "\"");
                        Files.write(configFile, lines);
                        return true;
                    }
                }
                if (trimmed.equals("[agent]")) agentSection = i;
            }

            if (agentSection >= 0) {
                lines.add(agentSection + 1, keyLine);
            } else {
                lines.add("");
                lines.add("[agent]");
                lines.add(keyLine);
            }
            Files.write(configFile, lines);
            return true;
        } catch (IOException e) {
            log.log("Could not persist model choice to config.toml: " + e.getMessage());
            return false;
        }
    }
}
