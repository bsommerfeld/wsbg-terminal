package de.bsommerfeld.tinysocket.api;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The plainest WebSocket server (RFC 6455) there is, for the live test: a
 * virtual thread per connection, no extensions. A socket echoes every message
 * and answers a close with the same close; a few paths do more:
 * <ul>
 *   <li>{@code /bye} - closes at once, with {@code 4001 tschüss}</li>
 *   <li>{@code /refuse} - turns the handshake down with a 403</li>
 * </ul>
 * A request without an upgrade gets a small HTML page - the anchor's -
 * and {@code /cookie} a cookie with it. Every handshake's headers are kept,
 * by path.
 */
final class EchoServer implements AutoCloseable {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    final Map<String, Map<String, String>> handshakes = new ConcurrentHashMap<>();
    final AtomicInteger sockets = new AtomicInteger();
    private final ServerSocket server;

    EchoServer() throws IOException {
        server = new ServerSocket(0, 1024, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("echo-server").start(this::accept);
    }

    String base() {
        return "127.0.0.1:" + server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket connection = server.accept();
                Thread.ofVirtual().name("echo-connection").start(() -> serve(connection));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket connection) {
        try (connection) {
            InputStream in = new BufferedInputStream(connection.getInputStream());
            OutputStream out = connection.getOutputStream();
            String path = line(in).split(" ")[1];
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String header = line(in); !header.isEmpty(); header = line(in)) {
                int colon = header.indexOf(':');
                headers.put(header.substring(0, colon).trim(), header.substring(colon + 1).trim());
            }
            String key = headers.get("Sec-WebSocket-Key");
            if (key == null) {
                page(out, path);
                return;
            }
            handshakes.put(path, headers);
            if (path.equals("/refuse")) {
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                return;
            }
            String protocol = headers.get("Sec-WebSocket-Protocol");
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept(key) + "\r\n"
                    + (protocol == null ? "" : "Sec-WebSocket-Protocol: " + protocol.split(",")[0].trim() + "\r\n")
                    + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            sockets.incrementAndGet();
            if (path.equals("/bye")) {
                frame(out, 0x8, closePayload(4001, "tschüss"));
                return;
            }
            echo(in, out);
        } catch (IOException gone) {
            // the client went
        }
    }

    private static void echo(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int messageOpcode = 0;
        while (true) {
            int first = in.read();
            if (first < 0) {
                return;
            }
            int second = in.read();
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0f;
            long length = second & 0x7f;
            if (length == 126) {
                length = (in.read() << 8) | in.read();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | in.read();
                }
            }
            byte[] mask = (second & 0x80) != 0 ? in.readNBytes(4) : null;
            byte[] payload = in.readNBytes((int) length);
            if (payload.length != length) {
                throw new EOFException();
            }
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }
            switch (opcode) {
                case 0x8 -> {
                    frame(out, 0x8, payload);
                    return;
                }
                case 0x9 -> frame(out, 0xA, payload);
                case 0xA -> {
                }
                default -> {
                    if (opcode != 0) {
                        messageOpcode = opcode;
                    }
                    message.write(payload);
                    if (fin) {
                        frame(out, messageOpcode, message.toByteArray());
                        message.reset();
                    }
                }
            }
        }
    }

    private static void page(OutputStream out, String path) throws IOException {
        byte[] body = "<html><title>anchor</title></html>".getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + body.length + "\r\n"
                + (path.equals("/cookie") ? "Set-Cookie: session=browser; Path=/; Max-Age=600\r\n" : "")
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static void frame(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(0x80 | opcode);
        if (payload.length < 126) {
            out.write(payload.length);
        } else if (payload.length < 65_536) {
            out.write(126);
            out.write(payload.length >> 8);
            out.write(payload.length & 0xff);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) (((long) payload.length >> (8 * i)) & 0xff));
            }
        }
        out.write(payload);
        out.flush();
    }

    private static byte[] closePayload(int code, String reason) {
        byte[] text = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + text.length];
        payload[0] = (byte) (code >> 8);
        payload[1] = (byte) code;
        System.arraycopy(text, 0, payload, 2, text.length);
        return payload;
    }

    private static String accept(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c = in.read(); c != '\n'; c = in.read()) {
            if (c < 0) {
                throw new EOFException();
            }
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }
}
