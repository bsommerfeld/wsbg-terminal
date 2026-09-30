package de.bsommerfeld.tinyfetch.engine;

import de.bsommerfeld.tinyfetch.api.FetchException;

import java.util.List;
import java.util.function.Consumer;

/**
 * Where TinySocket's WebSockets live: {@link EngineProcess} in production, a
 * stand-in in tests.
 */
public interface SocketEngine extends AutoCloseable {

    /**
     * Has the engine open a socket. Returns once it is asked, not once the
     * handshake is done: {@code events} gets the rest - {@link SocketFrame.Opened},
     * {@link SocketFrame.Message}s, and one {@link SocketFrame.Close}, last.
     *
     * @param anchor        the page whose origin the socket speaks for, or
     *                      {@code null} for the root of {@code url}'s own host
     * @param events        runs on the thread that reads the engine - it hands
     *                      the work off and never blocks
     * @param timeoutMillis how long the caller waits for the handshake; an
     *                      engine still starting gets a grace on top
     * @return the socket's id
     * @throws FetchException the engine is not there
     */
    long openSocket(String url, List<String> protocols, String anchor, Consumer<SocketFrame> events,
            long timeoutMillis) throws FetchException, InterruptedException;

    /**
     * Sends a {@link SocketFrame.Message} on an open socket, or a
     * {@link SocketFrame.Close} for one; returns once it is written.
     *
     * @throws FetchException the socket or the engine is gone
     */
    void sendSocket(SocketFrame frame) throws FetchException;

    /** Lets go of the engine; sockets afterwards fail. */
    @Override
    void close();
}
