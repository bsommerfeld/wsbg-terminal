package de.bsommerfeld.tinyreddit.route;

import de.bsommerfeld.tinyfetch.api.BrowserSession;
import de.bsommerfeld.tinyfetch.api.CaptchaRequiredException;
import de.bsommerfeld.tinyfetch.api.FetchException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Reddit's visitor session on {@code www.reddit.com}: {@code loid} (who the
 * logged-out visitor is, kept for a year) and {@code token_v2} (their access
 * token, renewed daily). Reddit hands both out only to a browser that ran its
 * page - so a client without them first has the session unlocked, and every
 * anonymous request after that goes out as a script of that page.
 *
 * <p>Without a {@link BrowserSession} that can unlock, this does nothing and
 * the routes ask Reddit as they are.
 *
 * <p>A CAPTCHA nobody solved is remembered for {@link #REFUSAL_MEMORY}: every
 * route shares this session, and the next one asking must not start the
 * engine - or ask the person - all over again.
 */
public final class RedditSession {

    /** The page the session is opened on, and the referer of every request made "from" it. */
    public static final String HOME = "https://www.reddit.com/";

    static final String HOST = "www.reddit.com";
    static final Set<String> COOKIES = new LinkedHashSet<>(List.of("loid", "token_v2"));

    static final Duration REFUSAL_MEMORY = Duration.ofMinutes(30);

    private final BrowserSession browser;
    private final Supplier<Instant> clock;
    private CaptchaRequiredException refusal;
    private Instant refusedUntil = Instant.EPOCH;

    /** @param browser the client's session side; {@code null} when it has none */
    public RedditSession(BrowserSession browser, Supplier<Instant> clock) {
        this.browser = browser;
        this.clock = clock;
    }

    /**
     * Opens the session if it is missing or expired; one engine run, only then.
     *
     * @throws CaptchaRequiredException Reddit wants a person and none solved it (now or recently)
     */
    public synchronized void ensure() throws FetchException, InterruptedException {
        if (browser == null || !browser.canUnlock()) {
            return;
        }
        if (COOKIES.stream().allMatch(name -> browser.hasCookie(HOST, name))) {
            return;
        }
        if (refusal != null && clock.get().isBefore(refusedUntil)) {
            throw refusal;
        }
        try {
            browser.unlock(HOME, COOKIES);
            refusal = null;
        } catch (CaptchaRequiredException e) {
            refusal = e;
            refusedUntil = clock.get().plus(REFUSAL_MEMORY);
            throw e;
        }
    }
}
