package de.bsommerfeld.wsbg.terminal.ui.net;

import de.bsommerfeld.wsbg.terminal.reddit.RedditResponse;
import de.bsommerfeld.wsbg.terminal.reddit.RedditTransport;
import de.bsommerfeld.wsbg.terminal.ui.CefHost;

import java.time.Duration;

/**
 * {@link RedditTransport} backed by the embedded Chromium runtime. This is the
 * path the {@code reddit} module's interface doc anticipates: Reddit's bot
 * detection 403s the anonymous {@code .json} endpoint for any non-browser
 * client (TLS/HTTP fingerprint + JS challenge), but a same-origin {@code fetch}
 * from a real {@code reddit.com} document sails through with the browser's
 * fingerprint and cookies. See {@link CefFetchClient} for the mechanism.
 *
 * <p>
 * Anchored at {@code https://www.reddit.com/}, so every {@code www.reddit.com}
 * listing, {@code /by_id/} and comment-tree URL the scraper builds is
 * same-origin and needs no CORS handshake. Rate limiting stays in the scraper;
 * this class only fetches.
 */
public final class CefRedditTransport implements RedditTransport {

    private static final String ANCHOR = "https://www.reddit.com/";

    /**
     * Per-request ceiling. Deep comment fetches are the slow case (large body,
     * chunked back over the router); 30 s is generous headroom over a healthy
     * fetch while still failing fast enough for the fallback chain to move on.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final CefFetchClient fetch;

    /**
     * @param probeSubreddit the subreddit used to build the readiness-verification
     *                       URL ({@code /r/<sub>/new.json?limit=1}) — a cheap call
     *                       that only returns 200 once Chromium is past the bot wall
     */
    public CefRedditTransport(CefHost cefHost, String probeSubreddit) {
        String verifyUrl = "https://www.reddit.com/r/" + probeSubreddit + "/new.json?limit=1";
        this.fetch = new CefFetchClient(cefHost, ANCHOR, verifyUrl, "reddit");
    }

    @Override
    public RedditResponse get(String url) throws Exception {
        CefFetchClient.HttpResult r = fetch.fetch(url, REQUEST_TIMEOUT);
        return new RedditResponse(r.status(), r.body(), r.headers());
    }
}
