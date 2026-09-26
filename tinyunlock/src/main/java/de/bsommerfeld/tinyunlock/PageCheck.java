package de.bsommerfeld.tinyunlock;

import java.util.List;
import java.util.Locale;

/**
 * Reads a loaded page for what the engine has to know: is it a CAPTCHA - a
 * check only a person can pass - or anything else, which a browser gets
 * through by itself.
 */
final class PageCheck {

    /**
     * Markers of the CAPTCHA systems in use. A page that merely embeds one in
     * a form (a login) is rare on the pages this opens, and would at worst
     * cost one needless round through the person.
     */
    private static final List<String> CAPTCHA_MARKERS = List.of(
            "prove your humanity",          // Reddit
            "class=\"g-recaptcha\"",        // Google reCAPTCHA widget
            "class=\"h-captcha\"",          // hCaptcha
            "challenges.cloudflare.com",    // Cloudflare Turnstile
            "captcha-delivery.com",         // DataDome
            "px-captcha");                  // PerimeterX / HUMAN

    private PageCheck() {
    }

    static boolean isCaptcha(String html) {
        String lower = html.toLowerCase(Locale.ROOT);
        for (String marker : CAPTCHA_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** One line for the log: title, size, and whether it is a CAPTCHA. */
    static String describe(String html) {
        String lower = html.toLowerCase(Locale.ROOT);
        int start = lower.indexOf("<title>");
        int end = lower.indexOf("</title>");
        String title = start >= 0 && end > start ? html.substring(start + 7, end).trim() : "?";
        return "\"" + title + "\", " + html.length() + " chars" + (isCaptcha(html) ? ", CAPTCHA" : "");
    }
}
