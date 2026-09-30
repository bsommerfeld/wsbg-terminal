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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void aSocketOpensEchoesAndClosesThroughTheEngine() throws Exception {
        try (EngineProcess engine = new EngineProcess(fakeBrowser("echo"), Duration.ofSeconds(20))) {
            BlockingQueue<SocketFrame> frames = new LinkedBlockingQueue<>();
            long id = engine.openSocket("wss://push.example.org/", List.of("v2"), null, frames::add, 5_000);
            assertEquals(new SocketFrame.Opened(id, "v2"), frames.poll(10, TimeUnit.SECONDS));

            engine.sendSocket(new SocketFrame.Message(id, false, "hallo".getBytes(StandardCharsets.UTF_8)));
            SocketFrame.Message echo = (SocketFrame.Message) frames.poll(10, TimeUnit.SECONDS);
            assertEquals("hallo", new String(echo.data(), StandardCharsets.UTF_8));

            engine.sendSocket(new SocketFrame.Close(id, 4000, "fertig"));
            assertEquals(new SocketFrame.Close(id, 4000, "fertig"), frames.poll(10, TimeUnit.SECONDS));
            FetchException gone = assertThrows(FetchException.class,
                    () -> engine.sendSocket(new SocketFrame.Message(id, true, new byte[1])));
            assertTrue(gone.getMessage().contains("closed"), gone.getMessage());
        }
    }

    @Test
    void aStoppingEngineClosesEverySocketItHeld() throws Exception {
        try (EngineProcess engine = new EngineProcess(fakeBrowser("crash"), Duration.ofSeconds(20))) {
            BlockingQueue<SocketFrame> frames = new LinkedBlockingQueue<>();
            long id = engine.openSocket("wss://push.example.org/", List.of(), null, frames::add, 5_000);
            SocketFrame.Close close = (SocketFrame.Close) frames.poll(10, TimeUnit.SECONDS);
            assertEquals(id, close.id());
            assertEquals(1006, close.code());
            assertTrue(close.reason().contains("about to crash"), close.reason());
        }
    }

    @Test
    void clientsOfOneCommandShareOneEngineUntilTheLastLetsGo() throws Exception {
        List<String> command = fakeBrowser("echo");
        EngineProcess first = EngineProcess.shared(command);
        EngineProcess second = EngineProcess.shared(command);
        assertSame(first, second);

        first.close();
        assertEquals(200, second.exchange(request(1, "https://example.org/", 1_000)).status(),
                "one client left - the engine still runs");
        second.close();
        assertThrows(FetchException.class, () -> second.exchange(request(2, "https://example.org/", 1_000)));

        EngineProcess third = EngineProcess.shared(command);
        assertNotSame(first, third, "after the last close, the next client starts a new one");
        third.close();
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
