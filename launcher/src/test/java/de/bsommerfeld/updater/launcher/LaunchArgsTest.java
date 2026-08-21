package de.bsommerfeld.updater.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the launcher's own command-line flags. Both are set by the
 * terminal's in-app buttons and must be CONSUMED here — a flag that leaks on to
 * the terminal's {@code main} is an unknown argument in the wrong process.
 */
class LaunchArgsTest {

    @Test
    void noFlags() {
        LaunchArgs args = LaunchArgs.parse(new String[0]);
        assertFalse(args.forceUpdate());
        assertFalse(args.installModel());
        assertEquals(0, args.forwardArgs().length);
    }

    @Test
    void installModelIsRecognisedAndStripped() {
        LaunchArgs args = LaunchArgs.parse(new String[]{"--install-model"});
        assertTrue(args.installModel());
        assertFalse(args.forceUpdate());
        assertEquals(0, args.forwardArgs().length, "the flag is ours, not the terminal's");
    }

    @Test
    void bothFlagsCoexistAndEverythingElseIsForwarded() {
        LaunchArgs args = LaunchArgs.parse(
                new String[]{"--force-update", "--offline", "--install-model", "foo"});
        assertTrue(args.forceUpdate());
        assertTrue(args.installModel());
        assertArrayEquals(new String[]{"--offline", "foo"}, args.forwardArgs());
    }
}
