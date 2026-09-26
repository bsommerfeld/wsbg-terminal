package de.bsommerfeld.tinyfetch.api;

import java.io.IOException;

/**
 * A request produced no HTTP answer: the host could not be reached, the
 * transfer timed out or was cancelled, the body outgrew its limit - or, as
 * {@link CooldownException}, it was never sent. An HTTP error status is not a
 * failure; it comes back as a {@link FetchResponse}.
 */
public class FetchException extends IOException {

    public FetchException(String message) {
        super(message);
    }

    public FetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
