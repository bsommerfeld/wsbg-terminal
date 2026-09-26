package de.bsommerfeld.tinyreddit.route;

/** A {@code 200} whose body is not what the route expects. */
public final class MalformedAnswerException extends Exception {

    public MalformedAnswerException(String message, Throwable cause) {
        super(message, cause);
    }
}
