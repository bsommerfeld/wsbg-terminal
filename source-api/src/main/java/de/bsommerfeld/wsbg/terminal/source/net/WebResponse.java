package de.bsommerfeld.wsbg.terminal.source.net;

import java.util.Map;
import java.util.Optional;

/**
 * Transport-agnostic view of an HTTP response, shared by every {@link WebFetcher}
 * strategy so a caller stays oblivious to whether the bytes came from a plain
 * JDK client or the embedded browser. Mirrors the shape the consumers actually
 * need: a status code, the raw body, and case-insensitive header access.
 *
 * @param status  HTTP status code, or {@code 0} when the transport failed before
 *                a response was received
 * @param body    response body as text (never {@code null}; empty on failure)
 * @param headers response headers; lookups via {@link #header(String)} are
 *                case-insensitive
 */
public record WebResponse(int status, String body, Map<String, String> headers) {

    public WebResponse {
        if (body == null) body = "";
        if (headers == null) headers = Map.of();
    }

    /** A transport-level failure with no HTTP response (status {@code 0}). */
    public static WebResponse failure() {
        return new WebResponse(0, "", Map.of());
    }

    /** True when the status is a definitive answer (2xx success or 404 not-found). */
    public boolean isDefinitive() {
        return (status >= 200 && status < 300) || status == 404;
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
