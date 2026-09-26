package de.bsommerfeld.tinyfetch.api;

import java.util.List;
import java.util.Optional;

/**
 * The worst case: a site wants a person. The application implements this to
 * ask its user - explain what is going on, and on their go-ahead let them
 * solve the CAPTCHA in a browser window ({@link CaptchaChallenge#openWindow}).
 *
 * <p>A person solves it, never the program: the window shows the site's page
 * as it is, and nothing in it is clicked or filled in automatically. What they
 * solve is kept in the engine's profile, so the headless unlocks after it pass
 * without asking again for as long as the site trusts that session.
 *
 * <pre>{@code
 * CaptchaSolver solver = challenge -> {
 *     if (!askUser(challenge.host())) {          // the app's own dialog
 *         return Optional.empty();
 *     }
 *     return Optional.of(challenge.openWindow(i18n.get("captcha.window.title")));
 * };
 * }</pre>
 */
@FunctionalInterface
public interface CaptchaSolver {

    /** For clients without a person to ask: every CAPTCHA stays unsolved. */
    CaptchaSolver NOBODY = challenge -> Optional.empty();

    /**
     * Called when the headless engine hit a CAPTCHA. Blocks until the person
     * is done - which may take minutes - or has declined.
     *
     * @return the cookies after the person solved it; empty when they did not
     * @throws FetchException the window could not be opened or failed
     */
    Optional<List<String>> solveCaptcha(CaptchaChallenge challenge) throws FetchException, InterruptedException;
}
