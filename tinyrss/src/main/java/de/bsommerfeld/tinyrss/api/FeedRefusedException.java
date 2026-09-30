package de.bsommerfeld.tinyrss.api;

import de.bsommerfeld.tinyfetch.api.FetchResponse;
import de.bsommerfeld.tinyfetch.api.Wall;

/** The host answered, but not with the feed: an error status or a wall. */
public final class FeedRefusedException extends FeedException {

    private final transient FetchResponse response;

    public FeedRefusedException(FetchResponse response) {
        super(response.url() + " answered HTTP " + response.status()
                + (response.wall() == Wall.NONE ? "" : " " + response.wall()));
        this.response = response;
    }

    /** The answer as it came - status, wall, headers, body. */
    public FetchResponse response() {
        return response;
    }

    public int status() {
        return response.status();
    }

    public Wall wall() {
        return response.wall();
    }
}
