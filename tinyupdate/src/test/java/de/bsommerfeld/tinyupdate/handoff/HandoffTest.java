package de.bsommerfeld.tinyupdate.handoff;

import de.bsommerfeld.tinyupdate.api.GitHubRepository;
import de.bsommerfeld.tinyupdate.api.ReleaseChannel;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HandoffTest {

    private static Handoff handoff(String prefix, String platform, String postUpdate) {
        return new Handoff(4242, "WSBG Terminal", Path.of("/data/WSBG/app"),
                new GitHubRepository("owner", "repo", "http://localhost:8080"), ReleaseChannel.EXPERIMENTAL,
                prefix, platform, postUpdate, List.of("/Applications/WSBG Terminal.app/Contents/MacOS/WSBG Terminal", "--flag"));
    }

    @Test
    void parse_readsBackWhatToArgumentsWrote() {
        Handoff original = handoff("wsbg", "macos-aarch64", "bin/setup.sh");
        assertEquals(original, Handoff.parse(original.toArguments()));
    }

    @Test
    void emptyValues_stayOffTheCommandLine_andParseBackEmpty() {
        Handoff original = handoff("", "", null);
        List<String> arguments = original.toArguments();

        assertFalse(arguments.contains("--assets"));
        assertFalse(arguments.contains("--platform"));
        assertFalse(arguments.contains("--post-update"));
        assertFalse(arguments.contains(""));
        assertEquals(original, Handoff.parse(arguments));
    }

    @Test
    void relaunch_isEverythingAfterTheSeparator_verbatim() {
        Handoff parsed = Handoff.parse(List.of("--wait", "1", "--name", "App", "--install", "/x",
                "--repository", "o/r", "--", "run", "--wait", "--"));
        assertEquals(List.of("run", "--wait", "--"), parsed.relaunch());
    }

    @Test
    void parse_withoutApi_usesGitHub() {
        Handoff parsed = Handoff.parse(List.of("--wait", "1", "--name", "App", "--install", "/x",
                "--repository", "o/r", "--", "run"));
        assertEquals(GitHubRepository.of("o/r"), parsed.repository());
        assertEquals(ReleaseChannel.STABLE, parsed.channel());
    }

    @Test
    void parse_rejectsMissingRequiredOption() {
        assertThrows(IllegalArgumentException.class, () -> Handoff.parse(List.of(
                "--name", "App", "--install", "/x", "--repository", "o/r", "--", "run")));
    }

    @Test
    void parse_rejectsUnknownOptionAndOptionWithoutValue() {
        assertThrows(IllegalArgumentException.class, () -> Handoff.parse(List.of("--bogus", "1")));
        assertThrows(IllegalArgumentException.class, () -> Handoff.parse(List.of("--wait")));
    }

    @Test
    void parse_rejectsMissingRelaunch() {
        assertThrows(IllegalArgumentException.class, () -> Handoff.parse(List.of("--wait", "1",
                "--name", "App", "--install", "/x", "--repository", "o/r")));
    }
}
