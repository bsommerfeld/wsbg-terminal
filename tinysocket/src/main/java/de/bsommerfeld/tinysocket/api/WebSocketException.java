package de.bsommerfeld.tinysocket.api;

import java.io.IOException;

/**
 * A socket did not open - refused, no handshake in time, the browser engine is
 * not there - or is no longer open to send on.
 */
public class WebSocketException extends IOException {

    public WebSocketException(String message) {
        super(message);
    }

    public WebSocketException(String message, Throwable cause) {
        super(message, cause);
    }
}
