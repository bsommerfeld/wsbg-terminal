package de.bsommerfeld.wsbg.terminal.ui.net;

import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import de.bsommerfeld.wsbg.terminal.ui.CefHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenWeb (Spot.IM) conversation reads for Yahoo Finance's per-ticker comment
 * boards, run as the widget's OWN calls (2026-07-16): a hidden browser tab
 * anchors on finance.yahoo.com, and both legs of the anonymous handshake are
 * page-context POSTs through the {@link CefFetchClient} wire — the Origin
 * header, the CORS preflight and the cookie situation match the real widget
 * exactly, because it IS a page fetch from the same document context.
 *
 * <p><b>The handshake</b> (pinned live 2026-07-16: plain-HTTP POSTs answer
 * 403 from the AWS edge, the widget's page-context calls pass):
 * <ol>
 *   <li>{@code POST https://api-2-0.spot.im/v1.0.0/authenticate} with
 *       {@code x-spot-id} → anonymous bearer token in the
 *       {@code x-access-token} response header (cached ~30 min);</li>
 *   <li>{@code POST https://api-2-0.spot.im/v1.0.0/conversation/read} with
 *       {@code x-spot-id}, {@code x-post-id} and the token, JSON body with
 *       the conversation id ({@code <spotId>_<postId>}), newest first.</li>
 * </ol>
 * A 401/403 on the read invalidates the cached token and retries the
 * handshake ONCE; any further failure returns that status to the chain.
 *
 * <p><b>Contract:</b> callers pass a conversation-read URL carrying
 * {@code spotId} and {@code postId} query parameters (e.g. {@code
 * https://api-2-0.spot.im/v1.0.0/conversation/read?spotId=sp_X&postId=finmb_1});
 * the caller's header map is ignored (this fetcher OWNS the handshake
 * headers). Any other URL answers 400 — this is a purpose-built leg, not a
 * general transport. Transport trouble answers status 0 (never throws), so
 * the calling source degrades to empty.
 */
public final class OpenWebConversationFetcher implements WebFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(OpenWebConversationFetcher.class);

    private static final String ANCHOR_URL = "https://finance.yahoo.com/";
    private static final String AUTH_URL = "https://api-2-0.spot.im/v1.0.0/authenticate";
    private static final String READ_URL = "https://api-2-0.spot.im/v1.0.0/conversation/read";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    private final CefFetchClient client;

    private volatile String token;
    private volatile Instant tokenAt = Instant.EPOCH;

    public OpenWebConversationFetcher(CefHost cefHost) {
        // The anchor doubles as the verify URL: once the Yahoo front page
        // answers 200 through the tab, the page context is established.
        // Credentials 'omit': OpenWeb identity rides the bearer token, and a
        // cross-origin call with credentials would need a stricter CORS grant.
        this.client = new CefFetchClient(cefHost, ANCHOR_URL, ANCHOR_URL,
                "openweb", "omit");
    }

    @Override
    public String name() {
        return "openweb-conversations";
    }

    @Override
    public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
        String spotId;
        String postId;
        try {
            Map<String, String> query = queryParams(url);
            spotId = query.get("spotId");
            postId = query.get("postId");
        } catch (Exception e) {
            spotId = null;
            postId = null;
        }
        if (spotId == null || spotId.isBlank() || postId == null || postId.isBlank()) {
            return new WebResponse(400, "", Map.of());
        }
        try {
            WebResponse first = read(spotId, postId, timeout);
            if (first.status() != 401 && first.status() != 403) return first;
            // Token gone stale (or never valid) — one fresh handshake, one retry.
            token = null;
            return read(spotId, postId, timeout);
        } catch (Exception e) {
            LOG.debug("OpenWeb conversation read failed for {}: {}", postId, e.getMessage());
            return new WebResponse(0, "", Map.of());
        }
    }

    private WebResponse read(String spotId, String postId, Duration timeout) throws Exception {
        String bearer = currentToken(spotId, timeout);
        if (bearer == null) return new WebResponse(0, "", Map.of());
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("x-spot-id", spotId);
        h.put("x-post-id", postId);
        h.put("x-access-token", bearer);
        String body = "{\"conversation_id\":\"" + spotId + "_" + postId
                + "\",\"count\":30,\"offset\":0,\"sort_by\":\"newest\"}";
        CefFetchClient.HttpResult r = client.fetch(READ_URL, "POST", h, body, timeout);
        return new WebResponse(r.status(), r.body(), r.headers());
    }

    /** The cached anonymous token, refreshed through the handshake when stale. */
    private synchronized String currentToken(String spotId, Duration timeout) throws Exception {
        if (token != null && tokenAt.plus(TOKEN_TTL).isAfter(Instant.now())) return token;
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("x-spot-id", spotId);
        CefFetchClient.HttpResult r = client.fetch(AUTH_URL, "POST", h, "{}", timeout);
        // Browser fetch lowercases header names; be tolerant anyway.
        String t = r.headers().get("x-access-token");
        if (t == null) t = r.headers().get("X-Access-Token");
        if (r.status() == 200 && t != null && !t.isBlank()) {
            token = t;
            tokenAt = Instant.now();
            return t;
        }
        LOG.debug("OpenWeb authenticate answered status {} (token {}present)",
                r.status(), t == null ? "ab" : "");
        return null;
    }

    private static Map<String, String> queryParams(String url) {
        Map<String, String> out = new HashMap<>();
        String q = URI.create(url).getRawQuery();
        if (q == null) return out;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(java.net.URLDecoder.decode(pair.substring(0, eq),
                                java.nio.charset.StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(eq + 1),
                                java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
