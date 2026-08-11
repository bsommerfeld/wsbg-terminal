package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.api.ReleaseChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the update-channel question's backend contract: when the launcher asks,
 * what an answer persists, and — the load-bearing part — that everything which
 * is not a clear yes stays on the stable channel.
 */
class ChannelSelectionTest {

    // ------------------------------------------------------------------
    // When the launcher asks
    // ------------------------------------------------------------------

    @Test
    void freshInstallHasNoAnswerOnRecord(@TempDir Path dir) {
        ChannelSelection.Result result = ChannelSelection.resolve(dir, new SessionLog(dir));
        assertFalse(result.userChosen(), "no config.toml at all — the launcher must ask");
        assertEquals(ReleaseChannel.STABLE, result.channel());
    }

    @Test
    void configWithoutTheKeyStillCountsAsUnanswered(@TempDir Path dir) throws IOException {
        // An install that predates the question: the file is fully populated,
        // just not with this key. That is exactly the case the question exists
        // for, so it must not read as an answer.
        writeConfig(dir, "[user]", "language = \"de\"", "auto-update = true");
        assertFalse(ChannelSelection.resolve(dir, new SessionLog(dir)).userChosen());
    }

    @Test
    void anEmptyValueIsNoAnswer(@TempDir Path dir) throws IOException {
        // jshepherd writes the declared key with its empty default the first
        // time the terminal persists the config — still unanswered.
        writeConfig(dir, "[user]", "experimental-updates = \"\"");
        assertFalse(ChannelSelection.resolve(dir, new SessionLog(dir)).userChosen());
    }

    @Test
    void garbageIsNoAnswerAndNeverOpensTheExperimentalChannel(@TempDir Path dir)
            throws IOException {
        writeConfig(dir, "[user]", "experimental-updates = \"maybe\"");

        ChannelSelection.Result result = ChannelSelection.resolve(dir, new SessionLog(dir));
        assertFalse(result.userChosen(), "a typo must put the question again");
        assertEquals(ReleaseChannel.STABLE, result.channel());
    }

    // ------------------------------------------------------------------
    // What an answer means
    // ------------------------------------------------------------------

    @Test
    void yesRunsTheExperimentalChannelAndStopsTheAsking(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[user]", "experimental-updates = \"yes\"");

        ChannelSelection.Result result = ChannelSelection.resolve(dir, new SessionLog(dir));
        assertTrue(result.userChosen());
        assertEquals(ReleaseChannel.EXPERIMENTAL, result.channel());
    }

    @Test
    void noRunsTheStableChannelAndStopsTheAsking(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[user]", "experimental-updates = \"no\"");

        ChannelSelection.Result result = ChannelSelection.resolve(dir, new SessionLog(dir));
        assertTrue(result.userChosen());
        assertEquals(ReleaseChannel.STABLE, result.channel());
    }

    @Test
    void theDottedFormAHandEditedConfigMayCarryIsAcceptedToo(@TempDir Path dir)
            throws IOException {
        writeConfig(dir, "[user]", "user.experimental-updates = \"yes\"");
        assertEquals(ReleaseChannel.EXPERIMENTAL,
                ChannelSelection.resolve(dir, new SessionLog(dir)).channel());
    }

    // ------------------------------------------------------------------
    // What a confirmed choice writes
    // ------------------------------------------------------------------

    @Test
    void choiceIsPersistedAndReadBackOnTheNextStart(@TempDir Path dir) {
        assertTrue(ConfigWriter.write(dir, "[user]", ChannelSelection.CONFIG_KEY,
                ChannelSelection.YES, new SessionLog(dir)));

        ChannelSelection.Result next = ChannelSelection.resolve(dir, new SessionLog(dir));
        assertTrue(next.userChosen());
        assertEquals(ReleaseChannel.EXPERIMENTAL, next.channel());
    }

    @Test
    void answeringLandsInsideTheUserSectionBesideTheOtherKeys(@TempDir Path dir)
            throws IOException {
        writeConfig(dir, "[agent]", "agent.model-tag = \"\"", "", "[user]", "auto-update = true");

        assertTrue(ConfigWriter.write(dir, "[user]", ChannelSelection.CONFIG_KEY,
                ChannelSelection.NO, new SessionLog(dir)));

        String config = Files.readString(dir.resolve("config.toml"));
        assertTrue(config.indexOf("experimental-updates = \"no\"") > config.indexOf("[user]"),
                "the answer must land inside [user]");
        assertTrue(config.contains("auto-update = true"), "neighbouring keys survive");
    }

    // ------------------------------------------------------------------
    // Translation
    // ------------------------------------------------------------------

    @Test
    void bothRowsAreWordedInBothLanguages() {
        assertEquals("Stabil", LauncherI18n.translate("Stable", "de"));
        assertEquals("Risikomanagement", LauncherI18n.translate("Risk management", "de"));
        assertEquals("Experimentell", LauncherI18n.translate("Experimental", "de"));
        assertEquals("100er Hebel", LauncherI18n.translate("100x leverage", "de"));
        assertEquals("Welche Updates willst du?",
                LauncherI18n.translate("Which updates do you want?", "de"));
        assertEquals("Stable", LauncherI18n.translate("Stable", "en"));
    }

    private static void writeConfig(Path dir, String... lines) throws IOException {
        Files.write(dir.resolve("config.toml"), java.util.List.of(lines));
    }
}
