package de.bsommerfeld.tinyfetch.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The whole stack against a local server: FFM binding, headers on the wire,
 * decoding, redirects, cookies, revalidation, pacing and walls. Skipped when
 * libcurl-impersonate is not installed ({@code .script/natives.sh}).
 */
class TinyFetchTest {

    private static final HostPolicy INSTANT = HostPolicy.defaults().withMinInterval(Duration.ZERO);

    @TempDir
    Path temporary;

    private HttpServer server;
    private String base;
    private final List<Map<String, List<String>>> received = new CopyOnWriteArrayList<>();
    private final List<List<String>> receivedOrder = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    @BeforeAll
    static void requireLibrary() {
        assumeTrue(TinyFetch.libraryAvailable(), "libcurl-impersonate not installed - run .script/natives.sh");
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
        received.add(exchange.getRequestHeaders());
        byte[] requestBody = exchange.getRequestBody().readAllBytes();

        switch (path) {
            case "/gzip" -> {
                ByteArrayOutputStream packed = new ByteArrayOutputStream();
                try (GZIPOutputStream gzip = new GZIPOutputStream(packed)) {
                    gzip.write("entpackt ✓".getBytes(StandardCharsets.UTF_8));
                }
                exchange.getResponseHeaders().add("content-encoding", "gzip");
                exchange.getResponseHeaders().add("content-type", "text/plain; charset=utf-8");
                send(exchange, 200, packed.toByteArray());
            }
            case "/redirect" -> {
                exchange.getResponseHeaders().add("location", "/target");
                send(exchange, 302, new byte[0]);
            }
            case "/set-cookie" -> {
                exchange.getResponseHeaders().add("set-cookie", "session=abc123; Path=/");
                send(exchange, 200, "ok".getBytes());
            }
            case "/etag" -> {
                String sent = exchange.getRequestHeaders().getFirst("if-none-match");
                exchange.getResponseHeaders().add("etag", "\"v1\"");
                if ("\"v1\"".equals(sent)) {
                    send(exchange, 304, null);
                } else {
                    send(exchange, 200, "gecachter Inhalt".getBytes(StandardCharsets.UTF_8));
                }
            }
            case "/throttle" -> {
                exchange.getResponseHeaders().add("retry-after", "120");
                send(exchange, 429, "slow down".getBytes());
            }
            case "/captcha" -> {
                exchange.getResponseHeaders().add("content-type", "text/html");
                send(exchange, 200, "<title>Reddit - Prove your humanity</title>".getBytes());
            }
            case "/echo" -> send(exchange, 200, requestBody);
            case "/big" -> send(exchange, 200, new byte[64 * 1024]);
            default -> send(exchange, 200, ("hello " + path).getBytes());
        }
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body == null ? -1 : body.length == 0 ? -1 : body.length);
        if (body != null && body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    private TinyFetch client() throws FetchException {
        return TinyFetch.builder().defaultPolicy(INSTANT).build();
    }

    @Test
    void plainGetGoesThroughTheNativeStack() throws Exception {
        try (TinyFetch fetch = client()) {
            FetchResponse response = fetch.fetch(FetchRequest.page(base + "/hello"));
            assertEquals(200, response.status());
            assertTrue(response.ok());
            assertEquals("hello /hello", response.text());
            assertEquals("HTTP/1.1", response.httpVersion());
            assertTrue(fetch.libraryVersion().contains("BoringSSL"), fetch.libraryVersion());
        }
    }

    @Test
    void browserHeadersArriveAsBuilt() throws Exception {
        String userAgent;
        try (TinyFetch fetch = client()) {
            userAgent = fetch.userAgent();
            fetch.fetch(FetchRequest.page(base + "/headers"));
        }
        Map<String, List<String>> headers = received.getFirst();
        assertEquals(userAgent, headers.get("User-agent").getFirst());
        assertEquals("navigate", headers.get("Sec-fetch-mode").getFirst());
        assertEquals("gzip, deflate, br, zstd", headers.get("Accept-encoding").getFirst());
        assertTrue(headers.get("Accept-language").getFirst().startsWith("de-DE"));
    }

    @Test
    void compressedBodiesAreDecoded() throws Exception {
        try (TinyFetch fetch = client()) {
            assertEquals("entpackt ✓", fetch.fetch(FetchRequest.page(base + "/gzip")).text());
        }
    }

    @Test
    void redirectsAreFollowed() throws Exception {
        try (TinyFetch fetch = client()) {
            FetchResponse response = fetch.fetch(FetchRequest.page(base + "/redirect"));
            assertEquals("hello /target", response.text());
            assertTrue(response.url().toString().endsWith("/target"));
        }
    }

    @Test
    void cookiesComeBackAndSurviveARestart() throws Exception {
        Path jar = temporary.resolve("cookies.txt");
        try (TinyFetch fetch = TinyFetch.builder().defaultPolicy(INSTANT).cookieFile(jar).build()) {
            fetch.fetch(FetchRequest.page(base + "/set-cookie"));
            fetch.fetch(FetchRequest.page(base + "/after"));
        }
        assertEquals("session=abc123", received.get(1).get("Cookie").getFirst());
        assertTrue(Files.readString(jar).contains("abc123"));

        try (TinyFetch restarted = TinyFetch.builder().defaultPolicy(INSTANT).cookieFile(jar).build()) {
            restarted.fetch(FetchRequest.page(base + "/next-day"));
        }
        assertEquals("session=abc123", received.get(2).get("Cookie").getFirst());
    }

    @Test
    void unchangedResourcesAreRevalidatedNotRedownloaded() throws Exception {
        try (TinyFetch fetch = client()) {
            FetchResponse first = fetch.fetch(FetchRequest.data(base + "/etag"));
            FetchResponse second = fetch.fetch(FetchRequest.data(base + "/etag"));
            assertFalse(first.revalidated());
            assertTrue(second.revalidated());
            assertEquals(200, second.status());
            assertEquals("gecachter Inhalt", second.text());
        }
        assertEquals("\"v1\"", received.get(1).get("If-none-match").getFirst());
    }

    @Test
    void postSendsItsBodyBinarySafe() throws Exception {
        byte[] body = {0, 1, 2, (byte) 0xff, 'x'};
        try (TinyFetch fetch = client()) {
            FetchResponse response = fetch.fetch(FetchRequest.data(base + "/echo")
                    .post("application/octet-stream", body));
            assertArrayEquals(body, response.body());
        }
        assertEquals("application/octet-stream", received.getFirst().get("Content-type").getFirst());
    }

    @Test
    void throttlePausesTheHostWithoutTouchingTheNetwork() throws Exception {
        try (TinyFetch fetch = client()) {
            FetchResponse response = fetch.fetch(FetchRequest.page(base + "/throttle"));
            assertEquals(Wall.THROTTLED, response.wall());
            assertFalse(response.ok());

            CooldownException paused = assertThrows(CooldownException.class,
                    () -> fetch.fetch(FetchRequest.page(base + "/anything")));
            assertEquals(Wall.THROTTLED, paused.reason());
            assertTrue(fetch.pausedUntil("127.0.0.1").isPresent());
        }
        assertEquals(1, received.size(), "the paused request never left");
    }

    @Test
    void captchaPageWithStatus200IsRecognised() throws Exception {
        try (TinyFetch fetch = client()) {
            FetchResponse response = fetch.fetch(FetchRequest.page(base + "/captcha"));
            assertEquals(200, response.status());
            assertEquals(Wall.CHALLENGE, response.wall());
            assertFalse(response.ok());
        }
    }

    @Test
    void requestsToOneHostAreSpacedOut() throws Exception {
        HostPolicy paced = HostPolicy.defaults().withMinInterval(Duration.ofMillis(300)).withJitter(0);
        List<Long> times = Collections.synchronizedList(new ArrayList<>());
        try (TinyFetch fetch = TinyFetch.builder().defaultPolicy(paced).build()) {
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        fetch.fetch(FetchRequest.page(base + "/paced"));
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
        for (int i = 1; i < sorted.size(); i++) {
            long gapMillis = (sorted.get(i) - sorted.get(i - 1)) / 1_000_000;
            assertTrue(gapMillis >= 280, "gap was " + gapMillis + " ms");
        }
    }

    @Test
    void oversizedBodiesFail() throws Exception {
        try (TinyFetch fetch = TinyFetch.builder().defaultPolicy(INSTANT).maxBodyBytes(1024).build()) {
            FetchException failure = assertThrows(FetchException.class,
                    () -> fetch.fetch(FetchRequest.page(base + "/big")));
            assertTrue(failure.getMessage().contains("larger than 1024"), failure.getMessage());
        }
    }

    @Test
    void unlockTakesTheSessionOverOnce() throws Exception {
        FakeEngine engine = FakeEngine.create(temporary, FakeEngine.Mode.OK);
        try (TinyFetch fetch = TinyFetch.builder().browser(Browser.CHROMIUM_EMBEDDED).defaultPolicy(INSTANT)
                .unlocker(ProcessUnlocker.builder(engine.command()).build()).build()) {
            assertTrue(fetch.canUnlock());
            assertFalse(fetch.hasCookie("127.0.0.1", "session"));

            fetch.unlock(base + "/", Set.of("session"));
            fetch.unlock(base + "/", Set.of("session"));

            assertTrue(fetch.hasCookie("127.0.0.1", "session"));
            assertEquals(1, engine.calls().size(), "the second unlock found the session there");
            fetch.fetch(FetchRequest.data(base + "/api"));
        }
        assertEquals("session=unlocked", received.getFirst().get("Cookie").getFirst());
    }

    @Test
    void unsolvedCaptchaPausesTheHostAndASolvedOneEndsThePause() throws Exception {
        FakeEngine refusing = FakeEngine.create(temporary, FakeEngine.Mode.CAPTCHA);
        try (TinyFetch fetch = TinyFetch.builder().browser(Browser.CHROMIUM_EMBEDDED).defaultPolicy(INSTANT)
                .unlocker(ProcessUnlocker.builder(refusing.command()).build()).build()) {
            assertThrows(CaptchaRequiredException.class, () -> fetch.unlock(base + "/", Set.of("session")));
            assertTrue(fetch.pausedUntil("127.0.0.1").isPresent());
            assertThrows(CooldownException.class, () -> fetch.fetch(FetchRequest.page(base + "/")));
        }
        try (TinyFetch fetch = TinyFetch.builder().browser(Browser.CHROMIUM_EMBEDDED).defaultPolicy(INSTANT)
                .unlocker(ProcessUnlocker.builder(refusing.command())
                        .captchaSolver(challenge -> java.util.Optional.of(challenge.openWindow("t")))
                        .build())
                .build()) {
            fetch.fetch(FetchRequest.page(base + "/throttle"));
            assertTrue(fetch.pausedUntil("127.0.0.1").isPresent());
            fetch.unlock(base + "/", Set.of("session"));
            assertTrue(fetch.pausedUntil("127.0.0.1").isEmpty(), "a fresh session ends the pause");
        }
    }

    @Test
    void unlockerMustBeTheSameBrowser() throws Exception {
        FakeEngine engine = FakeEngine.create(temporary, FakeEngine.Mode.OK);
        ProcessUnlocker cef = ProcessUnlocker.builder(engine.command()).build();
        assertThrows(IllegalStateException.class,
                () -> TinyFetch.builder().browser(Browser.CHROME).unlocker(cef).build());
    }

    @Test
    void embeddedChromiumSpeaksCefsLanguageUnlessToldOtherwise() throws Exception {
        try (TinyFetch fetch = TinyFetch.builder().browser(Browser.CHROMIUM_EMBEDDED).defaultPolicy(INSTANT).build()) {
            fetch.fetch(FetchRequest.page(base + "/language"));
        }
        assertEquals("en-US,en;q=0.9", received.getFirst().get("Accept-language").getFirst());
    }

    @Test
    void closedClientRefuses() throws Exception {
        TinyFetch fetch = client();
        fetch.close();
        assertThrows(FetchException.class, () -> fetch.fetch(FetchRequest.page(base + "/")));
    }
}
