package de.bsommerfeld.tinyfetch.pacing;

import de.bsommerfeld.tinyfetch.api.Wall;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Decides whether an answer is a {@link Wall}.
 *
 * <h3>Status codes are not enough</h3>
 * Challenge pages come with any status: Cloudflare's interstitial with 403 or
 * 503, Reddit's reCAPTCHA page with a plain 200 (measured 2026-09-25). So an
 * HTML answer is also read for the markers of the known challenge systems.
 * The markers are deliberately specific - a page that merely loads a CAPTCHA
 * widget for its login form must not ground its host.
 *
 * <h3>Head and tail</h3>
 * The start and the end of the page are read, not the middle: Reddit's
 * CAPTCHA names itself in its first 200 bytes, but its network block page
 * puts the text after 189 KB of inline CSS, 600 bytes before the end
 * (measured 2026-09-25).
 */
public final class WallDetector {

    /** How much of the start and of the end of a page is read. */
    private static final int SNIFF_BYTES = 64 * 1024;

    private static final List<String> CHALLENGE_MARKERS = List.of(
            "prove your humanity",              // Reddit's reCAPTCHA interstitial
            "blocked by network security",      // Reddit's hard block
            "<title>just a moment...</title>",  // Cloudflare JS challenge
            "challenge-platform",               // Cloudflare challenge script
            "cf-chl-",                          // Cloudflare challenge ids
            "attention required! | cloudflare", // Cloudflare block page
            "px-captcha",                       // PerimeterX / HUMAN
            "captcha-delivery.com",             // DataDome
            "_incapsula_resource");             // Imperva

    private WallDetector() {
    }

    /**
     * @param header looks up a response header by name, any case
     */
    public static Wall classify(int status, Function<String, Optional<String>> header, byte[] body) {
        if (isHtml(header) && containsChallenge(body)) {
            return Wall.CHALLENGE;
        }
        if (status == 429) {
            return Wall.THROTTLED;
        }
        if (status == 503 && header.apply("retry-after").isPresent()) {
            return Wall.THROTTLED;
        }
        if (status == 403) {
            return Wall.FORBIDDEN;
        }
        return Wall.NONE;
    }

    private static boolean isHtml(Function<String, Optional<String>> header) {
        return header.apply("content-type")
                .map(type -> type.toLowerCase(Locale.ROOT).contains("html"))
                .orElse(false);
    }

    private static boolean containsChallenge(byte[] body) {
        int headLength = Math.min(body.length, SNIFF_BYTES);
        int tailStart = Math.max(headLength, body.length - SNIFF_BYTES);
        return containsMarker(body, 0, headLength) || containsMarker(body, tailStart, body.length - tailStart);
    }

    private static boolean containsMarker(byte[] body, int offset, int length) {
        String text = new String(body, offset, length, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        for (String marker : CHALLENGE_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
