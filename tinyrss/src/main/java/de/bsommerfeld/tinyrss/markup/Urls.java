package de.bsommerfeld.tinyrss.markup;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Addresses as feeds write them: relative, protocol-relative, with spaces or
 * brackets that {@link URI} refuses. Everything comes out absolute, or not at
 * all. Umlauts stay as written - {@link URI} takes them, and a caller that
 * encodes a path itself must not get {@code %C3%B6} to encode a second time.
 */
public final class Urls {

    /** What {@link URI} refuses but browsers take - encoded as they encode it. */
    private static final String ILLEGAL = " \"<>\\^`{|}";

    private Urls() {
    }

    /**
     * {@code reference} resolved against {@code base}.
     *
     * @return the absolute address; {@code null} for a blank reference, and for
     *         a relative one when there is no base to resolve it against
     */
    public static String resolve(String base, String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        String trimmed = reference.trim().replaceAll("[\t\n\r]", "");
        try {
            URI uri = new URI(encode(trimmed));
            if (uri.isAbsolute()) {
                return uri.toString();
            }
            if (base == null || base.isBlank()) {
                return null;
            }
            URI baseUri = new URI(encode(base.trim().replaceAll("[\t\n\r]", "")));
            if (baseUri.getRawPath() == null || baseUri.getRawPath().isEmpty()) {
                baseUri = new URI(baseUri + "/");
            }
            return baseUri.resolve(uri).toString();
        } catch (URISyntaxException | IllegalArgumentException malformed) {
            return null;
        }
    }

    /** Whether {@code url} is an absolute {@code http(s)} address. */
    public static boolean isWeb(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** Percent-encodes what {@link URI} refuses: whitespace, quotes, a lone {@code %}, brackets outside the host. */
    private static String encode(String url) {
        int authorityEnd = authorityEnd(url);
        StringBuilder encoded = null;
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            boolean bracket = (c == '[' || c == ']') && i >= authorityEnd;
            boolean invisible = c >= 0x7F && (Character.isSpaceChar(c) || Character.isISOControl(c));
            if (c < 0x21 || invisible || ILLEGAL.indexOf(c) >= 0 || bracket || c == '%' && !isEscape(url, i)) {
                if (encoded == null) {
                    encoded = new StringBuilder(url.length() + 16).append(url, 0, i);
                }
                int codePoint = url.codePointAt(i);
                for (byte b : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
                    encoded.append('%').append(String.format("%02X", b & 0xFF));
                }
                i += Character.charCount(codePoint) - 1;
            } else if (encoded != null) {
                encoded.append(c);
            }
        }
        return encoded == null ? url : encoded.toString();
    }

    private static boolean isEscape(String url, int i) {
        return i + 2 < url.length()
                && Character.digit(url.charAt(i + 1), 16) >= 0
                && Character.digit(url.charAt(i + 2), 16) >= 0;
    }

    /** Where {@code scheme://host:port} ends, {@code 0} when there is none. */
    private static int authorityEnd(String url) {
        int scheme = url.indexOf("//");
        if (scheme < 0) {
            return 0;
        }
        int end = scheme + 2;
        while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) {
            end++;
        }
        return end;
    }
}
