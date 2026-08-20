package de.bsommerfeld.wsbg.terminal.web.fetch;

import java.util.Map;
import java.util.Optional;

/**
 * Transport-agnostic view of an HTTP response, shared by every transport so a
 * caller stays oblivious to whether the bytes came from a plain JDK client or
 * the embedded browser.
 *
 * <p>Text is the house output: {@link #body()} is the decoded text every
 * source parses. The ONE declared exception is binary content (images): a
 * transport that fetched bytes it could not decode as text hands them back in
 * {@link #raw()} with an empty body. Callers that want bytes ask {@link #raw()};
 * everyone else never sees them.
 *
 * @param status  HTTP status code, or {@code 0} when the transport failed before
 *                a response was received
 * @param body    response body as text (never {@code null}; empty on failure or
 *                for binary content)
 * @param raw     undecoded response bytes for binary content, or an empty array
 *                when the response was text (never {@code null})
 * @param headers response headers; lookups via {@link #header(String)} are
 *                case-insensitive
 */
public record WebResponse(int status, String body, byte[] raw, Map<String, String> headers) {

    private static final byte[] NO_BYTES = new byte[0];

    public WebResponse {
        if (body == null) body = "";
        if (raw == null) raw = NO_BYTES;
        if (headers == null) headers = Map.of();
    }

    /** A text response — the normal case. */
    public static WebResponse text(int status, String body, Map<String, String> headers) {
        return new WebResponse(status, body, NO_BYTES, headers);
    }

    /** A binary response (images) — bytes untouched, body empty. */
    public static WebResponse binary(int status, byte[] raw, Map<String, String> headers) {
        return new WebResponse(status, "", raw, headers);
    }

    /** A transport-level failure with no HTTP response (status {@code 0}). */
    public static WebResponse failure() {
        return new WebResponse(0, "", NO_BYTES, Map.of());
    }

    /** {@code true} when the transport handed back undecoded bytes. */
    public boolean isBinary() {
        return raw.length > 0;
    }

    /**
     * True when the status is a definitive answer another transport could not
     * improve on, so the fetcher must stop here: 2xx success, <b>304
     * not-modified</b> (the conditional-request success — without it a
     * direct-first order would "fall through" every cache revalidation into
     * the browser joker and refetch a full 200), and the plain client errors
     * that are about the REQUEST, not the client's fingerprint
     * (404/400/405/410/422/451 — a real browser gets the same answer; 401
     * stays NON-definitive on purpose: crumb-locked endpoints 401 a bare
     * client but can answer a browser session). Wall-shaped statuses
     * (403/429/5xx) stay non-definitive so the joker can rescue them.
     */
    public boolean isDefinitive() {
        return (status >= 200 && status < 300) || status == 304
                || status == 404 || status == 400 || status == 405
                || status == 410 || status == 422 || status == 451;
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
