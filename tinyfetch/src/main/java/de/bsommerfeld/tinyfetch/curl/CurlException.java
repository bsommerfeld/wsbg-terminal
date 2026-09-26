package de.bsommerfeld.tinyfetch.curl;

/** A libcurl call failed; {@link #code()} is the {@code CURLcode}, or -1 when there is none. */
public final class CurlException extends Exception {

    /** {@code CURLE_OPERATION_TIMEDOUT} */
    public static final int TIMED_OUT = 28;

    /** {@code CURLE_ABORTED_BY_CALLBACK}: the transfer was cancelled from Java. */
    public static final int ABORTED = 42;

    /** {@code CURLE_WRITE_ERROR}: the body outgrew its limit. */
    public static final int WRITE_ERROR = 23;

    private final int code;

    public CurlException(String message, int code) {
        super(message);
        this.code = code;
    }

    public CurlException(String message, Throwable cause) {
        super(message, cause);
        this.code = -1;
    }

    public int code() {
        return code;
    }
}
