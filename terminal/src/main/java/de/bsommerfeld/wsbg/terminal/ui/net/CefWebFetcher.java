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
 * none of them ever attached to a window. Request headers are ignored — Chromium
 * supplies its own session; the plain {@code direct} strategy in the chain is
 * where caller headers take effect.
 */
public final class CefWebFetcher implements WebFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(CefWebFetcher.class);

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
        String origin = originOf(url);
        if (origin == null) {
            throw new IllegalArgumentException("Cannot derive origin from URL: " + url);
        }
        // Anchor the per-origin browser there; the first URL seen for the origin
        // doubles as the readiness-verification URL (it returns 200 only once the
        // browser's session is fully established).
        CefFetchClient client = byOrigin.computeIfAbsent(origin, o -> {
            LOG.info("Spinning up hidden browser anchored at {}", o);
            return new CefFetchClient(cefHost, o + "/", url, hostOf(o));
        });
        CefFetchClient.HttpResult r = client.fetch(url, timeout);
        return new WebResponse(r.status(), r.body(), r.headers());
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
