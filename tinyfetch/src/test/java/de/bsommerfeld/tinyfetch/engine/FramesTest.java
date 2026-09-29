package de.bsommerfeld.tinyfetch.engine;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                body, "https://www.reddit.com/", 30_000);

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
    }

    @Test
    void absentBodyAndAnchorStayAbsent() throws IOException {
        EngineRequest sent = new EngineRequest(1, "https://example.org/", "GET", List.of(), null, null, 1);
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
    void aBrokenStreamFailsInsteadOfAllocating() {
        DataInputStream garbage = new DataInputStream(new ByteArrayInputStream(new byte[] {
                0, 0, 0, 0, 0, 0, 0, 1, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff}));
        assertThrows(IOException.class, () -> Frames.readRequest(garbage));
    }
}
