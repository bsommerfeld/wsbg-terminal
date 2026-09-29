package de.bsommerfeld.tinyfetch.api;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One request, as the {@code fetch()} of a page parked on the target's site
 * makes it. Immutable; every {@code with}-style method returns a new request.
 *
 * <p>What a browser owns itself - user agent aside ({@link #header}), cookies,
 * referer, origin, encoding and the {@code sec-*} headers - is the page's to
 * set, not the caller's.
 */
public final class FetchRequest {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final URI uri;
    private final String method;
    private final String contentType;
    private final byte[] body;
    private final List<Map.Entry<String, String>> headers;
    private final Duration timeout;

    private FetchRequest(URI uri, String method, String contentType, byte[] body,
            List<Map.Entry<String, String>> headers, Duration timeout) {
        this.uri = uri;
        this.method = method;
        this.contentType = contentType;
        this.body = body;
        this.headers = headers;
        this.timeout = timeout;
    }

    /** A {@code GET} of {@code url}. */
    public static FetchRequest of(String url) {
        return new FetchRequest(parse(url), "GET", null, null, List.of(), DEFAULT_TIMEOUT);
    }

    /**
     * A header of the caller's own, e.g. {@code authorization} or
     * {@code accept}. A {@code user-agent} replaces the browser's own for this
     * request - for APIs that want the application to name itself; the other
     * names a browser owns are dropped by the page.
     */
    public FetchRequest header(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (name.isBlank() || name.contains(":") || name.contains("\n") || value.contains("\n")) {
            throw new IllegalArgumentException("invalid header: " + name);
        }
        List<Map.Entry<String, String>> copy = new ArrayList<>(headers);
        copy.add(Map.entry(name.toLowerCase(Locale.ROOT), value));
        return new FetchRequest(uri, method, contentType, body, List.copyOf(copy), timeout);
    }

    /** Sends {@code body} as a POST, e.g. a form ({@code application/x-www-form-urlencoded}). */
    public FetchRequest post(String contentType, byte[] body) {
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(body, "body");
        return new FetchRequest(uri, "POST", contentType, body.clone(), headers, timeout);
    }

    /** {@link #post(String, byte[])} with a UTF-8 text body. */
    public FetchRequest post(String contentType, String body) {
        return post(contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    /** How long the page waits for the answer, redirects included; 30 s unless set. */
    public FetchRequest timeout(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return new FetchRequest(uri, method, contentType, body, headers, timeout);
    }

    public URI uri() {
        return uri;
    }

    /** Lower-case host - the unit of pacing. */
    public String host() {
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    public String method() {
        return method;
    }

    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    /** The body, or {@code null} without one. */
    public byte[] body() {
        return body == null ? null : body.clone();
    }

    /** The caller's own headers, names lower-cased, in the order they were added. */
    public List<Map.Entry<String, String>> headers() {
        return headers;
    }

    public Duration timeout() {
        return timeout;
    }

    @Override
    public String toString() {
        return method + " " + uri;
    }

    private static URI parse(String url) {
        Objects.requireNonNull(url, "url");
        URI parsed = URI.create(url);
        String scheme = parsed.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("only http(s) URLs: " + url);
        }
        if (parsed.getHost() == null) {
            throw new IllegalArgumentException("URL without host: " + url);
        }
        return parsed;
    }
}
