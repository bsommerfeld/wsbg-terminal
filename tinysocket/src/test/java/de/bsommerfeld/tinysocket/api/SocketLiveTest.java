package de.bsommerfeld.tinysocket.api;

import de.bsommerfeld.tinyfetch.api.BrowserEngine;
import de.bsommerfeld.tinyfetch.api.FetchRequest;
import de.bsommerfeld.tinyfetch.api.HostPolicy;
import de.bsommerfeld.tinyfetch.api.TinyFetch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The whole chain for real, against a local {@link EchoServer}: TinySocket and
 * TinyFetch on one engine, sockets opened by Chromium in a hidden tab.
 *
 * <p>Opt-in: {@code mvn package -pl tinybrowser -am -DskipTests}, then
 * {@code mvn test -pl tinysocket -Dtest=SocketLiveTest -Dtest.excludedGroups=visual}.
 * Chromium comes from {@code tinybrowser/target/chromium} (installed on first
 * use), the profile stays in {@code tinybrowser/target/socket-profile}.
 */
@Tag("live")
class SocketLiveTest {

    private static EchoServer server;
    private static TinyFetch fetch;
    private static TinySocket sockets;

    /** Writes down what it heard, one line per call. */
    private static final class Heard implements SocketListener {
        final BlockingQueue<Object> heard = new LinkedBlockingQueue<>();

        @Override
        public void onText(WebSocket socket, String text) {
            heard.add(text);
        }

        @Override
        public void onBinary(WebSocket socket, byte[] data) {
            heard.add(data);
        }

        @Override
        public void onClose(WebSocket socket, int code, String reason) {
            heard.add("close " + code + " " + reason);
        }

        Object next() throws InterruptedException {
            return heard.poll(30, TimeUnit.SECONDS);
        }
    }

    @BeforeAll
    static void start() throws Exception {
        Path target = Path.of(System.getProperty("tinybrowser.target", "../tinybrowser/target")).toAbsolutePath();
        assumeTrue(Files.isDirectory(target.resolve("engine")), "TinyBrowser not packaged - mvn package -pl tinybrowser -am");
        BrowserEngine engine = BrowserEngine.of(List.of(target.resolve("classes"), target.resolve("engine").resolve("*")),
                target.resolve("chromium"), target.resolve("socket-profile"));
        server = new EchoServer();
        fetch = TinyFetch.builder().engine(engine)
                .policy("127.0.0.1", HostPolicy.defaults().withMinInterval(Duration.ZERO)).build();
        sockets = TinySocket.builder().engine(engine).openTimeout(Duration.ofSeconds(60)).build();
    }

    @AfterAll
    static void stop() throws Exception {
        if (sockets != null) {
            sockets.close();
        }
        if (fetch != null) {
            fetch.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private static String url(String path) {
        return "ws://" + server.base() + path;
    }

    @Test
    void theHandshakeIsTheBrowsers() throws Exception {
        assertEquals(200, fetch.fetch(FetchRequest.of("http://" + server.base() + "/cookie")).status());

        Heard heard = new Heard();
        WebSocket socket = sockets.open(url("/hello"), List.of("v2.tinysocket", "v1.tinysocket"), heard);
        assertEquals("v2.tinysocket", socket.protocol());
        Map<String, String> handshake = server.handshakes.get("/hello");
        System.out.println("Handshake: " + handshake);
        assertTrue(handshake.get("User-Agent").contains("Chrome/"), handshake.get("User-Agent"));
        assertEquals("http://" + server.base(), handshake.get("Origin"), "the anchor's origin - the host's own root");
        assertTrue(String.valueOf(handshake.get("Cookie")).contains("session=browser"),
                "the cookie TinyFetch's tab earned goes along: one browser, one profile");
        assertEquals(1, ProcessHandle.current().children()
                .filter(child -> child.info().commandLine().orElse("").contains("BrowserMain")).count(),
                "TinyFetch and TinySocket share one engine");
        socket.close();
        assertEquals("close 1000 ", heard.next());
    }

    @Test
    void textAndBinaryComeBackAsSent() throws Exception {
        Heard heard = new Heard();
        WebSocket socket = sockets.open(url("/echo"), heard);

        String text = "Kurs 12,34 € - \u0001 mit Trenner, \"Zitat\" und </script>";
        socket.send(text);
        assertEquals(text, heard.next());

        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        socket.send(bytes);
        assertArrayEquals(bytes, (byte[]) heard.next());

        // Past what one router message carries: both come home in parts.
        byte[] big = new byte[1_000_000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31);
        }
        socket.send(big);
        assertArrayEquals(big, (byte[]) heard.next());
        String longText = "ä".repeat(600_000);
        socket.send(longText);
        assertEquals(longText, heard.next());
        // Odd in front: a part's end falls between the two halves of an emoji.
        String emoji = "x" + "\uD83D\uDE80".repeat(200_000);
        socket.send(emoji);
        assertEquals(emoji, heard.next(), "no emoji broken where the message was split");

        socket.close(4000, "fertig");
        assertEquals("close 4000 fertig", heard.next());
        assertFalse(socket.isOpen());
    }

    @Test
    void theServersCloseReachesTheListener() throws Exception {
        Heard heard = new Heard();
        sockets.open(url("/bye"), heard);
        assertEquals("close 4001 tschüss", heard.next());
    }

    @Test
    void aRefusedHandshakeFailsTheOpen() {
        WebSocketException refused = assertThrows(WebSocketException.class,
                () -> sockets.open(url("/refuse"), new Heard()));
        assertTrue(refused.getMessage().contains("refused (1006"), refused.getMessage());
    }

    @Test
    void aFullHouseUpToChromiumsCeilings() throws Exception {
        record Guest(WebSocket socket, Heard heard) {
        }
        List<Guest> house = new ArrayList<>();
        List<Exception> failures = new ArrayList<>();
        AtomicInteger echoed = new AtomicInteger();
        List<Thread> openers = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < 255; i++) {
            String name = "socket " + i;
            openers.add(Thread.ofVirtual().start(() -> {
                try {
                    Heard heard = new Heard();
                    WebSocket socket = sockets.open(url("/crowd"), heard);
                    synchronized (house) {
                        house.add(new Guest(socket, heard));
                    }
                    socket.send(name);
                    if (name.equals(heard.next())) {
                        echoed.incrementAndGet();
                    }
                } catch (Exception e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                }
            }));
        }
        for (Thread opener : openers) {
            opener.join();
        }
        System.out.println("255 sockets opened and echoed in "
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms");
        assertTrue(failures.isEmpty(), failures.isEmpty() ? "" : failures.size() + "x, " + failures.getFirst());
        assertEquals(255, echoed.get());

        WebSocketException perHost = assertThrows(WebSocketException.class,
                () -> sockets.open(url("/crowd"), new Heard()));
        assertTrue(perHost.getMessage().contains("255 WebSockets to " + server.base()), perHost.getMessage());
        String otherHost = "ws://localhost:" + server.base().substring(server.base().indexOf(':') + 1) + "/crowd";
        Heard last = new Heard();
        house.add(new Guest(sockets.open(otherHost, last), last));
        WebSocketException full = assertThrows(WebSocketException.class, () -> sockets.open(otherHost, new Heard()));
        assertTrue(full.getMessage().contains("256 WebSockets at most"), full.getMessage());

        house.forEach(guest -> guest.socket().close());
        for (Guest guest : house) {
            assertEquals("close 1000 ", guest.heard().next());
        }
    }
}
