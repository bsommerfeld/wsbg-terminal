package de.bsommerfeld.tinysearch.page;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Turns the links on a result page into the addresses they lead to. Some
 * engines send every click through a redirect of their own that carries
 * the target in its query; the target is read from there, never followed.
 */
final class Links {

    /** Characters a page may leave raw in an {@code href} that {@link URI} refuses. */
    private static final String UNSAFE = " \"<>\\^`{|}";

    private Links() {
    }

    /** An engine whose links lead straight to the target. */
    static URI direct(URI link) {
        return link;
    }

    /** Bing: {@code bing.com/ck/a?...&u=a1<target, base64url>}. */
    static URI bing(URI link) {
        if (!onHost(link, "bing.com") || !"/ck/a".equals(link.getPath())) {
            return link;
        }
        String encoded = parameter(link, "u");
        if (encoded == null || !encoded.startsWith("a1")) {
            return null;
        }
        try {
            return parse(new String(Base64.getUrlDecoder().decode(encoded.substring(2)), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** DuckDuckGo: {@code duckduckgo.com/l/?uddg=<target>}. */
    static URI duckDuckGo(URI link) {
        if (!onHost(link, "duckduckgo.com") || !"/l/".equals(link.getPath())) {
            return link;
        }
        String target = parameter(link, "uddg");
        return target == null ? null : parse(target);
    }

    /** Yahoo: {@code r.search.yahoo.com/.../RU=<target>/RK=...}, the target percent-encoded in the path. */
    static URI yahoo(URI link) {
        if (!onHost(link, "r.search.yahoo.com")) {
            return link;
        }
        String path = link.getRawPath();
        int start = path == null ? -1 : path.indexOf("/RU=");
        if (start < 0) {
            return null;
        }
        int end = path.indexOf('/', start + 4);
        String target = decode(path.substring(start + 4, end < 0 ? path.length() : end));
        return target == null ? null : parse(target);
    }

    /**
     * An absolute http(s) address, or {@code null}: characters a page may
     * leave raw are percent-encoded first.
     */
    static URI parse(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        StringBuilder escaped = new StringBuilder(address.length());
        for (char c : address.strip().toCharArray()) {
            if (UNSAFE.indexOf(c) >= 0) {
                escaped.append('%').append(String.format(Locale.ROOT, "%02X", (int) c));
            } else {
                escaped.append(c);
            }
        }
        try {
            URI uri = new URI(escaped.toString());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean onHost(URI link, String host) {
        String linkHost = link.getHost();
        if (linkHost == null) {
            return false;
        }
        String lower = linkHost.toLowerCase(Locale.ROOT);
        return lower.equals(host) || lower.endsWith("." + host);
    }

    /** The decoded value of query parameter {@code name}, or {@code null}. */
    private static String parameter(URI link, String name) {
        String query = link.getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return decode(pair.substring(equals + 1));
            }
        }
        return null;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
