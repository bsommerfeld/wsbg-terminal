package de.bsommerfeld.tinyfetch.curl;

import java.util.List;

/**
 * What came back: the final response after redirects.
 *
 * @param headerLines the final response's header lines, status line excluded
 * @param body        decoded body (libcurl has already undone gzip/br/zstd)
 */
public record CurlResponse(
        int status,
        String effectiveUrl,
        String httpVersion,
        List<String> headerLines,
        byte[] body) {
}
