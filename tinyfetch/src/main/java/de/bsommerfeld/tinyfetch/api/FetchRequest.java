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
 * One request, described the way a browser would make it. Immutable; every
 * {@code with}-style method returns a new request.
 *
 * <h3>Two kinds, because a browser has two</h3>
 * <ul>
 *   <li>{@link #page(String)} - the person opens the address: typed, bookmarked
 *       or followed from a link ({@link #referer}). The browser asks for a
 *       document ({@code sec-fetch-mode: navigate}). Use it for pages, feeds,
 *       and for an API address a person would open directly.</li>
 *   <li>{@link #data(String)} - the site's own script asks its backend, as the
 *       open page does while the person watches it ({@code sec-fetch-mode: cors}).
 *       Use it for the JSON endpoints a site's frontend calls; the referer is
 *       the page that would make the call and defaults to the target's origin.</li>
 * </ul>
 * Picking the kind that matches how a person would actually cause the request
 * is what keeps the traffic indistinguishable from theirs: an XHR-only endpoint
 * opened as a top-level document, or a document fetched in CORS mode, stands out.
 */
public final class FetchRequest {

    /** How the request comes about in a browser. */
    public enum Kind {
        /** Top-level navigation: the person opens the address. */
        PAGE,
        /** A script of the open page fetches it. */
        DATA
    }

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final URI uri;
    private final Kind kind;
    private final String method;
    private final String contentType;
    private final byte[] body;
    private final URI referer;
    private final List<Map.Entry<String, String>> headers;
    private final Duration timeout;

    private FetchRequest(URI uri, Kind kind, String method, String contentType, byte[] body, URI referer,
            List<Map.Entry<String, String>> headers, Duration timeout) {
        this.uri = uri;
        this.kind = kind;
        this.method = method;
        this.contentType = contentType;
        this.body = body;
        this.referer = referer;
        this.headers = headers;
        this.timeout = timeout;
    }

    /** The person opens {@code url}. */
    public static FetchRequest page(String url) {
        return new FetchRequest(parse(url), Kind.PAGE, "GET", null, null, null, List.of(), DEFAULT_TIMEOUT);
    }

    /** The open page's script fetches {@code url}. */
    public static FetchRequest data(String url) {
        return new FetchRequest(parse(url), Kind.DATA, "GET", null, null, null, List.of(), DEFAULT_TIMEOUT);
    }

    /**
     * The page the request comes from: the page a link was clicked on, or the
     * page whose script fetches. Cut down to its origin when it is not the
     * target's own origin, as Chrome's default referrer policy does.
     */
    public FetchRequest referer(String referer) {
        return new FetchRequest(uri, kind, method, contentType, body, parse(referer), headers, timeout);
    }

    /**
     * A header of the caller's own, e.g. {@code authorization}. It replaces the
     * browser's header of the same name in place - so overriding {@code accept}
     * keeps its position - otherwise it joins after {@code accept}.
     */
    public FetchRequest header(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (name.isBlank() || name.contains(":") || name.contains("\n") || value.contains("\n")) {
            throw new IllegalArgumentException("invalid header: " + name);
        }
        List<Map.Entry<String, String>> copy = new ArrayList<>(headers);
        copy.add(Map.entry(name.toLowerCase(Locale.ROOT), value));
        return new FetchRequest(uri, kind, method, contentType, body, referer, List.copyOf(copy), timeout);
    }

    /** Sends {@code body} as a POST, e.g. a form ({@code application/x-www-form-urlencoded}). */
    public FetchRequest post(String contentType, byte[] body) {
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(body, "body");
        return new FetchRequest(uri, kind, "POST", contentType, body.clone(), referer, headers, timeout);
    }

    /** {@link #post(String, byte[])} with a UTF-8 text body. */
    public FetchRequest post(String contentType, String body) {
        return post(contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    /** Whole-transfer timeout, redirects included; 30 s unless set. */
    public FetchRequest timeout(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return new FetchRequest(uri, kind, method, contentType, body, referer, headers, timeout);
    }

    public URI uri() {
        return uri;
    }

    /** Lower-case host - the unit of pacing. */
    public String host() {
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    public Kind kind() {
        return kind;
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

    public Optional<URI> referer() {
        return Optional.ofNullable(referer);
    }

    /** The caller's own headers, names lower-cased, in the order they were added. */
    public List<Map.Entry<String, String>> headers() {
        return headers;
    }

    /** Whether the caller set {@code name} (lower-case) itself. */
    public boolean hasHeader(String name) {
        return headers.stream().anyMatch(header -> header.getKey().equals(name));
    }

    public Duration timeout() {
        return timeout;
    }

    @Override
    public String toString() {
        return method + " " + uri + " (" + kind + ")";
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
