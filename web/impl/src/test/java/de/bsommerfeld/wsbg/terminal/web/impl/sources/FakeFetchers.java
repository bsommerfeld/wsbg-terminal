package de.bsommerfeld.wsbg.terminal.web.impl.sources;

import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;

import java.time.Duration;
import java.util.Map;

/**
 * Shared fake {@link WebFetcher}s for the facts-source tests (the 4-method
 * seam, same pattern as {@code GoogleNewsSourceTest}): a no-network stub for
 * parse-only tests and a functional adapter for scripted transports.
 */
public final class FakeFetchers {

    private FakeFetchers() {}

    /** One-method functional shape so tests can script fetches with a lambda. */
    @FunctionalInterface
    public interface Fetch {
        WebResponse apply(String url, Map<String, String> headers, Duration timeout)
                throws Exception;
    }

    /** A fetcher that answers GETs via {@code f}; binary/post are unsupported. */
    public static WebFetcher fetcher(Fetch f) {
        return new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                    FetchUtil... modes) throws Exception {
                return f.apply(url, headers, timeout);
            }

            @Override
            public WebResponse fetchBinary(String url, Map<String, String> headers,
                    Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WebResponse post(String url, Map<String, String> headers, String body,
                    String contentType, Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hostCoolingDown(String url) {
                return false;
            }
        };
    }

    /** For parse-only tests: any network attempt is a test failure. */
    public static WebFetcher noNetwork() {
        return fetcher((url, headers, timeout) -> {
            throw new AssertionError("no network expected, but fetched: " + url);
        });
    }
}
