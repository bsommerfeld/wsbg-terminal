package de.bsommerfeld.tinyfetch.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The protocol between TinyFetch and its engine, both ends of it: typed frames
 * over one local socket.
 *
 * <pre>
 * engine → client  HELLO   once, when Chromium is up: its version
 * client → engine  REQUEST an {@link EngineRequest}
 * engine → client  ANSWER  an {@link EngineAnswer}, in any order
 * </pre>
 * Closing the socket is the only way to stop the engine; it goes when the
 * client does, crashes included.
 *
 * <h3>Why a socket, not stdout</h3>
 * Chromium's helper processes inherit the engine's standard streams and may
 * write to them. On a socket nothing but frames can arrive.
 */
public final class Frames {

    public static final byte HELLO = 'H';
    public static final byte REQUEST = 'R';
    public static final byte ANSWER = 'A';

    /** Upper bounds a sane frame stays within - a broken stream fails instead of allocating gigabytes. */
    private static final int MAX_TEXT = 16 * 1024 * 1024;
    private static final int MAX_BODY = 512 * 1024 * 1024;
    private static final int MAX_HEADERS = 1024;

    private Frames() {
    }

    // ---- streams --------------------------------------------------------------

    /**
     * Reads from {@code channel}. Not {@code Channels.newInputStream}: its
     * streams may share a lock between reading and writing, and the reader
     * blocks in a read all the time the writer needs to write.
     */
    public static DataInputStream reader(SocketChannel channel) {
        return new DataInputStream(new BufferedInputStream(new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                return length == 0 ? 0 : channel.read(ByteBuffer.wrap(buffer, offset, length));
            }
        }, 64 * 1024));
    }

    /** Writes to {@code channel}; the caller flushes after every frame. */
    public static DataOutputStream writer(SocketChannel channel) {
        return new DataOutputStream(new BufferedOutputStream(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                write(new byte[] {(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                ByteBuffer bytes = ByteBuffer.wrap(buffer, offset, length);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
            }
        }, 64 * 1024));
    }

    // ---- frames ---------------------------------------------------------------

    /** The next frame's type; {@link java.io.EOFException} when the other side closed. */
    public static byte readType(DataInputStream in) throws IOException {
        return in.readByte();
    }

    public static void writeHello(DataOutputStream out, String version) throws IOException {
        out.writeByte(HELLO);
        writeText(out, version);
        out.flush();
    }

    public static String readHello(DataInputStream in) throws IOException {
        return readText(in);
    }

    public static void writeRequest(DataOutputStream out, EngineRequest request) throws IOException {
        out.writeByte(REQUEST);
        out.writeLong(request.id());
        writeText(out, request.url());
        writeText(out, request.method());
        writeHeaders(out, request.headers());
        writeBytes(out, request.body());
        writeText(out, request.anchor());
        out.writeLong(request.timeoutMillis());
        out.flush();
    }

    public static EngineRequest readRequest(DataInputStream in) throws IOException {
        return new EngineRequest(in.readLong(), readText(in), readText(in), readHeaders(in), readBytes(in),
                readText(in), in.readLong());
    }

    public static void writeAnswer(DataOutputStream out, EngineAnswer answer) throws IOException {
        out.writeByte(ANSWER);
        out.writeLong(answer.id());
        out.writeInt(answer.status());
        writeText(out, answer.url());
        writeHeaders(out, answer.headers());
        writeBytes(out, answer.body());
        writeText(out, answer.failure());
        out.flush();
    }

    public static EngineAnswer readAnswer(DataInputStream in) throws IOException {
        long id = in.readLong();
        int status = in.readInt();
        String url = readText(in);
        List<Map.Entry<String, String>> headers = readHeaders(in);
        byte[] body = readBytes(in);
        return new EngineAnswer(id, status, url, headers, body == null ? new byte[0] : body, readText(in));
    }

    // ---- fields ---------------------------------------------------------------

    private static void writeHeaders(DataOutputStream out, List<Map.Entry<String, String>> headers)
            throws IOException {
        out.writeInt(headers.size());
        for (Map.Entry<String, String> header : headers) {
            writeText(out, header.getKey());
            writeText(out, header.getValue());
        }
    }

    private static List<Map.Entry<String, String>> readHeaders(DataInputStream in) throws IOException {
        int count = bounded(in.readInt(), MAX_HEADERS, "header count");
        List<Map.Entry<String, String>> headers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = readText(in);
            String value = readText(in);
            if (name == null || value == null) {
                throw new IOException("corrupt frame: header without name or value");
            }
            headers.add(Map.entry(name, value));
        }
        return headers;
    }

    /** UTF-8 with a length in front; {@code -1} stands for {@code null}. */
    private static void writeText(DataOutputStream out, String text) throws IOException {
        writeBytes(out, text == null ? null : text.getBytes(StandardCharsets.UTF_8));
    }

    private static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        byte[] bytes = new byte[bounded(length, MAX_TEXT, "text length")];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        if (bytes == null) {
            out.writeInt(-1);
            return;
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        byte[] bytes = new byte[bounded(length, MAX_BODY, "body length")];
        in.readFully(bytes);
        return bytes;
    }

    private static int bounded(int value, int maximum, String what) throws IOException {
        if (value < 0 || value > maximum) {
            throw new IOException("corrupt frame: " + what + " " + value);
        }
        return value;
    }
}
