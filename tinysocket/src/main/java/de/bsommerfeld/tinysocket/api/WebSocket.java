package de.bsommerfeld.tinysocket.api;

import de.bsommerfeld.tinyfetch.api.FetchException;
import de.bsommerfeld.tinyfetch.engine.SocketEngine;
import de.bsommerfeld.tinyfetch.engine.SocketFrame;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One WebSocket, as {@link TinySocket#open} handed it out: open from then on
 * until its {@link SocketListener#onClose}. Safe from any number of threads.
 */
public final class WebSocket implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger("de.bsommerfeld.tinysocket");

    private final SocketEngine engine;
    private final URI uri;
    private final SocketListener listener;
    private final Consumer<WebSocket> gone;
    /** Completes with the socket's first word from the engine: Opened, or the Close of a refusal. */
    private final CompletableFuture<SocketFrame> handshake = new CompletableFuture<>();
    private final Queue<Runnable> calls = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean calling = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile long id;
    private volatile String protocol = "";
    private volatile boolean open;
    /** The caller stopped waiting for the handshake: nothing of this socket reaches the listener. */
    private volatile boolean abandoned;

    WebSocket(SocketEngine engine, URI uri, SocketListener listener, Consumer<WebSocket> gone) {
        this.engine = engine;
        this.uri = uri;
        this.listener = listener;
        this.gone = gone;
    }

    public URI uri() {
        return uri;
    }

    /** The subprotocol the server chose, empty for none. */
    public String protocol() {
        return protocol;
    }

    /** Open until its close arrived - the server's, the caller's, or a broken connection's. */
    public boolean isOpen() {
        return open;
    }

    /**
     * Sends a text message. Returns once the engine has it, not the server.
     *
     * @throws WebSocketException the socket is closed or closing
     */
    public void send(String text) throws WebSocketException {
        transmit(new SocketFrame.Message(id, false, text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Sends a binary message. {@code data} is on its way when this returns -
     * the caller may reuse the array.
     *
     * @throws WebSocketException the socket is closed or closing
     */
    public void send(byte[] data) throws WebSocketException {
        transmit(new SocketFrame.Message(id, true, data));
    }

    /** {@link #close(int, String)} with {@code 1000}, the normal close. */
    @Override
    public void close() {
        close(1000, "");
    }

    /**
     * Asks the server to close the socket; {@link SocketListener#onClose}
     * follows once it has. Does nothing on a socket that is closed or
     * closing already.
     *
     * @param code   {@code 1000}, or {@code 3000}-{@code 4999} - the codes a browser may send
     * @param reason at most 123 bytes as UTF-8
     */
    public void close(int code, String reason) {
        if (code != 1000 && (code < 3000 || code > 4999)) {
            throw new IllegalArgumentException("a browser closes with 1000 or 3000-4999, not " + code);
        }
        if (reason.getBytes(StandardCharsets.UTF_8).length > 123) {
            throw new IllegalArgumentException("close reason past 123 bytes: " + reason);
        }
        if (!open || !closing.compareAndSet(false, true)) {
            return;
        }
        try {
            engine.sendSocket(new SocketFrame.Close(id, code, reason));
        } catch (FetchException engineGone) {
            // the socket went with the engine; its Close is on the way
        }
    }

    @Override
    public String toString() {
        return "WebSocket " + uri;
    }

    // ---- TinySocket's side -----------------------------------------------------

    void bind(long id) {
        this.id = id;
    }

    /**
     * Waits for the handshake; a caller that stops waiting - out of time, or
     * interrupted - abandons the socket.
     *
     * @throws WebSocketException refused, or no handshake within {@code timeout}
     */
    void awaitOpen(Duration timeout) throws WebSocketException, InterruptedException {
        SocketFrame first;
        try {
            first = handshake.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            abandon();
            throw new WebSocketException(uri + ": no handshake within " + timeout);
        } catch (InterruptedException e) {
            abandon();
            throw e;
        } catch (ExecutionException e) {
            throw new WebSocketException(uri + ": " + e.getCause(), e.getCause());
        }
        if (first instanceof SocketFrame.Close refusal) {
            throw new WebSocketException(uri + ": refused (" + refusal.code()
                    + (refusal.reason().isEmpty() ? "" : ", " + refusal.reason()) + ")");
        }
    }

    /** One frame from the engine. Runs on the thread that reads the engine: hands every listener call off. */
    void receive(SocketFrame frame) {
        switch (frame) {
            case SocketFrame.Opened opened -> {
                protocol = opened.protocol();
                if (abandoned) {
                    cancel();
                    return;
                }
                open = true;
                handshake.complete(opened);
            }
            case SocketFrame.Message message -> {
                if (open && !abandoned) {
                    call(() -> {
                        if (message.binary()) {
                            listener.onBinary(this, message.data());
                        } else {
                            listener.onText(this, new String(message.data(), StandardCharsets.UTF_8));
                        }
                    });
                }
            }
            case SocketFrame.Close close -> {
                open = false;
                gone.accept(this);
                // A refusal is open()'s to report, an abandoned socket nobody's.
                if (!handshake.complete(close) && !abandoned) {
                    call(() -> listener.onClose(this, close.code(), close.reason()));
                }
            }
            case SocketFrame.Open ignored -> {
                // only ever sent to the engine
            }
        }
    }

    private void abandon() {
        abandoned = true;
        gone.accept(this);
        cancel();
    }

    /** Closes a socket nobody waits for any more - the Close still comes, and goes nowhere. */
    private void cancel() {
        try {
            engine.sendSocket(new SocketFrame.Close(id, 1000, ""));
        } catch (FetchException engineGone) {
            // then the socket is gone as well
        }
    }

    private void transmit(SocketFrame frame) throws WebSocketException {
        if (!open || closing.get()) {
            throw new WebSocketException(uri + " is closed");
        }
        try {
            engine.sendSocket(frame);
        } catch (FetchException e) {
            throw new WebSocketException(uri + ": " + e.getMessage(), e);
        }
    }

    /**
     * Listener calls go one after another, in arrival order, on a virtual
     * thread that exists only while there are calls to make - an idle socket
     * holds no thread.
     */
    private void call(Runnable call) {
        calls.add(call);
        if (calling.compareAndSet(false, true)) {
            Thread.ofVirtual().name("tinysocket-" + id).start(this::drain);
        }
    }

    private void drain() {
        do {
            for (Runnable call = calls.poll(); call != null; call = calls.poll()) {
                try {
                    call.run();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, uri + ": listener failed", e);
                }
            }
            calling.set(false);
        } while (!calls.isEmpty() && calling.compareAndSet(false, true));
    }
}
