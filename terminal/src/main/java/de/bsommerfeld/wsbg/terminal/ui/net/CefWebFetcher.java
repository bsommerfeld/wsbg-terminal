package de.bsommerfeld.wsbg.terminal.ui.net;

import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.ui.CefHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The browser-backed {@link WebFetcher} "joker": fetches through the embedded
 * Chromium runtime so the request goes out as ordinary browser traffic, with
 * Chromium managing the TLS/HTTP session and cookies. This is the path that
 * works against hosts which return 403/429 to a bare headless client (a
 * Cloudflare JS interstitial, Yahoo's IP-based limits) but serve a normal
 * browser session.
 *
 * <p>The same-origin approach ({@link CefFetchClient}) requires the hidden
 * browser to be anchored at the target's own origin, so this keeps <b>one hidden
 * browser per origin</b>, created lazily on first use: a {@code reddit.com} fetch
 * and a {@code query1.finance.yahoo.com} fetch each get their own parked tab,
 * none of them ever attached to a window. Idle tabs are <b>evicted</b> (idle-TTL
 * + LRU cap, see {@link #evictIdle()}) and transparently re-created on the next
 * fetch — a cold re-anchor costs a page load + warmup, which only the rarely-used
 * origins ever pay.
 *
 * <p>Caller headers are passed through to the page-side {@code fetch()}, minus
 * the names a browser owns itself (see {@link #sanitizeHeaders}) — so content
 * negotiation and the conditional cache
 * conditional validators work on this leg too, not just on {@code direct}.
 */
public final class CefWebFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(CefWebFetcher.class);

    /**
     * Hosts whose own root can't anchor a session, mapped to a full anchor URL.
     * Two flavors (probed 2026-07-14):
     * <ul>
     *   <li><b>Cross-origin brand anchor</b> (api.nasdaq.com, CNN dataviz): the
     *       API root never answers / teapots, so the joker parks on the brand's
     *       real site — loading it clears the domain-wide anti-bot cookie — and
     *       fetches the API host cross-origin through that session. Requires the
     *       API to answer CORS (NASDAQ echoes the origin + allows credentials;
     *       CNN answers {@code ACAO:*} → {@link #CREDENTIALS_OMIT}).</li>
     *   <li><b>Same-origin path anchor</b> (nfs.faireconomy.media): the root
     *       answers, but 301s CROSS-HOST (→ forexfactory.com) — Chromium follows
     *       and the document lands on the WRONG origin, CORS-blocking every
     *       fetch. Anchoring at a cheap data path on the host itself keeps the
     *       document — and therefore every fetch — same-origin: no CORS at all.
     *       Same-host redirects (polymarket → /docs, BaFin → login.html,
     *       pegelonline → /gast/start) are harmless and need no entry, as does
     *       any root that answers 2xx/4xx: even a 403/404 body is a real
     *       document AT that origin.</li>
     * </ul>
     */
    private static final Map<String, String> ANCHOR_OVERRIDE = Map.of(
            "api.nasdaq.com", "https://www.nasdaq.com/",
            "production.dataviz.cnn.io", "https://edition.cnn.com/",
            "nfs.faireconomy.media", "https://nfs.faireconomy.media/ff_calendar_thisweek.json");

    /**
     * Overridden hosts whose API answers CORS with {@code Access-Control-Allow-Origin: *},
     * which the browser refuses to combine with credentials — so the cross-origin
     * fetch must omit cookies (CNN's public dataviz endpoint). NASDAQ, by contrast,
     * echoes the exact origin + allows credentials, so it keeps the default "include".
     */
    private static final java.util.Set<String> CREDENTIALS_OMIT =
            java.util.Set.of("production.dataviz.cnn.io");

    /** Hidden tabs unused this long are disposed — the once-a-day briefing hosts
     *  and one-shot article publishers must not pin a Chromium renderer forever. */
    private static final long IDLE_EVICT_MS = 10 * 60_000;
    /** Hard ceiling on parked hidden tabs; beyond it the least-recently-used IDLE
     *  clients go first even before their TTL (hot origins refresh their stamp
     *  every poll and are never the LRU pick). */
    private static final int MAX_TABS = 16;
    /**
     * A tab used this recently is NOT an LRU eviction candidate, even over
     * {@link #MAX_TABS}. Measured on the research collect of 2026-08-09: the collect
     * phase touches 29 distinct origins inside 9 minutes, so the cap evicted
     * tabs the very same phase needed again seconds later - finanzen.net was
     * verified ready at 18:36:50, evicted at 18:39:07, and its next query paid
     * a fresh anchor load plus 41 s of warmup before it was ready again. Over
     * the whole run: 55 browser creations for 29 origins, 47 evictions.
     *
     * <p>The cap is therefore a STEADY-STATE cap, not an instantaneous one: a
     * burst legitimately parks more tabs, and the idle-TTL sweep plus the next
     * quiet minute bring the count back down on their own. {@link #HARD_MAX_TABS}
     * keeps that bend bounded - each parked tab is a Chromium renderer.
     */
    private static final long EVICT_GRACE_MS = 3 * 60_000;
    /** Ceiling the grace may never lift: beyond it the LRU pick ignores recency. */
    private static final int HARD_MAX_TABS = 40;

    private final CefHost cefHost;
    private final Map<String, CefFetchClient> byOrigin = new ConcurrentHashMap<>();

    public CefWebFetcher(CefHost cefHost) {
        this.cefHost = cefHost;
    }

    public String name() {
        return "browser";
    }

    public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) throws Exception {
        if (de.bsommerfeld.wsbg.terminal.ui.OfflineMode.ACTIVE) {
            throw new java.io.IOException("WSBG_OFFLINE: no outbound requests");
        }
        String requestOrigin = originOf(url);
        if (requestOrigin == null) {
            throw new IllegalArgumentException("Cannot derive origin from URL: " + url);
        }
        // Normally anchor at the target's own origin root (same-origin fetch);
        // ANCHOR_OVERRIDE maps the hosts that need a different anchor URL.
        String anchorUrl = ANCHOR_OVERRIDE.getOrDefault(hostOf(requestOrigin), requestOrigin + "/");
        String anchorOrigin = originOf(anchorUrl);
        boolean crossOrigin = !anchorOrigin.equals(requestOrigin);
        String creds = CREDENTIALS_OMIT.contains(hostOf(requestOrigin)) ? "omit" : "include";
        Map<String, String> sent = sanitizeHeaders(headers, crossOrigin);
        CefFetchClient.HttpResult r;
        // Acquire-or-rebuild loop: a client the eviction sweep disposed between
        // lookup and use refuses the fetch — drop the stale mapping and build a
        // fresh one instead of failing the request.
        for (;;) {
            CefFetchClient client = byOrigin.computeIfAbsent(anchorOrigin, o -> {
                LOG.info("Spinning up hidden browser anchored at {}{}", anchorUrl,
                        crossOrigin ? " (for " + requestOrigin + ")" : "");
                // For a same-origin anchor the requested URL doubles as the
                // readiness check; a cross-origin anchor verifies on itself
                // (the API host answers only CORS-gated data, not a page).
                String verify = crossOrigin ? anchorUrl : url;
                return new CefFetchClient(cefHost, anchorUrl, verify, hostOf(anchorOrigin), creds);
            });
            if (client.tryBeginFetch()) {
                try {
                    // No usable caller header → the classic GET form, byte-identical
                    // to the pre-pass-through behaviour (and what the warmup probe uses).
                    r = sent.isEmpty()
                            ? client.fetch(url, timeout)
                            : client.fetch(url, "GET", sent, null, timeout);
                } finally {
                    client.endFetch();
                }
                break;
            }
            byOrigin.remove(anchorOrigin, client);
        }
        evictIdle();
        return WebResponse.text(r.status(), r.body(), r.headers());
    }

    /**
     * Opportunistic hidden-tab eviction, run after every fetch (the wire polls
     * constantly, so this fires at least ~1×/min): a tab idle past
     * {@link #IDLE_EVICT_MS} is disposed, and beyond {@link #MAX_TABS} the
     * least-recently-used idle tabs are disposed early — each parked tab is a
     * whole Chromium renderer process, and before eviction the per-origin map
     * grew forever (one tab per article-publisher domain, joker-first mandate
     * 2026-07-14). A client with a fetch in flight is never touched; the
     * just-used client carries the freshest stamp and is safe from the LRU pick.
     *
     * <p>Since 2026-08-10 the LRU pick also spares tabs used within
     * {@link #EVICT_GRACE_MS} - see that constant for the measurement.
     */
    private synchronized void evictIdle() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, CefFetchClient> e : byOrigin.entrySet()) {
            CefFetchClient c = e.getValue();
            if (c.isIdle() && now - c.lastUsedAt() > IDLE_EVICT_MS
                    && byOrigin.remove(e.getKey(), c)) {
                c.dispose();
            }
        }
        Map<String, Long> idleStamps = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, CefFetchClient> e : byOrigin.entrySet()) {
            if (e.getValue().isIdle()) idleStamps.put(e.getKey(), e.getValue().lastUsedAt());
        }
        for (String origin : lruVictims(idleStamps, byOrigin.size(), now)) {
            CefFetchClient c = byOrigin.get(origin);
            if (c != null && byOrigin.remove(origin, c)) c.dispose();
        }
    }

    /**
     * The LRU pick as a pure decision - which origins go when the map sits over
     * the cap. Oldest first among the IDLE tabs, and a tab used within
     * {@link #EVICT_GRACE_MS} is spared until {@link #HARD_MAX_TABS}. Takes
     * plain data (idle origin → last-used stamp, plus the total parked count)
     * so the policy is unit-testable without a CEF host behind it.
     */
    static List<String> lruVictims(Map<String, Long> idleStamps, int parkedCount, long now) {
        int excess = parkedCount - MAX_TABS;
        if (excess <= 0) return List.of();
        boolean overHardCap = parkedCount > HARD_MAX_TABS;
        List<String> victims = idleStamps.entrySet().stream()
                .filter(e -> overHardCap || now - e.getValue() > EVICT_GRACE_MS)
                .sorted(Map.Entry.comparingByValue())
                .limit(excess)
                .map(Map.Entry::getKey)
                .toList();
        if (!victims.isEmpty()) {
            LOG.debug("Hidden-tab cap: {} parked, evicting {} idle tab(s) past the {} s grace.",
                    parkedCount, victims.size(), EVICT_GRACE_MS / 1000);
        }
        return victims;
    }

    /**
     * Header names a browser owns itself. {@code fetch()} silently drops them
     * (forbidden request headers), and for the ones that matter here we WANT
     * Chromium's own value — the joker exists precisely so the request carries
     * a real browser's User-Agent, cookies and encoding negotiation. Passing
     * the caller's values would at best be ignored, at worst contradict the
     * session we're borrowing.
     */
    private static final java.util.Set<String> BROWSER_OWNED = java.util.Set.of(
            "accept-charset", "accept-encoding", "connection", "content-length",
            "cookie", "cookie2", "date", "dnt", "expect", "host", "keep-alive",
            "origin", "referer", "te", "trailer", "transfer-encoding", "upgrade",
            "user-agent", "via");

    /**
     * CORS-safelisted request headers — the only ones a CROSS-ORIGIN fetch may
     * carry without triggering a preflight. The cross-origin anchors
     * ({@link #ANCHOR_OVERRIDE}) reach APIs that answer plain CORS but no
     * {@code OPTIONS}, so anything beyond this list would turn a working fetch
     * into a preflight failure. Same-origin fetches have no such limit.
     */
    private static final java.util.Set<String> CORS_SAFELISTED = java.util.Set.of(
            "accept", "accept-language", "content-language");

    /**
     * The caller's headers, reduced to what this transport may actually send:
     * {@link #BROWSER_OWNED} names drop out always, and a cross-origin fetch is
     * further reduced to {@link #CORS_SAFELISTED}. What survives is typically
     * the {@code Accept} the source negotiates with and the conditional
     * validators ({@code If-None-Match} / {@code If-Modified-Since}) the
     * caching decorator injects — both of which the browser leg used to throw
     * away, sending a hardcoded {@code Accept: application/json} instead.
     */
    static Map<String, String> sanitizeHeaders(Map<String, String> headers, boolean crossOrigin) {
        if (headers == null || headers.isEmpty()) return Map.of();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey();
            if (k == null || e.getValue() == null) continue;
            String lower = k.toLowerCase(java.util.Locale.ROOT);
            if (BROWSER_OWNED.contains(lower) || lower.startsWith("proxy-") || lower.startsWith("sec-")) {
                continue;
            }
            if (crossOrigin && !CORS_SAFELISTED.contains(lower)) continue;
            out.put(k, e.getValue());
        }
        return out;
    }

    /** scheme://host[:port] — the same-origin anchor for a URL. */
    private static String originOf(String url) {
        try {
            URI u = URI.create(url);
            if (u.getScheme() == null || u.getHost() == null) return null;
            String origin = u.getScheme() + "://" + u.getHost();
            if (u.getPort() != -1) origin += ":" + u.getPort();
            return origin;
        } catch (Exception e) {
            return null;
        }
    }

    private static String hostOf(String origin) {
        int i = origin.indexOf("://");
        return i >= 0 ? origin.substring(i + 3) : origin;
    }
}
