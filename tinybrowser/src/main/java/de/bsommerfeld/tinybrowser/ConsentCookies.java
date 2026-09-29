package de.bsommerfeld.tinybrowser;

import org.cef.network.CefCookie;
import org.cef.network.CefCookieManager;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plants EU consent cookies before a tab first loads a matching site. Without
 * them a fresh profile gets the consent interstitial instead of the page -
 * on master, {@code news.google.com} never became ready: every warmup probe
 * answered the consent shell until the budget ran out.
 *
 * <p>Best effort: a cookie the store refuses is logged and the tab goes ahead
 * without it. The store is the profile's, so a planted cookie also survives
 * restarts; planting again is harmless (it overwrites).
 */
final class ConsentCookies {

    // ------------------------------------------------------------------
    // The VALUES rot: Google rotates its consent encoding every few years.
    // If news.google.com tabs stop getting ready again, refresh them here.
    //
    //  * SOCS - Google's consent state (base64 protobuf, ~13 months). This
    //    is the community-documented "reject all" token (the Whoogle
    //    consent fix); Google accepts its old build stamp.
    //  * CONSENT - the legacy cookie; "PENDING+987" is the companion value
    //    the same fix ships.
    //
    // Yahoo has none: its EU consent is a server-side redirect handshake
    // with per-session cookies, and its finance API hosts answer without.
    // ------------------------------------------------------------------
    private static final String GOOGLE_SOCS = "CAESHAgBEhJnd3NfMjAyMzA4MTAtMF9SQzIaAmRlIAEaBgiAo_CmBg";
    private static final String GOOGLE_CONSENT = "PENDING+987";

    private record Seed(String name, String value) {
    }

    /** Registrable site → cookies set on {@code .site}, visible to every subdomain. */
    private static final Map<String, List<Seed>> BY_SITE = Map.of(
            "google.com", List.of(new Seed("SOCS", GOOGLE_SOCS), new Seed("CONSENT", GOOGLE_CONSENT)));

    /** Sites planted this run - once per site, not per tab. */
    private static final Set<String> planted = ConcurrentHashMap.newKeySet();

    private ConsentCookies() {
    }

    /** Plants the cookies for {@code anchorUrl}'s site, if it has any and they are not planted yet. */
    static void seedFor(String anchorUrl) {
        String host;
        try {
            host = URI.create(anchorUrl).getHost();
        } catch (IllegalArgumentException e) {
            return;
        }
        if (host == null) {
            return;
        }
        for (Map.Entry<String, List<Seed>> entry : BY_SITE.entrySet()) {
            String site = entry.getKey();
            if (!host.equals(site) && !host.endsWith("." + site)) {
                continue;
            }
            if (!planted.add(site)) {
                return;
            }
            try {
                CefCookieManager manager = CefCookieManager.getGlobalManager();
                Date now = new Date();
                Date expires = Date.from(Instant.now().plus(365, ChronoUnit.DAYS));
                boolean accepted = true;
                for (Seed seed : entry.getValue()) {
                    accepted &= manager.setCookie("https://www." + site + "/",
                            new CefCookie(seed.name(), seed.value(), "." + site, "/",
                                    true, false, now, now, true, expires));
                }
                if (accepted) {
                    Log.info("consent cookies planted for ." + site);
                } else {
                    planted.remove(site);
                    Log.warn("consent cookies for ." + site + " refused - the tab goes ahead without");
                }
            } catch (Throwable failure) {
                planted.remove(site);
                Log.warn("consent cookies for ." + site + " failed - the tab goes ahead without: " + failure);
            }
            return;
        }
    }
}
