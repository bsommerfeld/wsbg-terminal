package de.bsommerfeld.wsbg.terminal.ui.net;

import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import de.bsommerfeld.wsbg.terminal.ui.CefHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
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
 * origins ever pay. Request headers are ignored — Chromium supplies its own
 * session; the plain {@code direct} strategy in the chain is where caller
 * headers take effect.
 */
public final class CefWebFetcher implements WebFetcher {

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

    private final CefHost cefHost;
    private final Map<String, CefFetchClient> byOrigin = new ConcurrentHashMap<>();

    public CefWebFetcher(CefHost cefHost) {
        this.cefHost = cefHost;
    }

    @Override
    public String name() {
        return "browser";
    }

    @Override
    public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) throws Exception {
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
                    r = client.fetch(url, timeout);
                } finally {
                    client.endFetch();
                }
                break;
            }
            byOrigin.remove(anchorOrigin, client);
        }
        evictIdle();
        return new WebResponse(r.status(), r.body(), r.headers());
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
        int excess = byOrigin.size() - MAX_TABS;
        if (excess <= 0) return;
        byOrigin.entrySet().stream()
                .filter(e -> e.getValue().isIdle())
                .sorted(java.util.Comparator.comparingLong(e -> e.getValue().lastUsedAt()))
                .limit(excess)
                .forEach(e -> {
                    if (byOrigin.remove(e.getKey(), e.getValue())) e.getValue().dispose();
                });
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
