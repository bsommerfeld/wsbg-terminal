package de.bsommerfeld.wsbg.terminal.source.net;

import java.time.Duration;
import java.util.Map;

/**
 * One strategy for fetching a URL — a plain HTTP client, the embedded browser,
 * a proxy, etc. Sources don't pick a strategy; they declare an ordered list of
 * them ({@link WebFetchChain}) and let the chain resolve the first that works.
 * This is the seam that lets any news wire opt into the browser "joker" (which
 * sends requests as ordinary browser traffic, with a real session and cookies)
 * while keeping a plain-HTTP fallback, all by wiring rather than by code.
 *
 * <p>Implementations must be thread-safe and must NOT throttle on their own —
 * rate limiting belongs to the caller. Ordinary HTTP error statuses (403, 429,
 * …) are returned in {@link WebResponse#status()}, not thrown; throwing is
 * reserved for transport failures (timeout, interrupt, browser not ready).
 */
public interface WebFetcher {

    /** Short, stable identifier for logging (e.g. {@code "browser"}, {@code "direct"}). */
    String name();

    /**
     * Performs a GET against {@code url}.
     *
     * @param headers request headers to apply where the transport allows it. A
     *                browser-backed transport ignores session-controlled headers
     *                (it sets its own {@code User-Agent} etc.); a direct transport
     *                applies them verbatim. May be empty, never null.
     * @param timeout per-request ceiling
     */
    WebResponse fetch(String url, Map<String, String> headers, Duration timeout) throws Exception;
}
