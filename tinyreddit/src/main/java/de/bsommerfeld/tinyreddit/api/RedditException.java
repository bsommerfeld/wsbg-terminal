package de.bsommerfeld.tinyreddit.api;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * No route could answer. {@link #attempts()} says what each route ran into -
 * a pause, a wall, a network error, or that it was skipped.
 */
public class RedditException extends IOException {

    private final Map<Route, String> attempts;

    public RedditException(String message, Map<Route, String> attempts) {
        super(message + " " + attempts);
        this.attempts = Collections.unmodifiableMap(new LinkedHashMap<>(attempts));
    }

    /** Route to what happened there, in the order they were tried. */
    public Map<Route, String> attempts() {
        return attempts;
    }
}
