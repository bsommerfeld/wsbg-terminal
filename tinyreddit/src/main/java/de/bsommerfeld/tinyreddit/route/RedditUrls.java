package de.bsommerfeld.tinyreddit.route;

import java.net.URI;
import java.net.URISyntaxException;

/** Absolute Reddit URLs with path and query percent-encoded - slugs carry umlauts. */
final class RedditUrls {

    static final String WWW = "www.reddit.com";
    static final String OAUTH = "oauth.reddit.com";

    private RedditUrls() {
    }

    static String of(String host, String path, String query) {
        try {
            return new URI("https", host, path, query, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a Reddit path: " + path, e);
        }
    }

    /** Subreddit names are letters, digits and underscores; anything else is a caller bug. */
    static String subreddit(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{2,21}")) {
            throw new IllegalArgumentException("not a subreddit name: " + name);
        }
        return name;
    }

    static String sort(String sort) {
        return switch (sort) {
            case "new", "hot", "rising", "top" -> sort;
            default -> throw new IllegalArgumentException("unknown sort: " + sort);
        };
    }
}
