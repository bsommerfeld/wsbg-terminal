package de.bsommerfeld.wsbg.terminal.source.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A {@link WebFetcher} that resolves a URL by trying an ordered list of
 * strategies and returning the first <em>definitive</em> answer (2xx or 404).
 * A block-ish status (403/429/5xx/0) or a thrown transport error falls through
 * to the next strategy; if every strategy falls through, the last response (or a
 * synthetic failure) is returned so the caller still sees a status to act on.
 *
 * <p>This is the "array of fetch methods, tried in order" model: e.g.
 * {@code [browser, direct]} prefers the browser transport (which behaves like an
 * ordinary browser session) and falls back to plain HTTP, with no source-specific
 * glue. The set and order are pure wiring.
 */
public final class WebFetchChain implements WebFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(WebFetchChain.class);

    private final List<WebFetcher> strategies;

    public WebFetchChain(List<WebFetcher> strategies) {
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("WebFetchChain needs at least one strategy");
        }
        this.strategies = List.copyOf(strategies);
    }

    @Override
    public String name() {
        StringBuilder sb = new StringBuilder("chain[");
        for (int i = 0; i < strategies.size(); i++) {
            if (i > 0) sb.append('→');
            sb.append(strategies.get(i).name());
        }
        return sb.append(']').toString();
    }

    @Override
    public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) throws Exception {
        WebResponse last = WebResponse.failure();
        Exception lastError = null;
        for (WebFetcher f : strategies) {
            try {
                WebResponse r = f.fetch(url, headers, timeout);
                if (r.isDefinitive()) return r;
                LOG.debug("Fetch strategy '{}' → HTTP {} for {}, trying next", f.name(), r.status(), url);
                last = r;
            } catch (Exception e) {
                LOG.debug("Fetch strategy '{}' failed for {}: {}, trying next", f.name(), url, e.getMessage());
                lastError = e;
            }
        }
        // Every strategy fell through. Prefer a real (block) response over an
        // exception so the caller can branch on the status (e.g. trip a breaker).
        if (last.status() != 0) return last;
        if (lastError != null) throw lastError;
        return last;
    }
}
