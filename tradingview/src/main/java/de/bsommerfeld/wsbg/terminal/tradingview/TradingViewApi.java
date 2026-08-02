package de.bsommerfeld.wsbg.terminal.tradingview;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The house rules for talking to TradingView's non-{@code www} API hosts
 * (symbol-search, scanner, news-mediator, news-headlines), pinned live
 * 2026-08-02.
 *
 * <p><b>The single gotcha of this whole module:</b> those hosts sit behind an
 * nginx that answers <b>403</b> to any request without an
 * {@code Origin: https://www.tradingview.com} AND a matching {@code Referer}
 * (verified: the identical symbol-search URL answers 200 with the pair and 403
 * without). No cookie, no key, no token is needed - only these two headers. So
 * every request in this module goes out through {@link #headers(String)};
 * bypassing it is how a caller silently earns a 403.
 *
 * <p>The older {@code www.tradingview.com/api/v1/minds/} surface used by
 * {@link TradingViewMindsClient} does NOT need them (it is same-host), which is
 * why that client predates this helper and stays as it is.
 */
final class TradingViewApi {

    /** The site the API hosts accept as caller - anything else is a 403. */
    static final String ORIGIN = "https://www.tradingview.com";

    /** Article/permalink base: {@code storyPath} values are relative to it. */
    static final String SITE = "https://www.tradingview.com";

    private TradingViewApi() {}

    /** The mandatory Origin/Referer pair plus the usual JSON headers. */
    static Map<String, String> headers(String userAgent) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", userAgent);
        h.put("Accept", "application/json");
        h.put("Origin", ORIGIN);
        h.put("Referer", ORIGIN + "/");
        return Map.copyOf(h);
    }

    /** A {@code storyPath} ({@code /news/<id>-<slug>/}) → absolute permalink. */
    static String permalink(String storyPath) {
        if (storyPath == null || storyPath.isBlank()) return null;
        String p = storyPath.trim();
        return p.startsWith("http") ? p : SITE + (p.startsWith("/") ? p : "/" + p);
    }
}
