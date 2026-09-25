package de.bsommerfeld.updater;

import de.bsommerfeld.tinyupdate.api.GitHubRepository;
import de.bsommerfeld.tinyupdate.api.ReleaseChannel;
import de.bsommerfeld.tinyupdate.handoff.Handoff;
import de.bsommerfeld.updater.UpdateStatus.Failed;
import de.bsommerfeld.updater.UpdateStatus.Failure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Runs are driven end to end against a release on localhost; the scripts and commands are POSIX. */
@DisabledOnOs(OS.WINDOWS)
class UpdateRunTest {

    @TempDir
    Path root;

    private final List<UpdateStatus> statuses = new ArrayList<>();

    private Handoff handoff(FakeRelease release, long waitFor, String postUpdate, List<String> relaunch) {
        return new Handoff(waitFor, "App", root.resolve("app"),
                new GitHubRepository("o", "r", release.apiBase()), ReleaseChannel.STABLE,
                "", "", postUpdate, relaunch);
    }

    private static long goneProcess() throws Exception {
        Process process = new ProcessBuilder("true").start();
        process.waitFor();
        return process.pid();
    }

    private void awaitFile(Path file) throws InterruptedException {
        for (int i = 0; i < 100 && !Files.exists(file); i++) {
            Thread.sleep(20);
        }
    }

    @Test
    void appliesUpdate_runsPostUpdate_relaunches() throws Exception {
        Path relaunched = root.resolve("relaunched");
        try (FakeRelease release = new FakeRelease("v2", Map.of(
                "lib/app-2.jar", "jar",
                "bin/setup.sh", "echo setup > \"$PWD/setup-ran\"; exit 10"))) {
            UpdateRun run = new UpdateRun(handoff(release, goneProcess(), "bin/setup.sh",
                    List.of("touch", relaunched.toString())), Duration.ofSeconds(5), statuses::add);

            assertTrue(run.run());
        }

        Path app = root.resolve("app");
        assertEquals("jar", Files.readString(app.resolve("lib/app-2.jar")));
        assertEquals("v2", Files.readString(app.resolve("version.txt")));
        assertTrue(Files.exists(app.resolve("setup-ran")), "post-update ran in the install, warnings are no failure");
        awaitFile(relaunched);
        assertTrue(Files.exists(relaunched));

        assertInstanceOf(UpdateStatus.Waiting.class, statuses.getFirst());
        assertTrue(statuses.stream().anyMatch(UpdateStatus.Updating.class::isInstance));
        assertTrue(statuses.stream().anyMatch(UpdateStatus.Finishing.class::isInstance));
        assertInstanceOf(UpdateStatus.Relaunching.class, statuses.getLast());
    }

    @Test
    void waitsForTheApplication_andGivesUpWhenItStays() throws Exception {
        Process application = new ProcessBuilder("sleep", "30").start();
        try (FakeRelease release = new FakeRelease("v2", Map.of("lib/a.jar", "a"))) {
            UpdateRun run = new UpdateRun(handoff(release, application.pid(), null, List.of("true")),
                    Duration.ofMillis(200), statuses::add);

            assertFalse(run.run());
        } finally {
            application.destroy();
        }
        assertEquals(new Failed(Failure.STILL_RUNNING, "App"), statuses.getLast());
        assertFalse(Files.exists(root.resolve("app/lib/a.jar")), "nothing is touched before the application is gone");
    }

    @Test
    void failingPostUpdate_stopsBeforeRelaunch_andPassesTheExitCode() throws Exception {
        Path relaunched = root.resolve("relaunched");
        try (FakeRelease release = new FakeRelease("v2", Map.of("bin/setup.sh", "exit 3"))) {
            UpdateRun run = new UpdateRun(handoff(release, goneProcess(), "bin/setup.sh",
                    List.of("touch", relaunched.toString())), Duration.ofSeconds(5), statuses::add);

            assertFalse(run.run());
        }
        assertEquals(new Failed(Failure.POST_UPDATE, "3"), statuses.getLast());
        assertFalse(Files.exists(relaunched));
    }

    @Test
    void unreachableRelease_failsTheUpdateStep() throws Exception {
        FakeRelease release = new FakeRelease("v2", Map.of("lib/a.jar", "a"));
        release.close();
        UpdateRun run = new UpdateRun(handoff(release, goneProcess(), null, List.of("true")),
                Duration.ofSeconds(5), statuses::add);

        assertFalse(run.run());
        assertInstanceOf(Failed.class, statuses.getLast());
        assertEquals(Failure.UPDATE, ((Failed) statuses.getLast()).failure());
    }

    @Test
    void unstartableRelaunch_failsTheRelaunchStep() throws Exception {
        try (FakeRelease release = new FakeRelease("v2", Map.of("lib/a.jar", "a"))) {
            UpdateRun run = new UpdateRun(handoff(release, goneProcess(), null,
                    List.of(root.resolve("no-such-launcher").toString())), Duration.ofSeconds(5), statuses::add);

            assertFalse(run.run());
        }
        assertEquals(Failure.RELAUNCH, ((Failed) statuses.getLast()).failure());
    }
}
