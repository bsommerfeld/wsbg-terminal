package de.bsommerfeld.tinyfetch.api;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * An HTTP answer - any status, error statuses included. Whether the host let
 * the request through is {@link #wall()}; whether the answer is usable is
 * {@link #ok()}.
 *
 * <p>The headers are the ones a page's script may read: {@code set-cookie}
 * never shows, the browser keeps the cookies itself.
 */
public final class FetchResponse {

    private final int status;
    private final URI url;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final Wall wall;

    /**
     * @param headers header name to values; looked up case-insensitively
     */
    public FetchResponse(int status, URI url, Map<String, List<String>> headers, byte[] body, Wall wall) {
        this.status = status;
        this.url = url;
        TreeMap<String, List<String>> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> sorted.put(name, List.copyOf(values)));
        this.headers = Collections.unmodifiableMap(sorted);
        this.body = body;
        this.wall = wall;
    }

    public int status() {
        return status;
    }

    /** {@code 2xx} and let through. */
    public boolean ok() {
        return status >= 200 && status < 300 && wall == Wall.NONE;
    }

    /** Where the answer came from, after redirects. */
    public URI url() {
        return url;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    /** The first value of {@code name}, any case. */
    public Optional<String> header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    /** The decoded body. */
    public byte[] body() {
        return body.clone();
    }

    /** The body as text, in the charset {@code content-type} names (UTF-8 otherwise). */
    public String text() {
        return new String(body, charset());
    }

    public Wall wall() {
        return wall;
    }

    private Charset charset() {
        String contentType = header("content-type").orElse("");
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "charset=", 0, 8)) {
                try {
                    return Charset.forName(trimmed.substring(8).replace("\"", "").trim());
                } catch (RuntimeException e) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    @Override
    public String toString() {
        return status + " " + url + " (" + body.length + " bytes" + (wall == Wall.NONE ? "" : ", " + wall) + ")";
    }
}
