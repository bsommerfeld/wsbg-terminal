package de.bsommerfeld.tinyrss.api;

/**
 * The body is no feed: an HTML page - a site's front page, an error shell
 * served with {@code 200}, a consent page - JSON, or nothing at all.
 */
public final class NotAFeedException extends FeedException {

    public NotAFeedException(String message) {
        super(message);
    }
}
