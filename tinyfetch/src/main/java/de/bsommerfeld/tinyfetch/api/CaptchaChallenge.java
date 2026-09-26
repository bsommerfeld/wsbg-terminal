package de.bsommerfeld.tinyfetch.api;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One CAPTCHA waiting for a person: which page, and the means to show it to
 * them in the engine that will keep the result.
 */
public final class CaptchaChallenge {

    /** Opens the page in a window, for {@link #openWindow}. */
    @FunctionalInterface
    interface Window {
        List<String> open(URI page, Set<String> awaitCookies, String title)
                throws FetchException, InterruptedException;
    }

    private final URI page;
    private final Set<String> awaitCookies;
    private final Window window;

    CaptchaChallenge(URI page, Set<String> awaitCookies, Window window) {
        this.page = page;
        this.awaitCookies = Set.copyOf(awaitCookies);
        this.window = window;
    }

    /** The page that showed the CAPTCHA. */
    public URI page() {
        return page;
    }

    /** The site asking, e.g. {@code www.reddit.com} - for telling the person who wants what. */
    public String host() {
        return page.getHost().toLowerCase(Locale.ROOT);
    }

    /**
     * Shows the page in a browser window of the engine and waits until the
     * person has solved the CAPTCHA and the site issued its session.
     *
     * @param title the window's title, in the user's language
     * @return the session's cookies, as Netscape cookie-file lines
     * @throws CaptchaRequiredException the person closed the window unsolved
     */
    public List<String> openWindow(String title) throws FetchException, InterruptedException {
        return window.open(page, awaitCookies, title);
    }
}
