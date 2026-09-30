package de.bsommerfeld.tinyrss.api;

/**
 * An answer came, but no feed: the host refused ({@link FeedRefusedException})
 * or the body is something else ({@link NotAFeedException}). A request that
 * produced no answer at all is TinyFetch's {@code FetchException}.
 */
public abstract sealed class FeedException extends Exception permits FeedRefusedException, NotAFeedException {

    FeedException(String message) {
        super(message);
    }
}
