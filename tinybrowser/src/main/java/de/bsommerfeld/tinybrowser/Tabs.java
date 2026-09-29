package de.bsommerfeld.tinybrowser;

import de.bsommerfeld.tinyfetch.engine.EngineAnswer;
import de.bsommerfeld.tinyfetch.engine.EngineRequest;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The hidden tabs, one per site - the terminal's {@code CefWebFetcher} from
 * master. A request goes to the tab parked on its own origin (or on the
 * anchor TinyFetch names for its host), opened on first use; idle tabs are
 * closed again, and reopened when needed - a cold tab costs a page load and a
 * warmup, which only rarely asked sites ever pay.
 */
final class Tabs {

    /** Tabs unused this long are closed - once-a-day sites must not pin a renderer forever. */
    private static final long IDLE_EVICT_MS = 10 * 60_000;
    /** Tabs kept at most in steady state; beyond it the least recently used idle ones go early. */
    private static final int MAX_TABS = 16;
    /**
     * A tab used this recently is spared by the cap. Measured on master
     * (2026-08-09): a research run touched 29 sites in 9 minutes, and a pure
     * LRU cap closed tabs the same run needed again seconds later - 55 tab
     * openings for 29 sites. So the cap bends under a burst, and the idle
     * sweep brings the count back down in the next quiet minutes.
     */
    private static final long EVICT_GRACE_MS = 3 * 60_000;
    /** Past this, the grace no longer protects - every tab is a renderer process. */
    private static final int HARD_MAX_TABS = 40;

    /**
     * Header names a browser owns itself. {@code fetch()} drops most of them
     * silently, and for the ones that matter here the browser's own value is
     * the point - its cookies, its encoding. {@code user-agent} goes its own
     * way ({@link ResourcePolicy#USER_AGENT_MARKER}).
     */
    private static final Set<String> BROWSER_OWNED = Set.of(
            "accept-charset", "accept-encoding", "connection", "content-length",
            "cookie", "cookie2", "date", "dnt", "expect", "host", "keep-alive",
            "origin", "referer", "te", "trailer", "transfer-encoding", "upgrade", "via");

    /**
     * The headers a cross-origin fetch may carry without a preflight. Tabs
     * parked on another site reach APIs that answer plain CORS but no
     * {@code OPTIONS}; anything beyond these would turn a working fetch into
     * a failed preflight.
     */
    private static final Set<String> CORS_SAFELISTED = Set.of("accept", "accept-language", "content-language");

    private final Chromium chromium;
    private final Map<String, Tab> byAnchorOrigin = new ConcurrentHashMap<>();

    Tabs(Chromium chromium) {
        this.chromium = chromium;
    }

    EngineAnswer fetch(EngineRequest request) throws Exception {
        String requestOrigin = originOf(request.url());
        if (requestOrigin == null) {
            return EngineAnswer.failed(request.id(), "no origin in " + request.url());
        }
        String anchorUrl = request.anchor() != null ? request.anchor() : requestOrigin + "/";
        String anchorOrigin = originOf(anchorUrl);
        if (anchorOrigin == null) {
            return EngineAnswer.failed(request.id(), "no origin in anchor " + anchorUrl);
        }
        boolean crossOrigin = !anchorOrigin.equals(requestOrigin);
        Map<String, String> sent = sanitizeHeaders(request.headers(), crossOrigin);

        Tab.Result result;
        /*
         * A tab the eviction closed between lookup and use refuses the fetch:
         * drop it and open a new one instead of failing the request.
        */
        while (true) {
            Tab tab = byAnchorOrigin.computeIfAbsent(anchorOrigin, origin -> {
                // A same-origin GET is its own best probe: when it goes
                // through, the site let the tab in. Anything else probes the
                // anchor page.
                boolean probeWithRequest = !crossOrigin && request.method().equals("GET");
                return new Tab(chromium, anchorUrl, probeWithRequest ? request.url() : anchorUrl,
                        probeWithRequest ? sent : Map.of(), hostOf(anchorOrigin), "include");
            });
            if (tab.tryBeginFetch()) {
                try {
                    result = tab.fetch(request.url(), request.method(), sent, request.body(),
                            Duration.ofMillis(request.timeoutMillis()));
                } finally {
                    tab.endFetch();
                }
                break;
            }
            byAnchorOrigin.remove(anchorOrigin, tab);
        }
        evictIdle();
        return result.failure() != null
                ? EngineAnswer.failed(request.id(), result.failure())
                : new EngineAnswer(request.id(), result.status(), result.url(), result.headers(), result.body(), null);
    }

