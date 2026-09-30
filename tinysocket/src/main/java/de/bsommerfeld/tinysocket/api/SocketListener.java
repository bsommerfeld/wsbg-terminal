package de.bsommerfeld.tinysocket.api;

/**
 * What a {@link WebSocket} hears. Called on a thread of the socket's own, one
 * call after another, in the order the messages came; a call that throws is
 * logged, and the next one follows.
 */
public interface SocketListener {

    /** A text message. */
    default void onText(WebSocket socket, String text) {
    }

    /** A binary message. */
    default void onBinary(WebSocket socket, byte[] data) {
    }

    /**
     * The socket closed - the server or the caller closed it, with the code
     * and reason sent, or the connection broke ({@code 1006}: the network, the
     * server went away, the browser engine stopped). The last call; a socket
     * is never reopened - open a new one.
     */
    default void onClose(WebSocket socket, int code, String reason) {
    }
}
