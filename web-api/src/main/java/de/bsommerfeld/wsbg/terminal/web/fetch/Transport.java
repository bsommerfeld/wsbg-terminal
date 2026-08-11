package de.bsommerfeld.wsbg.terminal.web.fetch;

import java.time.Duration;
import java.util.Map;

/**
 * ONE way of moving a request over the wire — the implementation behind a
 * single {@link FetchUtil} value. web-impl ships {@link FetchUtil#DIRECT};
 * the terminal contributes {@link FetchUtil#BROWSER} (it owns the embedded
 * Chromium). Sources never see transports: they declare {@code mode()} and the
 * {@link WebFetcher} resolves the array against the registered transports.
 *
 * <p>Implementations must be thread-safe and must NOT throttle on their own —
 * pacing, cooldowns and retries belong to the {@link WebFetcher}. Ordinary
 * HTTP error statuses (403, 429, …) are returned in
 * {@link WebResponse#status()}, not thrown; throwing is reserved for transport
 * failures (timeout, interrupt, browser not ready).
 */
public interface Transport {

    /** Which {@link FetchUtil} slot this transport fills. */
    FetchUtil util();

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

    /** {@code true} when this transport can hand back undecoded bytes (images). */
    default boolean supportsBinary() {
        return false;
    }

    /** {@code true} when this transport can carry a request body (POST). */
    default boolean supportsPost() {
        return false;
    }

    /**
     * Performs a POST against {@code url} with the given body. Only transports
     * that declare {@link #supportsPost()} implement this; the fetcher skips
     * the others on the POST path. POSTs are never conditionally cached.
     */
    default WebResponse post(String url, Map<String, String> headers, String body,
            String contentType, Duration timeout) throws Exception {
        throw new UnsupportedOperationException(util() + " transport cannot POST");
    }

    /**
     * Like {@link #fetch} but the bytes stay undecoded. Only transports that
     * declare {@link #supportsBinary()} implement this; the fetcher skips the
     * others on the binary path.
     */
    default WebResponse fetchBinary(String url, Map<String, String> headers, Duration timeout)
            throws Exception {
        throw new UnsupportedOperationException(util() + " transport is text-only");
    }
}
