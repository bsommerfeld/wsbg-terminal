package de.bsommerfeld.tinysocket.api;

import de.bsommerfeld.tinyfetch.engine.SocketEngine;
import de.bsommerfeld.tinyfetch.engine.SocketFrame;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TinySocket's own half - the handshake wait, what reaches the engine, what
 * reaches the listener, and when - against a stand-in engine the test speaks
 * for.
 */
class TinySocketTest {

    /** Records what reaches it; answers every open with the function - before open's own return, as fast as can be. */
    private static final class ScriptedEngine implements SocketEngine {
        final List<SocketFrame.Open> opens = new CopyOnWriteArrayList<>();
        final BlockingQueue<SocketFrame> sent = new LinkedBlockingQueue<>();
        final Map<Long, Consumer<SocketFrame>> events = new ConcurrentHashMap<>();
        Function<SocketFrame.Open, SocketFrame> firstWord =
                open -> new SocketFrame.Opened(open.id(), open.protocols().isEmpty() ? "" : open.protocols().getFirst());
        long nextId;
        volatile boolean closed;

        @Override
        public synchronized long openSocket(String url, List<String> protocols, String anchor,
                Consumer<SocketFrame> events, long timeoutMillis) {
            SocketFrame.Open open = new SocketFrame.Open(++nextId, url, protocols, anchor);
            opens.add(open);
            this.events.put(open.id(), events);
            SocketFrame first = firstWord.apply(open);
            if (first != null) {
                events.accept(first);
            }
            return open.id();
        }

        @Override
        public void sendSocket(SocketFrame frame) {
            sent.add(frame);
        }

        /** The engine speaks. */
        void tell(SocketFrame frame) {
            events.get(frame.id()).accept(frame);
        }

        SocketFrame nextSent() throws InterruptedException {
            return sent.poll(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Writes down what it heard, one line per call. */
    private static final class Heard implements SocketListener {
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        @Override
        public void onText(WebSocket socket, String text) {
            lines.add("text " + text);
        }

        @Override
        public void onBinary(WebSocket socket, byte[] data) {
            lines.add("binary " + Arrays.toString(data));
        }

        @Override
        public void onClose(WebSocket socket, int code, String reason) {
            lines.add("close " + code + " " + reason);
        }

        String next() throws InterruptedException {
            return lines.poll(5, TimeUnit.SECONDS);
        }
    }

    private final ScriptedEngine engine = new ScriptedEngine();
    private final Heard heard = new Heard();

    private TinySocket.Builder builder() {
        return TinySocket.builder().engine(engine);
    }

    @Test
    void opensOnTheHostsAnchorWithTheProtocolTheServerTook() throws Exception {
        try (TinySocket sockets = builder().anchor("Push.Example.org", "https://www.example.org/").build()) {
            WebSocket quotes = sockets.open("wss://push.example.org/q", List.of("v2", "v1"), heard);
            assertTrue(quotes.isOpen());
            assertEquals("v2", quotes.protocol());
            assertEquals(new SocketFrame.Open(1, "wss://push.example.org/q", List.of("v2", "v1"),
                    "https://www.example.org/"), engine.opens.getFirst());

            sockets.open("ws://127.0.0.1:9000/", heard);
            assertNull(engine.opens.get(1).anchor(), "no anchor of its own - the engine parks on the host's root");
        }
    }

    @Test
    void messagesArriveInOrderAndTheCloseLast() throws Exception {
        try (TinySocket sockets = builder().build()) {
            WebSocket socket = sockets.open("wss://push.example.org/", heard);
            engine.tell(new SocketFrame.Message(1, false, "kurs ä".getBytes(StandardCharsets.UTF_8)));
            engine.tell(new SocketFrame.Message(1, true, new byte[] {1, 2}));
            engine.tell(new SocketFrame.Message(1, false, "b".getBytes(StandardCharsets.UTF_8)));
            engine.tell(new SocketFrame.Close(1, 4001, "tschüss"));

            assertEquals("text kurs ä", heard.next());
            assertEquals("binary [1, 2]", heard.next());
            assertEquals("text b", heard.next());
            assertEquals("close 4001 tschüss", heard.next());
            assertFalse(socket.isOpen());
            assertThrows(WebSocketException.class, () -> socket.send("zu spät"));
        }
    }

    @Test
    void aRefusalIsOpensToReport() throws Exception {
        engine.firstWord = open -> new SocketFrame.Close(open.id(), 1006, "");
        try (TinySocket sockets = builder().build()) {
            WebSocketException refused = assertThrows(WebSocketException.class,
                    () -> sockets.open("wss://push.example.org/", heard));
            assertTrue(refused.getMessage().contains("refused (1006)"), refused.getMessage());
        }
        assertTrue(heard.lines.isEmpty(), "a socket that never opened has nothing to tell its listener");
    }

    @Test
    void noHandshakeInTimeAbandonsTheSocket() throws Exception {
        engine.firstWord = open -> null;
        try (TinySocket sockets = builder().openTimeout(Duration.ofMillis(100)).build()) {
            WebSocketException late = assertThrows(WebSocketException.class,
                    () -> sockets.open("wss://push.example.org/", heard));
            assertTrue(late.getMessage().contains("no handshake"), late.getMessage());
            assertEquals(new SocketFrame.Close(1, 1000, ""), engine.nextSent(), "the engine is told to drop it");

            engine.tell(new SocketFrame.Opened(1, ""));
            assertEquals(new SocketFrame.Close(1, 1000, ""), engine.nextSent(), "a handshake nobody waits for is closed");
            engine.tell(new SocketFrame.Message(1, false, "x".getBytes(StandardCharsets.UTF_8)));
            engine.tell(new SocketFrame.Close(1, 1000, ""));
        }
        assertNull(heard.lines.poll(200, TimeUnit.MILLISECONDS), "an abandoned socket reaches nobody");
    }

    @Test
    void aSlowListenerHoldsUpItsOwnSocketOnly() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        SocketListener stuck = new SocketListener() {
            @Override
            public void onText(WebSocket socket, String text) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try (TinySocket sockets = builder().build()) {
            sockets.open("wss://slow.example.org/", stuck);
            sockets.open("wss://fast.example.org/", heard);
            long start = System.nanoTime();
            engine.tell(new SocketFrame.Message(1, false, "a".getBytes(StandardCharsets.UTF_8)));
            engine.tell(new SocketFrame.Message(1, false, "b".getBytes(StandardCharsets.UTF_8)));
            engine.tell(new SocketFrame.Message(2, false, "c".getBytes(StandardCharsets.UTF_8)));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1), "the engine's reader never waits");
            assertEquals("text c", heard.next(), "the other socket goes on");
            release.countDown();
        }
    }

