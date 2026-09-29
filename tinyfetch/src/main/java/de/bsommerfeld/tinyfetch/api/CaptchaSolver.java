package de.bsommerfeld.tinyfetch.api;

/**
 * The worst case: a site wants a person. TinyFetch hands every
 * {@link Wall#CHALLENGE} to this - the application implements it to ask its
 * user, and on their go-ahead let them solve the CAPTCHA themselves.
 *
 * <p>A person solves it, never the program. Nothing is clicked or filled in
 * automatically; TinyFetch only takes the answer.
 *
 * <h3>When it is called</h3>
 * On its own thread, right after the challenge came back - the request that
 * met it has already returned, and the host is paused as after any wall. At
 * most one call per host at a time; it may block for as long as the person
 * needs. Returning {@code true} ends the host's pause, so the next request
 * goes out at once instead of after the back-off.
 *
 * <pre>{@code
 * CaptchaSolver solver = challenge -> {
 *     if (!askUser(challenge.host())) {          // the app's own dialog
 *         return false;
 *     }
 *     return letThemSolve(challenge.url());      // the site's page, as it is
 * };
 * }</pre>
 */
@FunctionalInterface
public interface CaptchaSolver {

    /** For clients without a person to ask: every CAPTCHA stays unsolved. */
    CaptchaSolver NOBODY = challenge -> false;

    /**
     * @return whether the person solved it; {@code false} when they declined
     *         or gave up
     */
    boolean solve(CaptchaChallenge challenge) throws InterruptedException;
}
