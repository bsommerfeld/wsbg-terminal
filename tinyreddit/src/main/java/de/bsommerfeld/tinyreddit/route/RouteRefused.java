package de.bsommerfeld.tinyreddit.route;

import de.bsommerfeld.tinyfetch.api.FetchResponse;
import de.bsommerfeld.tinyfetch.api.Wall;

/** Reddit answered, but not with the data: a wall or an error status. */
public final class RouteRefused extends Exception {

    private final int status;
    private final Wall wall;

    public RouteRefused(FetchResponse response) {
        super("HTTP " + response.status() + (response.wall() == Wall.NONE ? "" : " " + response.wall()));
        this.status = response.status();
        this.wall = response.wall();
    }

    public int status() {
        return status;
    }

    public Wall wall() {
        return wall;
    }

    /** Reddit said no, as opposed to an error on its side: the route is to be left alone for a while. */
    public boolean isRefusal() {
        return wall != Wall.NONE;
    }
}
