package de.bsommerfeld.tinyfetch.engine;

import de.bsommerfeld.tinyfetch.api.FetchException;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The engine as a child process, against {@link FakeBrowser}. */
class EngineProcessTest {

    private static final Duration SHORT_GRACE = Duration.ofSeconds(2);

    private static List<String> fakeBrowser(String mode) throws URISyntaxException {
        String classPath = location(Frames.class) + System.getProperty("path.separator") + location(FakeBrowser.class);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(java, "-cp", classPath, FakeBrowser.class.getName(), "--mode", mode);
    }

    private static String location(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static EngineRequest request(long id, String url, long timeoutMillis) {
        return new EngineRequest(id, url, "GET", List.of(), null, null, timeoutMillis);
    }

    @Test
    void greetsAndAnswersEveryRequestByItsId() throws Exception {
        try (EngineProcess engine = new EngineProcess(fakeBrowser("echo"), Duration.ofSeconds(20))) {
            engine.start();
            List<CompletableFuture<EngineAnswer>> answers = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                long id = i;
                answers.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return engine.exchange(request(id, "https://example.org/" + id, 1_000));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (int i = 1; i <= 5; i++) {
                EngineAnswer answer = answers.get(i - 1).join();
                assertEquals(i, answer.id());
                assertEquals(200, answer.status());
                assertEquals("GET https://example.org/" + i, new String(answer.body(), StandardCharsets.UTF_8));
                assertEquals(List.of(Map.entry("x-request-id", Long.toString(i))), answer.headers());
            }
        }
    }

    @Test
    void aCrashFailsWhatIsInFlightAndTheRestartWaits() throws Exception {
        try (EngineProcess engine = new EngineProcess(fakeBrowser("crash"), Duration.ofSeconds(20))) {
            FetchException crashed = assertThrows(FetchException.class,
                    () -> engine.exchange(request(1, "https://example.org/", 1_000)));
            assertTrue(crashed.getMessage().contains("about to crash"), crashed.getMessage());

            FetchException waiting = assertThrows(FetchException.class,
                    () -> engine.exchange(request(2, "https://example.org/", 1_000)));
            assertTrue(waiting.getMessage().contains("started again from"), waiting.getMessage());
        }
    }

    @Test
    void anEngineThatNeverAnswersRunsOutOfPatience() throws Exception {
        try (EngineProcess engine = new EngineProcess(fakeBrowser("silent"), SHORT_GRACE)) {
            FetchException failure = assertThrows(FetchException.class,
                    () -> engine.exchange(request(1, "https://example.org/slow", 200)));
            assertTrue(failure.getMessage().contains("no answer in time"), failure.getMessage());
        }
    }

    @Test
    void anEngineThatNeverGreetsIsStillStarting() throws Exception {
        try (EngineProcess engine = new EngineProcess(fakeBrowser("mute"), SHORT_GRACE)) {
            FetchException failure = assertThrows(FetchException.class,
                    () -> engine.exchange(request(1, "https://example.org/", 200)));
            assertTrue(failure.getMessage().contains("still starting"), failure.getMessage());
        }
    }

    @Test
    void closedEngineRefuses() throws Exception {
        EngineProcess engine = new EngineProcess(fakeBrowser("echo"), SHORT_GRACE);
        engine.exchange(request(1, "https://example.org/", 1_000));
        engine.close();
        FetchException failure = assertThrows(FetchException.class,
                () -> engine.exchange(request(2, "https://example.org/", 1_000)));
        assertTrue(failure.getMessage().contains("closed"), failure.getMessage());
    }
}
