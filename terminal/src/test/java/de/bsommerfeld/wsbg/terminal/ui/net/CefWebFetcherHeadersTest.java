package de.bsommerfeld.wsbg.terminal.ui.net;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which caller headers survive the way into the page-side {@code fetch()}:
 * the browser keeps the names it owns, and a cross-origin anchor must stay
 * inside the CORS safelist or the fetch turns into an unanswerable preflight.
 */
class CefWebFetcherHeadersTest {

    private static Map<String, String> headers(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void sameOriginKeepsAcceptAndTheConditionalValidators() {
        Map<String, String> sent = CefWebFetcher.sanitizeHeaders(
                headers("Accept", "application/rss+xml",
                        "If-None-Match", "\"abc\"",
                        "If-Modified-Since", "Wed, 06 Aug 2026 10:00:00 GMT"),
                false);
        assertEquals(3, sent.size());
        assertEquals("application/rss+xml", sent.get("Accept"));
        assertEquals("\"abc\"", sent.get("If-None-Match"));
    }

    @Test
    void dropsWhatTheBrowserOwnsItself() {
        Map<String, String> sent = CefWebFetcher.sanitizeHeaders(
                headers("User-Agent", "wsbg-terminal/1.0",
                        "Accept-Encoding", "gzip",
                        "Cookie", "session=1",
                        "Referer", "https://example.org/",
                        "Sec-Fetch-Mode", "cors",
                        "Proxy-Authorization", "x",
                        "Accept", "application/json"),
                false);
        assertEquals(Map.of("Accept", "application/json"), sent,
                "Chromium supplies its own session — only the negotiation header survives");
    }

    @Test
    void crossOriginIsReducedToTheCorsSafelist() {
        Map<String, String> sent = CefWebFetcher.sanitizeHeaders(
                headers("Accept", "application/json",
                        "Accept-Language", "de-DE",
                        "If-None-Match", "\"abc\""),
                true);
        assertEquals(Map.of("Accept", "application/json", "Accept-Language", "de-DE"), sent,
                "a validator here would trigger a preflight the API never answers");
    }

    @Test
    void noUsableHeaderYieldsEmptySoTheClassicGetFormIsUsed() {
        assertTrue(CefWebFetcher.sanitizeHeaders(null, false).isEmpty());
        assertTrue(CefWebFetcher.sanitizeHeaders(Map.of(), false).isEmpty());
        assertTrue(CefWebFetcher.sanitizeHeaders(headers("User-Agent", "x"), false).isEmpty(),
                "nothing left after filtering → the pre-pass-through GET form");
    }

    @Test
    void ignoresNullValuesAndMatchesNamesCaseInsensitively() {
        Map<String, String> in = headers("accept", "text/html", "if-none-match", "\"e\"");
        in.put("user-agent", null);
        Map<String, String> sent = CefWebFetcher.sanitizeHeaders(in, false);
        assertEquals(Map.of("accept", "text/html", "if-none-match", "\"e\""), sent);
    }
}