    /**
     * Run after every fetch: a tab idle past {@link #IDLE_EVICT_MS} is closed,
     * and beyond {@link #MAX_TABS} the least recently used idle tabs go early.
     * A tab with a fetch in flight is never touched.
     */
    private synchronized void evictIdle() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Tab> entry : byAnchorOrigin.entrySet()) {
            Tab tab = entry.getValue();
            if (tab.isIdle() && now - tab.lastUsedAt() > IDLE_EVICT_MS && byAnchorOrigin.remove(entry.getKey(), tab)) {
                tab.dispose();
            }
        }
        Map<String, Long> idleStamps = new LinkedHashMap<>();
        byAnchorOrigin.forEach((origin, tab) -> {
            if (tab.isIdle()) {
                idleStamps.put(origin, tab.lastUsedAt());
            }
        });
        for (String origin : lruVictims(idleStamps, byAnchorOrigin.size(), now)) {
            Tab tab = byAnchorOrigin.get(origin);
            if (tab != null && byAnchorOrigin.remove(origin, tab)) {
                tab.dispose();
            }
        }
    }

    /**
     * Which tabs go when more than {@link #MAX_TABS} are open: the idle ones,
     * oldest first, sparing those used within {@link #EVICT_GRACE_MS} until
     * {@link #HARD_MAX_TABS}. Plain data in, so the policy is testable
     * without a browser.
     *
     * @param idleStamps anchor origin of every idle tab → when it was last used
     * @param openCount  every open tab, idle or not
     */
    static List<String> lruVictims(Map<String, Long> idleStamps, int openCount, long now) {
        int excess = openCount - MAX_TABS;
        if (excess <= 0) {
            return List.of();
        }
        boolean overHardCap = openCount > HARD_MAX_TABS;
        return idleStamps.entrySet().stream()
                .filter(entry -> overHardCap || now - entry.getValue() > EVICT_GRACE_MS)
                .sorted(Map.Entry.comparingByValue())
                .limit(excess)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * The caller's headers, reduced to what the page may send: the names a
     * browser owns drop out, a cross-origin fetch keeps only the CORS
     * safelist, and a same-origin {@code user-agent} travels under
     * {@link ResourcePolicy#USER_AGENT_MARKER}.
     */
    static Map<String, String> sanitizeHeaders(List<Map.Entry<String, String>> headers, boolean crossOrigin) {
        Map<String, String> sent = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers) {
            String name = header.getKey();
            if (name == null || header.getValue() == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (BROWSER_OWNED.contains(lower) || lower.startsWith("proxy-") || lower.startsWith("sec-")) {
                continue;
            }
            if (lower.equals("user-agent")) {
                if (!crossOrigin) {
                    sent.put(ResourcePolicy.USER_AGENT_MARKER, header.getValue());
                }
                continue;
            }
            if (crossOrigin && !CORS_SAFELISTED.contains(lower)) {
                continue;
            }
            sent.put(name, header.getValue());
        }
        return sent;
    }

    /** {@code scheme://host[:port]}, or {@code null} for an address without one. */
    static String originOf(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            String origin = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT);
            return uri.getPort() == -1 ? origin : origin + ":" + uri.getPort();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String hostOf(String origin) {
        int start = origin.indexOf("://");
        return start >= 0 ? origin.substring(start + 3) : origin;
    }
}
