package de.bsommerfeld.wsbg.terminal.reddit.net;

import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;

import java.time.Duration;
import java.util.Map;

/**
 * One fetch delegate in Reddit's module-internal fetcher chain
 * (OAuth → anonymous → RSS). The chain is an abstraction private to this
 * module: {@code OAuthRedditFetcher} implements it directly, while the
 * anonymous delegate is handed in at bootstrap as a lambda over the house
 * {@link de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher} with the modes
 * {@code {BROWSER, DIRECT}}.
 */
public interface RedditFetch {

    /** Short, stable identifier for logging (e.g. {@code "oauth"}, {@code "direct"}). */
    String name();

    /**
     * Performs a GET against {@code url}. Ordinary HTTP error statuses come
     * back in {@link WebResponse#status()}; throwing is reserved for transport
     * failures.
     */
    WebResponse fetch(String url, Map<String, String> headers, Duration timeout) throws Exception;
}
