package de.bsommerfeld.updater.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the language choice's backend contract: when the launcher asks
 * ({@link LauncherI18n#explicit()}), what a confirmed choice persists
 * ({@link ConfigWriter}), and that the choice survives into the next start.
 */
class LanguageSelectionTest {

    // ------------------------------------------------------------------
    // When the launcher asks
    // ------------------------------------------------------------------

    @Test
    void freshInstallHasNoLanguageOnRecord(@TempDir Path dir) {
        assertFalse(new LauncherI18n(dir).explicit(),
                "no config.toml at all — the launcher must ask");
    }

    @Test
    void configWithoutTheKeyStillCountsAsUnanswered(@TempDir Path dir) throws IOException {
        // The setup script writes a skeleton config before any language was
        // ever picked — that must not silently count as an answer.
        writeConfig(dir, "[agent]", "agent.model-tag = \"gemma4:e4b\"");
        assertFalse(new LauncherI18n(dir).explicit());
    }

    @Test
    void aPersistedLanguageStopsTheAsking(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[user]", "language = \"en\"");
        LauncherI18n i18n = new LauncherI18n(dir);
        assertTrue(i18n.explicit());
        assertEquals("en", i18n.language());
    }

    // ------------------------------------------------------------------
    // What a confirmed choice writes
    // ------------------------------------------------------------------

    @Test
    void choiceIsPersistedAndReadBackOnTheNextStart(@TempDir Path dir) {
        assertTrue(ConfigWriter.write(dir, "[user]", "language", "en", new SessionLog(dir)));

        LauncherI18n next = new LauncherI18n(dir);
        assertTrue(next.explicit());
        assertEquals("en", next.language());
    }

    @Test
    void anExistingLanguageLineIsReplacedInPlace(@TempDir Path dir) throws IOException {
        writeConfig(dir,
                "[user]",
                "# Display language code (e.g., 'de' for German). Default: 'de'",
                "language = \"de\"",
                "auto-update = true");

        assertTrue(ConfigWriter.write(dir, "[user]", "language", "en", new SessionLog(dir)));

        String config = Files.readString(dir.resolve("config.toml"));
        assertTrue(config.contains("language = \"en\""));
        assertFalse(config.contains("language = \"de\""));
        // Comment block and neighbouring keys survive the surgery.
        assertTrue(config.contains("# Display language code"));
        assertTrue(config.contains("auto-update = true"));
    }

    @Test
    void missingKeyIsInsertedUnderItsSection(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[agent]", "agent.model-tag = \"\"", "", "[user]", "auto-update = true");

        assertTrue(ConfigWriter.write(dir, "[user]", "language", "en", new SessionLog(dir)));

        String config = Files.readString(dir.resolve("config.toml"));
        int userSection = config.indexOf("[user]");
        int languageLine = config.indexOf("language = \"en\"");
        assertTrue(languageLine > userSection, "language must land inside [user]");
        assertEquals("en", new LauncherI18n(dir).language());
    }

    @Test
    void commentProseWithAnEqualsSignIsNeverMistakenForTheKey(@TempDir Path dir)
            throws IOException {
        writeConfig(dir, "[user]", "# language = the display language; empty = default");

        assertTrue(ConfigWriter.write(dir, "[user]", "language", "en", new SessionLog(dir)));

        String config = Files.readString(dir.resolve("config.toml"));
        assertTrue(config.contains("# language = the display language; empty = default"),
                "the comment line must stay untouched");
        assertEquals("en", new LauncherI18n(dir).language());
    }

    // ------------------------------------------------------------------
    // Translation
    // ------------------------------------------------------------------

    @Test
    void everyOfferedLanguageLabelsItsOwnScreen() {
        // The choice screen words each option in ITS language, so both
        // directions must resolve without falling back to a raw key.
        assertEquals("Sprache wählen", LauncherI18n.translate("Choose your language", "de"));
        assertEquals("Choose your language", LauncherI18n.translate("Choose your language", "en"));
        assertEquals(2, LauncherI18n.LANGUAGES.length);
    }

    private static void writeConfig(Path dir, String... lines) throws IOException {
        Files.write(dir.resolve("config.toml"), java.util.List.of(lines));
    }
}