    @Test
    void sendsAndClosesAsTheCallerSays() throws Exception {
        try (TinySocket sockets = builder().build()) {
            WebSocket socket = sockets.open("wss://push.example.org/", heard);
            socket.send("{\"subscribe\":\"DE0007164600\"}");
            SocketFrame.Message text = (SocketFrame.Message) engine.nextSent();
            assertFalse(text.binary());
            assertEquals("{\"subscribe\":\"DE0007164600\"}", new String(text.data(), StandardCharsets.UTF_8));
            socket.send(new byte[] {9, 8});
            assertArrayEquals(new byte[] {9, 8}, ((SocketFrame.Message) engine.nextSent()).data());

            assertThrows(IllegalArgumentException.class, () -> socket.close(1001, ""), "a browser may not send 1001");
            assertThrows(IllegalArgumentException.class, () -> socket.close(4000, "x".repeat(124)));
            socket.close(4000, "fertig");
            assertEquals(new SocketFrame.Close(1, 4000, "fertig"), engine.nextSent());
            socket.close();
            assertNull(engine.sent.poll(100, TimeUnit.MILLISECONDS), "closing is asked once");
            assertThrows(WebSocketException.class, () -> socket.send("x"), "a closing socket takes nothing more");
        }
    }

    @Test
    void onlyWebSocketUrls() throws Exception {
        try (TinySocket sockets = builder().build()) {
            assertThrows(IllegalArgumentException.class, () -> sockets.open("https://example.org/", heard));
            assertThrows(IllegalArgumentException.class, () -> sockets.open("wss:///nohost", heard));
        }
    }

    @Test
    void closingClosesEverySocketAndLetsGoOfTheEngine() throws Exception {
        TinySocket sockets = builder().build();
        sockets.open("wss://a.example.org/", heard);
        sockets.open("wss://b.example.org/", heard);
        sockets.close();
        assertTrue(engine.nextSent() instanceof SocketFrame.Close);
        assertTrue(engine.nextSent() instanceof SocketFrame.Close);
        assertTrue(engine.closed);
        assertThrows(WebSocketException.class, () -> sockets.open("wss://c.example.org/", heard));
    }
}
