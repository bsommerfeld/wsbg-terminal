package de.bsommerfeld.tinybrowser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The injected script is the tab's only way home. It must never take the
 * page down with it, and nothing a caller passes may become code.
 */
class PageFetchTest {

    private static String script(String url, Map<String, String> headers, byte[] body) {
        return PageFetch.script("_query", "ttag", "include", 7L, url, "GET", headers, body, 60_000);
    }

    @Test
    void theWayHomeSwallowsItsOwnDeath() {
        String script = script("https://example.invalid/data.json", Map.of(), null);
        assertTrue(script.contains("function q(s){try{window[\"_query\"]("),
                "a missing query function must not throw out of q()");
        assertTrue(script.contains("}catch(e){}}"), "and the throw stops right there");
    }

    @Test
    void callerHeadersBecomeStringLiterals() {
        String script = script("https://example.invalid/feed.xml", Map.of("accept", "application/rss+xml"), null);
        assertTrue(script.contains("headers:{\"accept\":\"application/rss+xml\"}"));
    }

    @Test
    void aQuotedUrlCannotBreakOutOfTheScript() {
        String script = script("https://example.invalid/x?q=\");alert(1);//</script>", Map.of(), null);
        assertEquals(1, script.split("\\Qalert(1)\\E", -1).length - 1, "present once - inside the literal");
        assertTrue(script.contains("\"https://example.invalid/x?q=\\\");alert(1);//\\u003c/script\\u003e\""));
    }

    @Test
    void theBodyTravelsAsBase64() {
        byte[] body = "grant_type=x&device_id=ä".getBytes(StandardCharsets.UTF_8);
        String script = script("https://example.invalid/token", Map.of(), body);
        assertTrue(script.contains("body:Uint8Array.from(atob(\"" + Base64.getEncoder().encodeToString(body) + "\")"));
        assertFalse(script("https://example.invalid/", Map.of(), null).contains("body:"));
    }

    @Test
    void literalsEscapeWhatJavaScriptWouldReadAsCode() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u2028\\u0001\"", PageFetch.literal("a\"b\\c\nd \u0001"));
    }
}
