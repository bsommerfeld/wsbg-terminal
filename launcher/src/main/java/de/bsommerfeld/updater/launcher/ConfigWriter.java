package de.bsommerfeld.updater.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists a single key into {@code config.toml} — the launcher's write side,
 * mirroring the tiny line-scan readers ({@link LauncherI18n},
 * {@link ModelSelection}, {@link LaunchArgs}). Used by the launcher's choice
 * screens: the model tag ({@link ModelConfigWriter}) and the display language.
 *
 * <p>
 * The write is a minimal line-surgery: an existing line for the key has its
 * value replaced in place (comments and layout untouched, so jshepherd's
 * comment blocks survive); a missing key is inserted directly under its section
 * header; a missing file/section gets a minimal skeleton appended. The
 * terminal's config layer declares every key, so a later jshepherd re-persist
 * carries the value forward with its full comment block.
 */
final class ConfigWriter {

    private ConfigWriter() {
    }

    /**
     * Writes {@code key = "value"} into {@code section}. Returns false (after
     * logging) when the file cannot be written — the choice still drives THIS
     * run in memory; only persistence for the next start is lost.
     *
     * @param section the TOML section header including brackets, e.g. {@code [user]}
     * @param key     the key as jshepherd writes it — dotted ({@code agent.model-tag})
     *                or bare ({@code language}); the bare form is always accepted
     *                too when scanning, so a hand-edited config still matches
     */
    static boolean write(Path appDir, String section, String key, String value, SessionLog log) {
        Path configFile = appDir.resolve("config.toml");
        String keyLine = key + " = \"" + value + "\"";
        try {
            if (!Files.exists(configFile)) {
                Files.writeString(configFile, section + "\n" + keyLine + "\n");
                return true;
            }

            String bareKey = key.substring(key.lastIndexOf('.') + 1);
            List<String> lines = new ArrayList<>(Files.readAllLines(configFile));
            int sectionLine = -1;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).strip();
                // Comment blocks carry prose with "=" in it — never a key line.
                if (trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    String lineKey = trimmed.substring(0, eq).strip();
                    if (lineKey.equals(key) || lineKey.equals(bareKey)) {
                        lines.set(i, lineKey + " = \"" + value + "\"");
                        Files.write(configFile, lines);
                        return true;
                    }
                }
                if (trimmed.equals(section)) sectionLine = i;
            }

            if (sectionLine >= 0) {
                lines.add(sectionLine + 1, keyLine);
            } else {
                lines.add("");
                lines.add(section);
                lines.add(keyLine);
            }
            Files.write(configFile, lines);
            return true;
        } catch (IOException e) {
            log.log("Could not persist " + key + " to config.toml: " + e.getMessage());
            return false;
        }
    }
}
