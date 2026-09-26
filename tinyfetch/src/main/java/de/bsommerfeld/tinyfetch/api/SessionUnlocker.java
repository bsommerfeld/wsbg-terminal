package de.bsommerfeld.tinyfetch.api;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * Opens a page in a real browser engine so the site can issue its session -
 * for sites that first check that the visitor runs a browser (a script, a
 * redirect chain) before they hand out the cookies everything else needs.
 * TinyFetch runs no JavaScript; the engine does, once, and TinyFetch carries
 * the session on from there.
 *
 * <p>The engine and TinyFetch must present as the same browser: a site that
 * issued a session to one fingerprint sees a different one come back with it.
 * {@link #browser()} is checked against {@link TinyFetch.Builder#browser}.
 *
 * @see ProcessUnlocker
 */
public interface SessionUnlocker {

    /** The browser the engine is - the one TinyFetch has to present as. */
    Browser browser();

    /**
     * Opens {@code page} until every cookie in {@code awaitCookies} exists.
     *
     * @return the cookies the site set, as Netscape cookie-file lines
     * @throws CaptchaRequiredException the site wants a person, and none solved it
     * @throws FetchException           the engine failed or timed out
     */
    List<String> unlock(URI page, Set<String> awaitCookies) throws FetchException, InterruptedException;
}
