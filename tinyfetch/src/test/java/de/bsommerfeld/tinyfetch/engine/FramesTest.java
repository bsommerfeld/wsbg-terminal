package de.bsommerfeld.tinyfetch.engine;

import de.bsommerfeld.tinyfetch.api.Step;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FramesTest {

    private static DataInputStream roundTrip(IoWriter writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writer.write(new DataOutputStream(bytes));
        return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(DataOutputStream out) throws IOException;
    }

    @Test
    void requestSurvivesTheWireByteForByte() throws IOException {
        byte[] body = {0, (byte) 0xff, 1, (byte) 0xe4};
        EngineRequest sent = new EngineRequest(42, "https://www.reddit.com/r/wallstreetbetsGER/new.json?ä=1", "POST",
                List.of(Map.entry("authorization", "bearer x"), Map.entry("accept", "application/json")),
                body, "https://www.reddit.com/", 30_000, Set.of(Step.LOAD_PAGE, Step.FETCH));

        DataInputStream in = roundTrip(out -> Frames.writeRequest(out, sent));
        assertEquals(Frames.REQUEST, Frames.readType(in));
        EngineRequest received = Frames.readRequest(in);

        assertEquals(sent.id(), received.id());
        assertEquals(sent.url(), received.url());
        assertEquals(sent.method(), received.method());
        assertEquals(sent.headers(), received.headers());
        assertArrayEquals(body, received.body());
        assertEquals(sent.anchor(), received.anchor());
        assertEquals(sent.timeoutMillis(), received.timeoutMillis());
        assertEquals(Set.of(Step.LOAD_PAGE, Step.FETCH), received.steps());
    }

    @Test
    void everyStepCombinationSurvivesTheWire() throws IOException {
        for (Set<Step> steps : List.of(Set.of(Step.FETCH), Set.of(Step.LOAD_PAGE, Step.FETCH), Step.ALL)) {
            EngineRequest sent = new EngineRequest(1, "https://example.org/", "GET", List.of(), null, null, 1, steps);
            DataInputStream in = roundTrip(out -> Frames.writeRequest(out, sent));
            Frames.readType(in);
            assertEquals(steps, Frames.readRequest(in).steps());
        }
    }

    @Test
    void absentBodyAndAnchorStayAbsent() throws IOException {
        EngineRequest sent = new EngineRequest(1, "https://example.org/", "GET", List.of(), null, null, 1, Step.ALL);
        DataInputStream in = roundTrip(out -> Frames.writeRequest(out, sent));
        Frames.readType(in);
        EngineRequest received = Frames.readRequest(in);
        assertNull(received.body());
        assertNull(received.anchor());
    }

    @Test
    void answerAndFailureSurviveTheWire() throws IOException {
        EngineAnswer answer = new EngineAnswer(7, 404, "https://example.org/after",
                List.of(Map.entry("content-type", "text/html")), "nicht da".getBytes(StandardCharsets.UTF_8), null);
        EngineAnswer failure = EngineAnswer.failed(8, "page fetch failed: TypeError");

        DataInputStream in = roundTrip(out -> {
            Frames.writeHello(out, "Chromium 132");
            Frames.writeAnswer(out, answer);
            Frames.writeAnswer(out, failure);
        });
        assertEquals(Frames.HELLO, Frames.readType(in));
        assertEquals("Chromium 132", Frames.readHello(in));

        assertEquals(Frames.ANSWER, Frames.readType(in));
        EngineAnswer first = Frames.readAnswer(in);
        assertEquals(404, first.status());
        assertEquals("https://example.org/after", first.url());
        assertEquals("nicht da", new String(first.body(), StandardCharsets.UTF_8));
        assertNull(first.failure());

        Frames.readType(in);
        EngineAnswer second = Frames.readAnswer(in);
        assertEquals(0, second.status());
        assertEquals("page fetch failed: TypeError", second.failure());
    }

    @Test
    void socketFramesSurviveTheWireInOrder() throws IOException {
        byte[] binary = {0, (byte) 0xff, 1};
        DataInputStream in = roundTrip(out -> {
            Frames.writeSocket(out, new SocketFrame.Open(3, "wss://push.example.org/q?ä=1", List.of("v2", "v1"), null));
            Frames.writeSocket(out, new SocketFrame.Opened(3, "v2"));
            Frames.writeSocket(out, new SocketFrame.Message(3, false, "kurs ä".getBytes(StandardCharsets.UTF_8)));
            Frames.writeSocket(out, new SocketFrame.Message(3, true, binary));
            Frames.writeSocket(out, new SocketFrame.Close(3, 4001, "tschüss"));
        });

        SocketFrame.Open open = (SocketFrame.Open) Frames.readSocket(Frames.readType(in), in);
        assertEquals("wss://push.example.org/q?ä=1", open.url());
        assertEquals(List.of("v2", "v1"), open.protocols());
        assertNull(open.anchor());
        assertEquals(new SocketFrame.Opened(3, "v2"), Frames.readSocket(Frames.readType(in), in));
        SocketFrame.Message text = (SocketFrame.Message) Frames.readSocket(Frames.readType(in), in);
        assertEquals("kurs ä", new String(text.data(), StandardCharsets.UTF_8));
        SocketFrame.Message bytes = (SocketFrame.Message) Frames.readSocket(Frames.readType(in), in);
        assertTrue(bytes.binary());
        assertArrayEquals(binary, bytes.data());
        assertEquals(new SocketFrame.Close(3, 4001, "tschüss"), Frames.readSocket(Frames.readType(in), in));
    }

    @Test
    void anUnknownTypeIsNoSocketFrame() {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(new byte[16]));
        assertThrows(IOException.class, () -> Frames.readSocket(Frames.ANSWER, in));
    }

    @Test
    void aBrokenStreamFailsInsteadOfAllocating() {
        DataInputStream garbage = new DataInputStream(new ByteArrayInputStream(new byte[] {
                0, 0, 0, 0, 0, 0, 0, 1, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff}));
        assertThrows(IOException.class, () -> Frames.readRequest(garbage));
    }
}
