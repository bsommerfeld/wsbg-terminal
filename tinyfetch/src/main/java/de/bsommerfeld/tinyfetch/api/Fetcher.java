package de.bsommerfeld.tinyfetch.api;

/**
 * Anything that answers a {@link FetchRequest}. {@link TinyFetch} is the real
 * one; code built on top takes this interface, so its tests can answer from
 * memory without the native library.
 */
@FunctionalInterface
public interface Fetcher {

    /**
     * Sends the request once the host's pace allows it, and returns the
     * answer - any HTTP status, walls included.
     *
     * @throws CooldownException   the host is paused after a wall; nothing was sent
     * @throws FetchException      no HTTP answer (network, TLS, timeout, body too large)
     * @throws InterruptedException interrupted while waiting for the host's pace
     */
    FetchResponse fetch(FetchRequest request) throws FetchException, InterruptedException;
}
