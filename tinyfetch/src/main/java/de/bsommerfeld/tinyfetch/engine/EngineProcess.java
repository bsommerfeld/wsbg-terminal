package de.bsommerfeld.tinyfetch.engine;

import de.bsommerfeld.tinyfetch.api.FetchException;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The engine as a child process - TinyBrowser, embedded Chromium in its own
 * JVM - spoken to over a local socket ({@link Frames}).
 *
 * <h3>Lifecycle</h3>
 * {@link #start()} launches it in the background; the first start may install
 * Chromium (a download of about 100 MB), and requests meanwhile wait up to
 * their own patience. An engine that stops - a crash, a broken frame - fails
 * every request in flight and is started again by the next request, at most
 * once per {@link #RESTART_PAUSE}. {@link #close()} closes the socket, which
 * is the engine's signal to save its cookies and go, and waits until it has
 * gone - killing it after {@link #STOP_WAIT}.
 *
 * <h3>Output</h3>
 * The engine's standard output and error are one stream of log lines,
 * forwarded to {@code System.Logger} {@code de.bsommerfeld.tinyfetch.engine};
 * a line starting with {@code I }, {@code W } or {@code D } carries its level.
 * The last few lines go into the message of every failure they may explain.
 */
public final class EngineProcess implements Engine {

    private static final System.Logger LOG = System.getLogger("de.bsommerfeld.tinyfetch.engine");

    /**
     * Beyond twice a request's own timeout - the engine re-issues a fetch
     * once when no reply came - how long it may take: a new tab first loads
     * its page and verifies the session.
     */
    private static final Duration ENGINE_GRACE = Duration.ofSeconds(60);
    /** A stopped engine is started again at most this often. */
    static final Duration RESTART_PAUSE = Duration.ofSeconds(30);
    /** How long a stopping engine gets to save its cookies before it is killed. */
    private static final Duration STOP_WAIT = Duration.ofSeconds(5);
    private static final int OUTPUT_TAIL = 8;
    /** How long a failing engine's last words may take to arrive - they explain the failure. */
    private static final Duration LAST_WORDS_WAIT = Duration.ofMillis(500);

    private final List<String> command;
    private final Duration grace;
    private final Object lifecycle = new Object();
    private Connection connection;
    private long startedAt;
    private boolean closed;

    /**
     * @param command the engine's command line; {@code --socket <path>} is appended
     */
    public EngineProcess(List<String> command) {
        this(command, ENGINE_GRACE);
    }

    /** With another grace than {@link #ENGINE_GRACE} - for tests that must not wait a minute. */
    EngineProcess(List<String> command, Duration grace) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("empty engine command");
        }
        this.command = List.copyOf(command);
        this.grace = grace;
    }

    /** Launches the engine now, rather than with the first request. Returns at once. */
    public void start() throws FetchException {
        connection();
    }

    @Override
    public EngineAnswer exchange(EngineRequest request) throws FetchException, InterruptedException {
        long deadline = System.currentTimeMillis() + 2 * request.timeoutMillis() + grace.toMillis();
        return connection().exchange(request, deadline);
    }

    @Override
    public void close() {
        Connection last;
        synchronized (lifecycle) {
            closed = true;
            last = connection;
            connection = null;
        }
        if (last != null) {
            last.stop("closed");
            last.awaitExit();
        }
    }

    private Connection connection() throws FetchException {
        synchronized (lifecycle) {
            if (closed) {
                throw new FetchException("browser engine is closed");
            }
            if (connection != null && connection.stopReason == null) {
                return connection;
            }
            long now = System.currentTimeMillis();
            if (connection != null && now - startedAt < RESTART_PAUSE.toMillis()) {
                throw new FetchException("browser engine stopped (" + connection.stopReason + "); started again from "
                        + Instant.ofEpochMilli(startedAt + RESTART_PAUSE.toMillis()));
            }
            startedAt = now;
            connection = new Connection();
            return connection;
        }
    }

    /**
     * Unix socket paths are limited to about 100 bytes, 104 on macOS - and
     * macOS's per-user temporary directory takes half of that by itself.
     */
    private static Path socketDirectory() throws IOException {
        Path base = Path.of(System.getProperty("java.io.tmpdir"));
        if (base.toString().length() > 60 && Files.isDirectory(Path.of("/tmp"))) {
            base = Path.of("/tmp");
        }
        return Files.createTempDirectory(base, "tinyfetch-");
    }

    private static String describe(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }

    /** One engine process and its socket, from launch to stop. */
    private final class Connection {

        private final Path directory;
        private final ServerSocketChannel server;
        private final Process process;
        private final CompletableFuture<String> hello = new CompletableFuture<>();
        private final Map<Long, CompletableFuture<EngineAnswer>> pending = new ConcurrentHashMap<>();
        private final Deque<String> output = new ArrayDeque<>();
        private final Object writeLock = new Object();
        private final Thread outputReader;
        private volatile SocketChannel channel;
        private volatile DataOutputStream out;
        private volatile String stopReason;

        Connection() throws FetchException {
            Path socketDirectory = null;
            ServerSocketChannel socketServer = null;
            try {
                socketDirectory = socketDirectory();
                Path socket = socketDirectory.resolve("engine.sock");
                socketServer = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                socketServer.bind(UnixDomainSocketAddress.of(socket));
                List<String> line = new ArrayList<>(command);
                line.addAll(List.of("--socket", socket.toString()));
                this.process = new ProcessBuilder(line).redirectErrorStream(true).start();
            } catch (IOException e) {
                closeQuietly(socketServer);
                deleteQuietly(socketDirectory);
                throw new FetchException("cannot start the browser engine: " + describe(e), e);
            }
            this.directory = socketDirectory;
            this.server = socketServer;
            this.outputReader = Thread.ofVirtual().name("tinyfetch-engine-output").start(this::readOutput);
            Thread.ofVirtual().name("tinyfetch-engine-answers").start(this::readAnswers);
            process.onExit().thenRun(() -> stop("exited with code " + process.exitValue()));
        }

        EngineAnswer exchange(EngineRequest request, long deadline) throws FetchException, InterruptedException {
            awaitHello(deadline);
            CompletableFuture<EngineAnswer> answer = new CompletableFuture<>();
            pending.put(request.id(), answer);
            if (stopReason != null) {
                pending.remove(request.id());
                throw stopped();
            }
            try {
                synchronized (writeLock) {
                    Frames.writeRequest(out, request);
                }
            } catch (IOException e) {
                pending.remove(request.id());
                stop("cannot write to it: " + describe(e));
                throw stopped();
            }
            try {
                return answer.get(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new FetchException("browser engine gave no answer in time for " + request.url());
            } catch (ExecutionException e) {
                throw e.getCause() instanceof FetchException failure ? failure
                        : new FetchException(describe(e.getCause()), e.getCause());
            } finally {
                pending.remove(request.id());
            }
        }

        private void awaitHello(long deadline) throws FetchException, InterruptedException {
            try {
                hello.get(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new FetchException("browser engine is still starting" + tail());
            } catch (ExecutionException e) {
                throw stopped();
            }
        }

        private void readAnswers() {
            try {
                SocketChannel accepted = server.accept();
                closeQuietly(server);
                channel = accepted;
                out = Frames.writer(accepted);
                DataInputStream in = Frames.reader(accepted);
                if (Frames.readType(in) != Frames.HELLO) {
                    throw new IOException("the engine did not introduce itself");
                }
                String version = Frames.readHello(in);
                LOG.log(System.Logger.Level.INFO, "browser engine up: {0}", version);
                hello.complete(version);

                /*
                 * Parks in the read until the next frame, and ends only by an
                 * IOException: EOFException when the engine leaves,
                 * AsynchronousCloseException when stop() closes the channel,
                 * or a corrupt frame. A flag could not end it - the thread
                 * sits in the read and would see the flag only after the next
                 * frame; closing the channel is the only way to wake it.
                */
                while (true) {
                    byte type = Frames.readType(in);
                    if (type != Frames.ANSWER) {
                        throw new IOException("corrupt frame of type " + type);
                    }
                    EngineAnswer answer = Frames.readAnswer(in);
                    CompletableFuture<EngineAnswer> waiting = pending.remove(answer.id());
                    if (waiting != null) {
                        waiting.complete(answer);
                    }
                }

            } catch (IOException e) {
                stop("connection lost: " + describe(e));
            }
        }

        private void readOutput() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (line.isBlank()) {
                        continue;
                    }
                    char marker = line.length() > 1 && line.charAt(1) == ' ' ? line.charAt(0) : '?';
                    System.Logger.Level level = switch (marker) {
                        case 'I' -> System.Logger.Level.INFO;
                        case 'W' -> System.Logger.Level.WARNING;
                        default -> System.Logger.Level.DEBUG;
                    };
                    String text = marker == 'I' || marker == 'W' || marker == 'D' ? line.substring(2) : line;
                    LOG.log(level, "engine: {0}", text);
                    synchronized (output) {
                        output.addLast(text);
                        if (output.size() > OUTPUT_TAIL) {
                            output.removeFirst();
                        }
                    }
                }
            } catch (IOException ignored) {
                // the process is gone; its exit says the rest
            }
        }

        /**
         * Ends this connection for good: everything waiting fails, the engine
         * gets {@link #STOP_WAIT} to save its cookies and is killed after.
         */
        void stop(String reason) {
            if (!reason.equals("closed")) {
                awaitLastWords();
            }
            synchronized (this) {
                if (stopReason != null) {
                    return;
                }
                stopReason = reason + tail();
            }
            FetchException failure = stopped();
            hello.completeExceptionally(failure);
            pending.values().forEach(waiting -> waiting.completeExceptionally(failure));
            pending.clear();
            closeQuietly(server);
            closeQuietly(channel);
            if (!reason.equals("closed")) {
                LOG.log(System.Logger.Level.WARNING, "browser engine stopped: {0}", stopReason);
            }
            if (!reason.equals("closed")) {
                Thread.ofVirtual().name("tinyfetch-engine-stop").start(this::awaitExit);
            }
        }

        /**
         * Waits for the engine to leave, and kills it after {@link #STOP_WAIT}.
         * {@link EngineProcess#close()} waits here itself: a background thread
         * would die with an application on its way out, and a hung engine
         * would outlive it.
         */
        void awaitExit() {
            try {
                if (!process.waitFor(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            deleteQuietly(directory);
        }

        /** An engine on its way out usually says why on its way out - wait for the output to end, briefly. */
        private void awaitLastWords() {
            try {
                outputReader.join(LAST_WORDS_WAIT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private FetchException stopped() {
            return new FetchException("browser engine stopped: " + stopReason);
        }

        private String tail() {
            synchronized (output) {
                return output.isEmpty() ? "" : " | " + String.join(" | ", output);
            }
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // closing is all that was left to do
        }
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException ignored) {
                    // a temporary directory; the system cleans up after us
                }
            });
        } catch (IOException ignored) {
            // as above
        }
    }
}
