package de.bsommerfeld.tinyfetch.engine;

import java.util.List;

/**
 * One WebSocket's traffic between a client and the engine, which opens the
 * socket in a hidden tab of its own ({@link Frames}).
 *
 * <pre>
 * client → engine  Open     open a socket in the tab parked on the anchor
 * engine → client  Opened   the handshake went through
 * both ways        Message  one whole message, text or binary
 * both ways        Close    the client's: close it; the engine's: it closed -
 *                           always a socket's last frame, failed handshakes included
 * </pre>
 */
public sealed interface SocketFrame {

    /** The socket; unique per engine connection. */
    long id();

    /**
     * @param url       {@code ws://} or {@code wss://}
     * @param protocols the subprotocols to offer, empty for none
     * @param anchor    the page whose origin the socket speaks for, or
     *                  {@code null} for the root of {@code url}'s own host
     */
    record Open(long id, String url, List<String> protocols, String anchor) implements SocketFrame {

        public Open {
            protocols = List.copyOf(protocols);
        }
    }

    /** @param protocol the subprotocol the server chose, empty for none */
    record Opened(long id, String protocol) implements SocketFrame {
    }

    /** @param data a binary message as it is, a text message as UTF-8 */
    record Message(long id, boolean binary, byte[] data) implements SocketFrame {
    }

    /**
     * @param code   the close code; {@code 1006} when the connection failed
     *               or broke, which comes without a reason from the server
     * @param reason the reason, empty for none
     */
    record Close(long id, int code, String reason) implements SocketFrame {
    }
}
