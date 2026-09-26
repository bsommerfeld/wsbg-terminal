package de.bsommerfeld.tinyfetch.api;

import java.util.Set;

/**
 * The session side of a client: what it holds for a site, and the way to get
 * a session a site only issues to a real browser. {@link TinyFetch} is one;
 * code built on top asks for this interface next to {@link Fetcher}.
 */
public interface BrowserSession {

    /** Whether a live (not expired) cookie {@code name} would be sent to {@code host}. */
    boolean hasCookie(String host, String name);

    /** Whether {@link #unlock} can do anything - a {@link SessionUnlocker} is configured. */
    boolean canUnlock();

    /**
     * Has the engine open {@code url} until the cookies in {@code awaitCookies}
     * exist, and takes them over. Does nothing when they already do.
     *
     * @throws CaptchaRequiredException a person was needed and none solved it
     * @throws FetchException           the engine failed
     */
    void unlock(String url, Set<String> awaitCookies) throws FetchException, InterruptedException;
}
