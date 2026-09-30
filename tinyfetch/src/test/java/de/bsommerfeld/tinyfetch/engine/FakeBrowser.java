package de.bsommerfeld.tinyfetch.engine;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A stand-in for TinyBrowser, run as a real child process: it speaks the
 * protocol, but answers without a browser. {@code --mode} picks how:
 * <ul>
 *   <li>{@code echo} - 200, the body {@code <method> <url>}; a socket opens
 *       with the first subprotocol offered, echoes every message and answers
 *       a close with the same close - unless its URL says {@code refuse}</li>
 *   <li>{@code crash} - logs a warning and exits with code 3 on the first request</li>
 *   <li>{@code silent} - never answers</li>
 *   <li>{@code mute} - connects but never greets, and leaves when the socket closes</li>
 * </ul>
 */
public final class FakeBrowser {

    private FakeBrowser() {
    }

    public static void main(String[] args) throws Exception {
        String mode = "echo";
        Path socket = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--mode" -> mode = args[i + 1];
                case "--socket" -> socket = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(socket));
        DataInputStream in = Frames.reader(channel);
        DataOutputStream out = Frames.writer(channel);
        System.out.println("stray output that is not a frame");
        System.err.println("I fake browser up in mode " + mode);
        if (mode.equals("mute")) {
            // Never greets, but leaves with the socket, as the real one does.
            while (in.read() >= 0) {
                // nothing to say
            }
            return;
        }
        Frames.writeHello(out, "FakeChromium 1.0");
        try {
            while (true) {
                byte type = Frames.readType(in);
                if (type != Frames.REQUEST) {
                    SocketFrame reply = switch (Frames.readSocket(type, in)) {
                        case SocketFrame.Open open when open.url().contains("refuse") ->
                                new SocketFrame.Close(open.id(), 1006, "");
                        case SocketFrame.Open open ->
                                new SocketFrame.Opened(open.id(), open.protocols().isEmpty() ? "" : open.protocols().getFirst());
                        case SocketFrame other -> other;
                    };
                    if (mode.equals("crash")) {
                        System.err.println("W about to crash");
                        System.exit(3);
                    }
                    synchronized (out) {
                        Frames.writeSocket(out, reply);
                    }
                    continue;
                }
                EngineRequest request = Frames.readRequest(in);
                switch (mode) {
                    case "crash" -> {
                        System.err.println("W about to crash");
                        System.exit(3);
                    }
                    case "silent" -> {
                    }
                    default -> {
                        EngineAnswer answer = new EngineAnswer(request.id(), 200, request.url(),
                                List.of(Map.entry("x-request-id", Long.toString(request.id()))),
                                (request.method() + " " + request.url()).getBytes(StandardCharsets.UTF_8), null);
                        synchronized (out) {
                            Frames.writeAnswer(out, answer);
                        }
                    }
                }
            }
        } catch (IOException closed) {
            System.err.println("I fake browser leaves");
        }
    }
}
