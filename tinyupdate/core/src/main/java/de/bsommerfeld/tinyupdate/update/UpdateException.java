package de.bsommerfeld.tinyupdate.update;

/**
 * Thrown when an update operation fails unrecoverably.
 */
public class UpdateException extends Exception {

    public UpdateException(String message) {
        super(message);
    }

    public UpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
