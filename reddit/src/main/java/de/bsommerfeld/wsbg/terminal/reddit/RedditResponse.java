package de.bsommerfeld.wsbg.terminal.reddit;

import java.util.Map;
import java.util.Optional;

/**
 * Transport-agnostic view of a Reddit HTTP response.
 *
 * <p>
 * {@link RedditScraper} only needs the status code, the raw body, and
 * occasional access to response headers (for rate-limit backoff). Modelling
 * those three fields explicitly — instead of leaking {@code HttpResponse} or a
 * browser-specific type — lets the scraper stay oblivious to whether the bytes
 * came from a JDK {@code HttpClient} or the embedded browser.
 *
 * @param statusCode HTTP status code, or a negative sentinel when the transport
 *                   failed before a response was received
 * @param body       response body as text (may be empty, never {@code null})
 * @param headers    response headers; lookups via {@link #header(String)} are
 *                   case-insensitive
 */
public record RedditResponse(int statusCode, String body, Map<String, String> headers) {

    public RedditResponse {
        if (body == null) body = "";
        if (headers == null) headers = Map.of();
    }

    /** Case-insensitive header lookup. */
    public Optional<String> header(String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return Optional.ofNullable(e.getValue());
            }
        }
        return Optional.empty();
    }
}
