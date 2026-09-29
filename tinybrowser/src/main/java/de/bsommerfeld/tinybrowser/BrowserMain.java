package de.bsommerfeld.tinybrowser;

import de.bsommerfeld.tinyfetch.engine.EngineAnswer;
import de.bsommerfeld.tinyfetch.engine.EngineRequest;
import de.bsommerfeld.tinyfetch.engine.Frames;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

/**
 * TinyBrowser, the engine TinyFetch starts: embedded Chromium in its own JVM,
 * with a hidden tab per site that carries out TinyFetch's requests as that
 * site's own {@code fetch()}.
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
 * back as they come. When the socket closes, TinyFetch is gone or done: the
 * cookies are written and the process leaves without CEF's shutdown, which
 * has been known to hang.
 */
public final class BrowserMain {

    /** How long the cookie store may take to reach the disk on the way out. */
    private static final long COOKIE_FLUSH_MILLIS = 2_000;

    private BrowserMain() {
    }

    public static void main(String[] args) {
        // JCEF prints to stdout; everything goes to the log.
        System.setOut(System.err);
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) ->
                Log.warn("uncaught in " + thread.getName() + ": " + failure));
        // Before anything starts AWT: no Dock icon, no menu bar.
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

        Chromium chromium = Chromium.start(arguments.chromium(), arguments.profile());
        Tabs tabs = new Tabs(chromium);
        Frames.writeHello(out, chromium.version());

        try {
            while (true) {
                byte type = Frames.readType(in);
                if (type != Frames.REQUEST) {
                    throw new IOException("corrupt frame of type " + type);
                }
                EngineRequest request = Frames.readRequest(in);
                Thread.ofVirtual().name("tinybrowser-request-" + request.id())
                        .start(() -> answer(tabs, request, out));
            }
        } catch (IOException closed) {
            // TinyFetch closed the socket - or went away
        }
        chromium.flushCookies(COOKIE_FLUSH_MILLIS);
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
