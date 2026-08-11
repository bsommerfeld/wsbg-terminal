package de.bsommerfeld.wsbg.terminal.web.source;

import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Skeleton for hand-written sources: holds the injected house fetcher and
 * routes every request through the source's own declared {@link #mode()} —
 * so an implementation can never accidentally fetch outside its declared
 * transport order. Everything else (parsing, addressing) stays with the
 * concrete source.
 */
public abstract class AbstractWebSource implements WebSource {

    /** The default per-request ceiling when a source doesn't override. */
    protected static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private final WebFetcher fetcher;

    protected AbstractWebSource(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Fetches {@code url} over this source's declared transport order. */
    protected final WebResponse get(String url, Map<String, String> headers, Duration timeout)
            throws Exception {
        return fetcher.fetch(url, headers, timeout, mode());
    }

    protected final WebResponse get(String url, Map<String, String> headers) throws Exception {
        return get(url, headers, DEFAULT_TIMEOUT);
    }

    /** The image path: bytes stay undecoded. */
    protected final WebResponse getBinary(String url, Map<String, String> headers, Duration timeout)
            throws Exception {
        return fetcher.fetchBinary(url, headers, timeout, mode());
    }

    /** The outlier door: a POST over this source's declared transport order. */
    protected final WebResponse post(String url, Map<String, String> headers, String body,
            String contentType, Duration timeout) throws Exception {
        return fetcher.post(url, headers, body, contentType, timeout, mode());
    }

    /** {@code true} while the host is cooling down — polite callers skip. */
    protected final boolean hostCoolingDown(String url) {
        return fetcher.hostCoolingDown(url);
    }
}
