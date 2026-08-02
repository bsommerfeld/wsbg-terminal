package de.bsommerfeld.wsbg.terminal.finanzennet;

import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Akamai key for finanzen.net - a COMPLETE, internally consistent browser
 * header set. This is the single reason this module reaches the source at all,
 * so it lives in one place instead of being copy-pasted across the three clients.
 *
 * <h3>The wall (measured live 2026-08-02)</h3>
 * finanzen.net answers {@code 403} with {@code server: AkamaiGHost} to anything
 * that does not look like a real browser handshake. The wall falls WITHOUT a
 * browser, but only for a request that carries every header a Chromium tab
 * sends, over HTTP/2. Isolated counter-probes, each a single missing piece:
 *
 * <ul>
 *   <li>{@code --http1.1} → 403 (the protocol version is part of the print)</li>
 *   <li>no {@code Accept-Encoding} → 403</li>
 *   <li>no {@code Accept-Language} → 403</li>
 *   <li>bare {@code User-Agent} only → 403 (re-verified 2026-08-02)</li>
 *   <li>Googlebot UA → 403</li>
 *   <li>full set → 200, reproducibly (20 requests in a burst: 20 × 200)</li>
 * </ul>
 *
 * Akamai grades header COMPLETENESS as the fingerprint, not any single value.
 *
 * <h3>Why the User-Agent is not simply {@code BrowserUserAgent.random()}</h3>
 * The client-hint headers ({@code sec-ch-ua}, {@code sec-ch-ua-platform}) are
 * only sent by Chromium browsers, and their brand version must match the
 * {@code Chrome/<major>} token of the User-Agent. A Firefox or Safari string
 * from the shared pool paired with {@code sec-ch-ua} is a browser that cannot
 * exist - exactly the inconsistency the wall is looking for. So this class
 * draws from {@link BrowserUserAgent#pool()}, keeps only the CHROMIUM entries,
 * and DERIVES the hints from the drawn string (brand + major version + platform
 * token). If the shared pool should ever lose its Chromium entries, a pinned,
 * self-consistent Chrome set takes over ({@link #PINNED_UA}) rather than
 * shipping a contradictory one - being one fixed browser beats being an
 * impossible one.
 *
 * <p>The randomisation still does its job: it spreads installs across several
 * honest identifiers instead of stamping the whole user base with one string.
 *
 * <h3>Transport</h3>
 * The JDK {@code HttpClient} behind {@code DirectWebFetcher} negotiates HTTP/2
 * and is therefore a valid carrier for this set; the browser transport in the
 * standard chain sends its own (equally complete) headers anyway.
 */
final class FinanzenNetHttp {

    /** Fallback identity, self-consistent by construction (Chrome 131 on macOS). */
    static final String PINNED_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final Pattern CHROME_VERSION = Pattern.compile("Chrome/(\\d+)");
    private static final Pattern EDGE_VERSION = Pattern.compile("Edg/(\\d+)");

    private FinanzenNetHttp() {}

    /**
     * One browser identity: a Chromium User-Agent plus the client hints derived
     * from it. Drawn once per client instance and reused for its lifetime, the
     * way a real session keeps a stable identity.
     */
    record Identity(String userAgent, String secChUa, String platform) {}

    /** Draws a Chromium identity from the shared pool, or the pinned one. */
    static Identity randomIdentity() {
        for (String candidate : shuffledPool()) {
            if (isChromium(candidate)) return identityFor(candidate);
        }
        return identityFor(PINNED_UA);
    }

    /**
     * Derives the client hints from {@code userAgent}. Non-Chromium input is
     * replaced by {@link #PINNED_UA} - never paired with hints it would not send.
     */
    static Identity identityFor(String userAgent) {
        String ua = isChromium(userAgent) ? userAgent : PINNED_UA;
        Matcher edge = EDGE_VERSION.matcher(ua);
        Matcher chrome = CHROME_VERSION.matcher(ua);
        String chromiumVersion = chrome.find() ? chrome.group(1) : "131";
        String brands;
        if (edge.find()) {
            brands = "\"Microsoft Edge\";v=\"" + edge.group(1) + "\", "
                    + "\"Chromium\";v=\"" + chromiumVersion + "\", "
                    + "\"Not_A Brand\";v=\"24\"";
        } else {
            brands = "\"Google Chrome\";v=\"" + chromiumVersion + "\", "
                    + "\"Chromium\";v=\"" + chromiumVersion + "\", "
                    + "\"Not_A Brand\";v=\"24\"";
        }
        return new Identity(ua, brands, platformOf(ua));
    }

    /** True for Chrome/Edge strings - the only ones that may carry client hints. */
    static boolean isChromium(String userAgent) {
        return userAgent != null && userAgent.contains("Chrome/")
                && !userAgent.contains("Firefox/");
    }

    /** The {@code sec-ch-ua-platform} token matching the UA's OS section. */
    static String platformOf(String userAgent) {
        String ua = userAgent == null ? "" : userAgent;
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("Macintosh") || ua.contains("Mac OS X")) return "macOS";
        if (ua.contains("Android")) return "Android";
        return "Linux";
    }

    /**
     * The full header set. {@code Sec-Fetch-Site} is {@code none} for the page
     * navigations and {@code same-origin} for the XHR-shaped suggest call, which
     * is what the real tab sends in each case.
     */
    static Map<String, String> headers(Identity id, boolean xhr) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", id.userAgent());
        h.put("Accept", xhr
                ? "*/*"
                : "text/html,application/xhtml+xml,application/xml;q=0.9,"
                        + "image/avif,image/webp,image/apng,*/*;q=0.8");
        h.put("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7");
        // ⚠️ identity, NOT "gzip, deflate, br". Akamai only checks that the
        // header is PRESENT - all three of identity / gzip / "gzip, deflate"
        // answer 200 (probed 2026-08-02) - but the JDK HttpClient behind
        // DirectWebFetcher does not decompress a response body. Asking for
        // gzip therefore returns 200 with unreadable bytes: the wall opens and
        // every parser then sees garbage, which is the worse failure because it
        // looks like a layout change. Announcing what we can actually read is
        // the honest request.
        h.put("Accept-Encoding", "identity");
        h.put("sec-ch-ua", id.secChUa());
        h.put("sec-ch-ua-mobile", "?0");
        h.put("sec-ch-ua-platform", "\"" + id.platform() + "\"");
        h.put("Sec-Fetch-Dest", xhr ? "empty" : "document");
        h.put("Sec-Fetch-Mode", xhr ? "cors" : "navigate");
        h.put("Sec-Fetch-Site", xhr ? "same-origin" : "none");
        h.put("Sec-Fetch-User", "?1");
        h.put("Upgrade-Insecure-Requests", "1");
        if (xhr) h.put("Referer", "https://www.finanzen.net/");
        return Map.copyOf(h);
    }

    private static java.util.List<String> shuffledPool() {
        java.util.List<String> pool = new java.util.ArrayList<>(BrowserUserAgent.pool());
        java.util.Collections.shuffle(pool);
        return pool;
    }

    /** Lower-cases with the root locale - Turkish-I safe. */
    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
