package de.bsommerfeld.updater.json;

/**
 * Thrown when JSON input cannot be parsed into the expected structure.
 */
public class JsonParseException extends RuntimeException {

    public JsonParseException(String message) {
        super(message);
    }

    public JsonParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
