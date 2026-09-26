package de.bsommerfeld.tinyreddit.api;

import java.util.Map;

/**
 * Reddit answered that the thing does not exist (404) - a subreddit, post or
 * comment page. Definitive: no other route is asked, it would say the same.
 */
public final class NotFoundException extends RedditException {

    public NotFoundException(String what, Route route) {
        super(what + " not found", Map.of(route, "404"));
    }
}
