package de.bsommerfeld.tinybrowser;

import de.bsommerfeld.tinyfetch.engine.EngineAnswer;
import de.bsommerfeld.tinyfetch.engine.EngineRequest;
import de.bsommerfeld.tinyfetch.engine.Frames;
import de.bsommerfeld.tinyfetch.engine.SocketFrame;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

/**
 * TinyBrowser, the engine TinyFetch and TinySocket start: embedded Chromium in
 * its own JVM, with a hidden tab per site that carries out TinyFetch's
 * requests as that site's own {@code fetch()}, and one per site that holds
 * TinySocket's WebSockets ({@link Sockets}).
 *
 * <h3>Arguments</h3>
 * <pre>
 * --socket &lt;path&gt;        TinyFetch's socket to connect to (Frames)
 * --chromium &lt;dir&gt;       the Chromium build - installed there if missing
 * --profile &lt;dir&gt;        the browser profile, kept across runs
 * --seed-profile &lt;dir&gt;   a profile to start from while --profile is empty (optional)
 * </pre>
 *
 * <h3>Lifetime</h3>
 * Connects first, then starts Chromium - which may install it - and greets
 * with its version. Every request runs on its own virtual thread; answers go
 * back as they come. Socket frames are handled on the reading thread, in the
 * order they came; what a socket says goes back as it comes. When the socket
 * closes, its client is gone or done: the cookies are written and the process
 * leaves without CEF's shutdown, which has been known to hang.
 */
public final class BrowserMain {

    /** How long the browser may take to write its cookies on the way out. */
    private static final long LEAVE_MILLIS = 2_000;

    private BrowserMain() {
    }

    public static void main(String[] args) {
        // JCEF prints to stdout; everything goes to the log.
        System.setOut(System.err);
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) ->
                Log.warn("uncaught in " + thread.getName() + ": " + failure));
        /*
         * Before anything starts AWT: no Dock icon, no menu bar. Only macOS
         * needs this - it gives any process that starts AWT a Dock icon.
         * Windows and Linux list a process in the taskbar only for a visible
         * window, and the engine opens none; the console window Windows would
         * give java.exe the JDK suppresses (CREATE_NO_WINDOW, stdio piped).
        */
        System.setProperty("apple.awt.UIElement", "true");

        int exit = 0;
        try {
            run(Arguments.parse(args));
        } catch (Throwable failure) {
            Log.warn("engine failed: " + failure);
            exit = 1;
        }
        Runtime.getRuntime().halt(exit);
    }

    private static void run(Arguments arguments) throws Exception {
        if (arguments.seedProfile() != null && ProfileSeed.seed(arguments.seedProfile(), arguments.profile())) {
            Log.info("profile started from " + arguments.seedProfile());
        }
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(arguments.socket()));
        DataInputStream in = Frames.reader(channel);
        DataOutputStream out = Frames.writer(channel);

        Browser browser = Chromium.start(arguments.chromium(), arguments.profile());
        try {
            Tabs tabs = new Tabs(browser);
            Sockets sockets = new Sockets(browser);
            Frames.writeHello(out, browser.version());
            while (true) {
                byte type = Frames.readType(in);
                if (type == Frames.REQUEST) {
                    EngineRequest request = Frames.readRequest(in);
                    Thread.ofVirtual().name("tinybrowser-request-" + request.id())
                            .start(() -> answer(tabs, request, out));
                    continue;
                }
                SocketFrame socketFrame = Frames.readSocket(type, in);
                try {
                    switch (socketFrame) {
                        case SocketFrame.Open open -> sockets.open(open, frame -> tell(out, frame));
                        case SocketFrame frame -> sockets.send(frame);
                    }
                } catch (RuntimeException failure) {
                    // One socket's trouble must not end the loop every request and socket depends on.
                    Log.warn("socket " + socketFrame.id() + " failed: " + failure);
                }
            }
        } catch (IOException closed) {
            // TinyFetch closed the socket - or went away
        } finally {
            browser.leave(LEAVE_MILLIS);
        }
    }

    private static void answer(Tabs tabs, EngineRequest request, DataOutputStream out) {
        EngineAnswer answer;
        try {
            answer = tabs.fetch(request);
        } catch (Exception failure) {
            String reason = failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
            answer = EngineAnswer.failed(request.id(), reason);
        }
        try {
            synchronized (out) {
                Frames.writeAnswer(out, answer);
            }
        } catch (IOException gone) {
            // TinyFetch is gone; the main loop ends with it
        }
    }

    private static void tell(DataOutputStream out, SocketFrame frame) {
        try {
            synchronized (out) {
                Frames.writeSocket(out, frame);
            }
        } catch (IOException gone) {
            // the client is gone; the main loop ends with it
        }
    }

    /** Parsed command line. */
    record Arguments(Path socket, Path chromium, Path profile, Path seedProfile) {

        static Arguments parse(String[] args) {
            Path socket = null;
            Path chromium = null;
            Path profile = null;
            Path seedProfile = null;
            for (int i = 0; i + 1 < args.length; i += 2) {
                Path value = Path.of(args[i + 1]);
                switch (args[i]) {
                    case "--socket" -> socket = value;
                    case "--chromium" -> chromium = value;
                    case "--profile" -> profile = value;
                    case "--seed-profile" -> seedProfile = value;
                    default -> throw new IllegalArgumentException("unknown argument " + args[i]);
                }
            }
            if (socket == null || chromium == null || profile == null) {
                throw new IllegalArgumentException("--socket, --chromium and --profile are required");
            }
            return new Arguments(socket, chromium, profile, seedProfile);
        }
    }
}
