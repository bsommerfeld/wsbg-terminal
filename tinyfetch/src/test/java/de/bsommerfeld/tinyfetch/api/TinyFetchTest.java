package de.bsommerfeld.tinyfetch.api;

import de.bsommerfeld.tinyfetch.engine.Engine;
import de.bsommerfeld.tinyfetch.engine.EngineAnswer;
import de.bsommerfeld.tinyfetch.engine.EngineRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TinyFetch's own half - pace, walls, what reaches the engine and what comes
 * back - against a stand-in engine that answers from memory.
 */
class TinyFetchTest {

    private static final HostPolicy INSTANT = HostPolicy.defaults().withMinInterval(Duration.ZERO);

    /** Records every request; answers with the function, or 200 "ok" without one. */
    private static final class ScriptedEngine implements Engine {
        final List<EngineRequest> requests = new CopyOnWriteArrayList<>();
        Function<EngineRequest, EngineAnswer> answer = request -> answer(request, 200, "text/plain", "ok");
        boolean closed;

        @Override
        public EngineAnswer exchange(EngineRequest request) {
            requests.add(request);
            return answer.apply(request);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final ScriptedEngine engine = new ScriptedEngine();

    private TinyFetch client() throws FetchException {
        return TinyFetch.builder().engine(engine).defaultPolicy(INSTANT).build();
    }

    private static EngineAnswer answer(EngineRequest request, int status, String contentType, String body) {
        return new EngineAnswer(request.id(), status, request.url(), List.of(Map.entry("content-type", contentType)),
                body.getBytes(StandardCharsets.UTF_8), null);
    }

    @Test
    void requestReachesTheEngineAsDescribed() throws Exception {
        byte[] body = {0, 1, 2, (byte) 0xff, 'x'};
        try (TinyFetch fetch = TinyFetch.builder().engine(engine).defaultPolicy(INSTANT)
                .anchor("api.nasdaq.com", "https://www.nasdaq.com/").build()) {
            fetch.fetch(FetchRequest.of("https://api.nasdaq.com/api/quote?x=1")
                    .header("Accept", "application/json")
                    .post("application/octet-stream", body)
                    .timeout(Duration.ofSeconds(7)));
            fetch.fetch(FetchRequest.of("https://www.reddit.com/r/x/new.json"));
        }
        EngineRequest sent = engine.requests.getFirst();
        assertEquals("https://api.nasdaq.com/api/quote?x=1", sent.url());
        assertEquals("POST", sent.method());
        assertEquals(List.of(Map.entry("accept", "application/json"),
                Map.entry("content-type", "application/octet-stream")), sent.headers());
        assertArrayEquals(body, sent.body());
        assertEquals("https://www.nasdaq.com/", sent.anchor());
        assertEquals(7_000, sent.timeoutMillis());

        EngineRequest plain = engine.requests.get(1);
        assertEquals("GET", plain.method());
        assertNull(plain.body());
        assertNull(plain.anchor(), "no anchor of its own - the engine parks on the host's root");
        assertTrue(plain.id() != sent.id());
    }

    @Test
    void answerComesBackWithItsHeadersAndFinalAddress() throws Exception {
        engine.answer = request -> new EngineAnswer(request.id(), 200, "https://www.reddit.com/after-redirect",
                List.of(Map.entry("content-type", "text/plain; charset=ISO-8859-1"), Map.entry("x-multi", "a"),
                        Map.entry("x-multi", "b")),
                "Grüße".getBytes(StandardCharsets.ISO_8859_1), null);
        try (TinyFetch fetch = client()) {
            FetchResponse response = fetch.fetch(FetchRequest.of("https://www.reddit.com/"));
            assertTrue(response.ok());
            assertEquals("Grüße", response.text());
            assertEquals("https://www.reddit.com/after-redirect", response.url().toString());
            assertEquals(List.of("a", "b"), response.headers().get("X-Multi"));
        }
    }

    @Test
    void throttlePausesTheHostWithoutTouchingTheEngine() throws Exception {
        engine.answer = request -> new EngineAnswer(request.id(), 429, request.url(),
                List.of(Map.entry("retry-after", "120")), "slow down".getBytes(), null);
        try (TinyFetch fetch = client()) {
            FetchResponse response = fetch.fetch(FetchRequest.of("https://www.reddit.com/a"));
            assertEquals(Wall.THROTTLED, response.wall());
            assertFalse(response.ok());

            CooldownException paused = assertThrows(CooldownException.class,
                    () -> fetch.fetch(FetchRequest.of("https://www.reddit.com/b")));
            assertEquals(Wall.THROTTLED, paused.reason());
            assertTrue(fetch.pausedUntil("www.reddit.com").isPresent());
            assertTrue(fetch.pausedUntil("oauth.reddit.com").isEmpty(), "the pause is the host's alone");
        }
        assertEquals(1, engine.requests.size(), "the paused request never left");
    }

    @Test
    void captchaPageWithStatus200IsRecognised() throws Exception {
        engine.answer = request -> answer(request, 200, "text/html", "<title>Reddit - Prove your humanity</title>");
        try (TinyFetch fetch = client()) {
            FetchResponse response = fetch.fetch(FetchRequest.of("https://www.reddit.com/"));
            assertEquals(200, response.status());
            assertEquals(Wall.CHALLENGE, response.wall());
            assertFalse(response.ok());
        }
    }

    @Test
    void noAnswerIsAFailureNotAResponse() throws Exception {
        engine.answer = request -> EngineAnswer.failed(request.id(), "page fetch failed: TypeError: Failed to fetch");
        try (TinyFetch fetch = client()) {
            FetchException failure = assertThrows(FetchException.class,
                    () -> fetch.fetch(FetchRequest.of("https://www.reddit.com/")));
            assertTrue(failure.getMessage().contains("Failed to fetch"), failure.getMessage());
            assertTrue(fetch.pausedUntil("www.reddit.com").isEmpty(), "silence is no wall");
        }
    }

    @Test
    void requestsToOneHostAreSpacedOut() throws Exception {
        HostPolicy paced = HostPolicy.defaults().withMinInterval(Duration.ofMillis(300)).withJitter(0);
        List<Long> times = Collections.synchronizedList(new ArrayList<>());
        try (TinyFetch fetch = TinyFetch.builder().engine(engine).defaultPolicy(paced).build()) {
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        fetch.fetch(FetchRequest.of("https://www.reddit.com/paced"));
                        times.add(System.nanoTime());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            for (Thread thread : threads) {
                thread.join();
            }
        }
        List<Long> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        assertEquals(3, sorted.size());
        for (int i = 1; i < sorted.size(); i++) {
            long gapMillis = (sorted.get(i) - sorted.get(i - 1)) / 1_000_000;
            assertTrue(gapMillis >= 280, "gap was " + gapMillis + " ms");
        }
    }

    @Test
    void closedClientRefusesAndStopsTheEngine() throws Exception {
        TinyFetch fetch = client();
        fetch.close();
        assertTrue(engine.closed);
        assertThrows(FetchException.class, () -> fetch.fetch(FetchRequest.of("https://www.reddit.com/")));
    }

    @Test
    void withoutAnEngineThereIsNoClient() {
        assertThrows(IllegalStateException.class, () -> TinyFetch.builder().build());
    }

    @Test
    void theEngineCommandRunsTinyBrowserWithItsDirectories() {
        List<String> command = BrowserEngine.of(List.of(Path.of("/opt/engine/*")),
                        Path.of("/data/chromium"), Path.of("/data/profile"))
                .seedProfile(Path.of("/old/profile"))
                .java(Path.of("/runtime/bin/java"))
                .command();
        assertEquals("/runtime/bin/java", command.getFirst());
        assertTrue(command.containsAll(List.of("-cp", "/opt/engine/*", "de.bsommerfeld.tinybrowser.BrowserMain")));
        assertEquals(List.of("--chromium", "/data/chromium", "--profile", "/data/profile",
                "--seed-profile", "/old/profile"), command.subList(command.size() - 6, command.size()));
    }
}
