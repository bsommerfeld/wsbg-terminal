package de.bsommerfeld.wsbg.terminal.ui.net;

import org.cef.network.CefCookie;
import org.cef.network.CefCookieManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Pre-seeds EU consent cookies into the global CEF cookie store before a hidden
 * anchor tab first loads a matching origin. Without this, a fresh Chromium
 * profile gets the consent interstitial instead of the real page — the
 * {@code news.google.com} anchor never became session-ready (every warmup probe
 * answered the consent shell until the budget expired: "warmup gave up after
 * PT2M"), and every joker query there burned the full READY_WAIT before falling
 * through to the direct leg.
 *
 * <p>Strictly best-effort: a failed cookie write logs WARN and the anchor
 * proceeds exactly as today — behavior can never get worse than the status quo.
 * The cookie store is the persistent one ({@code CefSettings.cache_path}), so a
 * successful seed also survives restarts; seeding is idempotent either way
 * (setCookie overwrites).
 */
final class ConsentCookieSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(ConsentCookieSeeder.class);

    // ------------------------------------------------------------------
    // Consent cookie VALUES — researched 2026-07-14. These ROT: Google
    // rotates its consent encoding every few years. If news.google.com
    // warmups start dying again, refresh them here (one obvious place):
    //
    //  * SOCS — Google's consent-state cookie (base64 protobuf, ~13-month
    //    TTL). This value is the community-documented "reject all" token
    //    (Whoogle consent fix, github.com/tteck/Proxmox discussion #1708;
    //    decodes to choice + gws build "gws_20230810-0_RC2" + "de" + a
    //    timestamp — Google accepts old build stamps). yt-dlp's minimal
    //    "SOCS=CAI" (accept, youtube.com) is the same mechanism.
    //  * CONSENT — the legacy consent cookie; "PENDING+987" is the
    //    companion value the same fix ships ("YES+…" is the pre-2022
    //    accept form Google no longer honors alone).
    //
    // YAHOO note: no seed implemented. Yahoo's EU flow (guce.yahoo.com →
    // consent.yahoo.com) is a server-side GUCS redirect handshake; the
    // resulting A1/A3/A1S/EuConsent cookies are per-session and server-
    // generated — no static, documented pre-seed value exists. The finance
    // API hosts (query1/query2.finance.yahoo.com) answer without consent
    // state, so the joker path there does not need one.
    // ------------------------------------------------------------------
    private static final String GOOGLE_SOCS =
            "CAESHAgBEhJnd3NfMjAyMzA4MTAtMF9SQzIaAmRlIAEaBgiAo_CmBg";
    private static final String GOOGLE_CONSENT = "PENDING+987";

    private record Seed(String name, String value) {}

    /** Registrable site → cookies set on ".site" (visible to every subdomain). */
    private static final Map<String, List<Seed>> BY_SITE = Map.of(
            "google.com", List.of(
                    new Seed("SOCS", GOOGLE_SOCS),
                    new Seed("CONSENT", GOOGLE_CONSENT)));

    /** Sites already seeded this process — one INFO line per site, not per tab. */
    private static final Set<String> seeded = ConcurrentHashMap.newKeySet();

    private ConsentCookieSeeder() {}

    /**
     * Seeds the consent cookies for {@code anchorUrl}'s site, if the table has
     * an entry and it wasn't seeded yet this process. Must run after CEF is
     * initialized (callers register their router handler first, which forces
     * init) and BEFORE the anchor browser loads. setCookie posts to CEF's IO
     * thread; browser creation + navigation take long enough that the cookie
     * lands first in practice.
     */
    static void seedFor(String anchorUrl) {
        String host = hostOf(anchorUrl);
        if (host == null) return;
        for (Map.Entry<String, List<Seed>> e : BY_SITE.entrySet()) {
            String site = e.getKey();
            if (!host.equals(site) && !host.endsWith("." + site)) continue;
            if (!seeded.add(site)) return; // already seeded this process
            try {
                CefCookieManager manager = CefCookieManager.getGlobalManager();
                Date now = new Date();
                Date expires = Date.from(Instant.now().plus(365, ChronoUnit.DAYS));
                boolean ok = true;
                for (Seed s : e.getValue()) {
                    ok &= manager.setCookie("https://www." + site + "/",
                            new CefCookie(s.name(), s.value(), "." + site, "/",
                                    true, false, now, now, true, expires));
                }
                if (ok) {
                    LOG.info("Consent cookies pre-seeded for .{} ({}).", site,
                            e.getValue().stream().map(Seed::name).collect(Collectors.joining(", ")));
                } else {
                    seeded.remove(site); // retry on the next client for this site
                    LOG.warn("Consent cookie pre-seed for .{} rejected by CEF — "
                            + "anchor proceeds without it.", site);
                }
            } catch (Throwable t) {
                seeded.remove(site);
                LOG.warn("Consent cookie pre-seed for .{} failed — anchor proceeds "
                        + "without it: {}", site, t.toString());
            }
            return;
        }
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
