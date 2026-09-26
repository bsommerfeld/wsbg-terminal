package de.bsommerfeld.tinyreddit.json;

/** Input that is not valid JSON. */
public final class JsonException extends RuntimeException {

    public JsonException(String message) {
        super(message);
    }
}
