package de.bsommerfeld.tinyfetch.api;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** The engine protocol, against a shell script that speaks it. */
class ProcessUnlockerTest {

    private static final URI PAGE = URI.create("https://www.reddit.com/");
    private static final LinkedHashSet<String> AWAIT = new LinkedHashSet<>(List.of("loid", "token_v2"));

    @TempDir
    Path directory;

    @BeforeAll
    static void needsAPosixShell() {
        assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"), "the fake engine is a sh script");
    }

    @Test
    void handsBackTheCookiesAndSpeaksTheProtocol() throws Exception {
        FakeEngine engine = FakeEngine.create(directory, FakeEngine.Mode.OK);
        ProcessUnlocker unlocker = ProcessUnlocker.builder(engine.command()).build();

        assertEquals(List.of(FakeEngine.COOKIE), unlocker.unlock(PAGE, AWAIT));
        assertEquals("--url https://www.reddit.com/ --await loid,token_v2", engine.calls().getFirst());
        assertEquals(Browser.CHROMIUM_EMBEDDED, unlocker.browser());
    }

    @Test
    void captchaWithoutAnyoneToSolveItFails() throws Exception {
        FakeEngine engine = FakeEngine.create(directory, FakeEngine.Mode.CAPTCHA);
        ProcessUnlocker unlocker = ProcessUnlocker.builder(engine.command()).build();

        CaptchaRequiredException failure = assertThrows(CaptchaRequiredException.class,
                () -> unlocker.unlock(PAGE, AWAIT));
        assertEquals("www.reddit.com", failure.host());
        assertEquals(1, engine.calls().size(), "no window without a solver");
    }

    @Test
    void solverOpensTheWindowAndThePersonSolvesIt() throws Exception {
        FakeEngine engine = FakeEngine.create(directory, FakeEngine.Mode.CAPTCHA);
        AtomicReference<CaptchaChallenge> asked = new AtomicReference<>();
        ProcessUnlocker unlocker = ProcessUnlocker.builder(engine.command())
                .captchaSolver(challenge -> {
                    asked.set(challenge);
                    return Optional.of(challenge.openWindow("Bestätigung für Reddit"));
                })
                .build();

        assertEquals(List.of(FakeEngine.COOKIE), unlocker.unlock(PAGE, AWAIT));
        assertEquals("www.reddit.com", asked.get().host());
        String window = engine.calls().get(1);
        assertTrue(window.endsWith("--visible true --window-title Bestätigung für Reddit"), window);
    }

    @Test
    void personDeclining() throws Exception {
        FakeEngine engine = FakeEngine.create(directory, FakeEngine.Mode.CAPTCHA);
        ProcessUnlocker unlocker = ProcessUnlocker.builder(engine.command())
                .captchaSolver(challenge -> Optional.empty())
                .build();

        assertThrows(CaptchaRequiredException.class, () -> unlocker.unlock(PAGE, AWAIT));
    }

    @Test
    void windowClosedUnsolved() throws Exception {
        FakeEngine engine = FakeEngine.create(directory, FakeEngine.Mode.CLOSED);
        ProcessUnlocker unlocker = ProcessUnlocker.builder(engine.command())
                .captchaSolver(challenge -> Optional.of(challenge.openWindow("x")))
                .build();

        CaptchaRequiredException failure = assertThrows(CaptchaRequiredException.class,
                () -> unlocker.unlock(PAGE, AWAIT));
        assertTrue(failure.getMessage().contains("closed unsolved"), failure.getMessage());
    }

    @Test
    void engineFailureCarriesItsReason() throws Exception {
        FakeEngine engine = FakeEngine.create(directory, FakeEngine.Mode.FAIL);
        ProcessUnlocker unlocker = ProcessUnlocker.builder(engine.command()).build();

        FetchException failure = assertThrows(FetchException.class, () -> unlocker.unlock(PAGE, AWAIT));
        assertTrue(failure.getMessage().contains("exit 1"), failure.getMessage());
        assertTrue(failure.getMessage().contains("page never finished loading"), failure.getMessage());
    }

    @Test
    void engineThatNeverAnswersIsGivenUpOn() throws Exception {
        FakeEngine engine = FakeEngine.create(directory, FakeEngine.Mode.HANG);
        ProcessUnlocker unlocker = ProcessUnlocker.builder(engine.command()).timeout(Duration.ofMillis(300)).build();

        FetchException failure = assertThrows(FetchException.class, () -> unlocker.unlock(PAGE, AWAIT));
        assertTrue(failure.getMessage().contains("no answer"), failure.getMessage());
    }

    @Test
    void prepareRunsTheEngineWithoutAPage() throws Exception {
        FakeEngine engine = FakeEngine.create(directory, FakeEngine.Mode.OK);
        ProcessUnlocker.builder(engine.command()).build().prepare(Duration.ofSeconds(10));
        assertEquals("--prepare true", engine.calls().getFirst());

        java.nio.file.Path other = java.nio.file.Files.createDirectories(directory.resolve("broken"));
        FakeEngine broken = FakeEngine.create(other, FakeEngine.Mode.FAIL);
        assertThrows(FetchException.class,
                () -> ProcessUnlocker.builder(broken.command()).build().prepare(Duration.ofSeconds(10)));
    }

    @Test
    void currentJavaIsThisRuntimes() {
        assertTrue(java.nio.file.Files.isExecutable(ProcessUnlocker.currentJava()), ProcessUnlocker.currentJava().toString());
    }

    @Test
    void tinyUnlockCommandLine() {
        List<String> command = ProcessUnlocker.tinyUnlockCommand(Path.of("/jdk/bin/java"),
                List.of(Path.of("a.jar"), Path.of("b.jar")), Path.of("/bundle"), Path.of("/profile"), Path.of("/old"));
        assertEquals("/jdk/bin/java", command.getFirst());
        assertTrue(command.contains("de.bsommerfeld.tinyunlock.UnlockMain"));
        assertEquals("/profile", command.get(command.indexOf("--profile") + 1));
        assertEquals("/old", command.get(command.indexOf("--seed-profile") + 1));
        assertTrue(command.contains("a.jar" + System.getProperty("path.separator") + "b.jar"));
    }
}
