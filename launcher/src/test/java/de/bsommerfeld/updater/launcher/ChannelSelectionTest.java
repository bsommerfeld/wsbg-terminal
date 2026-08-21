package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.api.ReleaseChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins how the launcher reads the update channel out of {@code config.toml} -
 * the load-bearing part being that everything which is not a clear yes stays
 * on the stable channel. The launcher no longer asks; the switch lives in the
 * terminal's settings, and this is only the read side of it.
 */
class ChannelSelectionTest {

    // ------------------------------------------------------------------
    // Everything that is not a clear yes stays stable
    // ------------------------------------------------------------------

    @Test
    void freshInstallRunsStable(@TempDir Path dir) {
        assertEquals(ReleaseChannel.STABLE, ChannelSelection.resolve(dir, new SessionLog(dir)));
    }

    @Test
    void configWithoutTheKeyRunsStable(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[user]", "language = \"de\"", "auto-update = true");
        assertEquals(ReleaseChannel.STABLE, ChannelSelection.resolve(dir, new SessionLog(dir)));
    }

    @Test
    void anEmptyValueRunsStable(@TempDir Path dir) throws IOException {
        // jshepherd writes the declared key with its empty default the first
        // time the terminal persists the config.
        writeConfig(dir, "[user]", "experimental-updates = \"\"");
        assertEquals(ReleaseChannel.STABLE, ChannelSelection.resolve(dir, new SessionLog(dir)));
    }

    @Test
    void garbageNeverOpensTheExperimentalChannel(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[user]", "experimental-updates = \"maybe\"");
        assertEquals(ReleaseChannel.STABLE, ChannelSelection.resolve(dir, new SessionLog(dir)));
    }

    // ------------------------------------------------------------------
    // What an answer means
    // ------------------------------------------------------------------

    @Test
    void yesRunsTheExperimentalChannel(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[user]", "experimental-updates = \"yes\"");
        assertEquals(ReleaseChannel.EXPERIMENTAL,
                ChannelSelection.resolve(dir, new SessionLog(dir)));
    }

    @Test
    void noRunsTheStableChannel(@TempDir Path dir) throws IOException {
        writeConfig(dir, "[user]", "experimental-updates = \"no\"");
        assertEquals(ReleaseChannel.STABLE, ChannelSelection.resolve(dir, new SessionLog(dir)));
    }

    @Test
    void theDottedFormAHandEditedConfigMayCarryIsAcceptedToo(@TempDir Path dir)
            throws IOException {
        writeConfig(dir, "[user]", "user.experimental-updates = \"yes\"");
        assertEquals(ReleaseChannel.EXPERIMENTAL,
                ChannelSelection.resolve(dir, new SessionLog(dir)));
    }

    @Test
    void theTerminalsWriteIsReadBackOnTheNextStart(@TempDir Path dir) throws IOException {
        // The settings panel persists the toggle through jshepherd, which
        // writes the bare key inside [user] - the shape asserted here.
        writeConfig(dir, "[agent]", "agent.model-tag = \"\"", "",
                "[user]", "auto-update = true", "experimental-updates = \"yes\"");
        assertEquals(ReleaseChannel.EXPERIMENTAL,
                ChannelSelection.resolve(dir, new SessionLog(dir)));
    }

    private static void writeConfig(Path dir, String... lines) throws IOException {
        Files.write(dir.resolve("config.toml"), java.util.List.of(lines));
    }
}
