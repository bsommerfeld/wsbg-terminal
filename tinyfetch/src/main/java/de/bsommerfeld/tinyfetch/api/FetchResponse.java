package de.bsommerfeld.tinyfetch.api;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * An HTTP answer - any status, error statuses included. Whether the host let
 * the request through is {@link #wall()}; whether the answer is usable is
 * {@link #ok()}.
 */
public final class FetchResponse {

    private final int status;
    private final URI url;
    private final String httpVersion;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final Wall wall;
    private final boolean revalidated;

    /**
     * @param headers     header name to values; looked up case-insensitively
     * @param revalidated the body is the cached copy the server confirmed with a 304
     */
    public FetchResponse(int status, URI url, String httpVersion, Map<String, List<String>> headers, byte[] body,
            Wall wall, boolean revalidated) {
        this.status = status;
        this.url = url;
        this.httpVersion = httpVersion;
        TreeMap<String, List<String>> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> sorted.put(name, List.copyOf(values)));
        this.headers = Collections.unmodifiableMap(sorted);
        this.body = body;
        this.wall = wall;
        this.revalidated = revalidated;
    }

    /** Parses raw {@code Name: value} lines as they came off the wire. */
    public static Map<String, List<String>> parseHeaderLines(List<String> lines) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
        return headers;
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

    /** {@code HTTP/2}, {@code HTTP/1.1}, ... */
    public String httpVersion() {
        return httpVersion;
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

    /**
     * The server answered {@code 304 Not Modified} and this is the copy
     * TinyFetch kept from last time; {@link #status()} reads {@code 200}.
     */
    public boolean revalidated() {
        return revalidated;
    }

    FetchResponse asRevalidated(byte[] cachedBody, Map<String, List<String>> cachedHeaders) {
        Map<String, List<String>> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        merged.putAll(cachedHeaders);
        merged.putAll(headers);
        return new FetchResponse(200, url, httpVersion, merged, cachedBody, Wall.NONE, true);
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
        return status + " " + url + " (" + httpVersion + ", " + body.length + " bytes"
                + (wall == Wall.NONE ? "" : ", " + wall) + (revalidated ? ", revalidated" : "") + ")";
    }
}
