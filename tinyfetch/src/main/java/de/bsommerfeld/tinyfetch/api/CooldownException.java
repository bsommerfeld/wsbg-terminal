package de.bsommerfeld.tinyfetch.api;

import java.time.Instant;

/**
 * The host is paused after a wall and the request was not sent. Retrying
 * before {@link #until()} fails the same way; a caller with a fallback (RSS
 * instead of JSON, another source) takes it now.
 */
public final class CooldownException extends FetchException {

    private final String host;
    private final Instant until;
    private final Wall reason;

    public CooldownException(String host, Instant until, Wall reason) {
        super(host + " is paused until " + until + " after " + reason);
        this.host = host;
        this.until = until;
        this.reason = reason;
    }

    public String host() {
        return host;
    }

    /** When the host may be asked again. */
    public Instant until() {
        return until;
    }

    /** The wall that caused the pause. */
    public Wall reason() {
        return reason;
    }
}
