package de.bsommerfeld.wsbg.terminal.ui.net;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The injected script is the joker's only way back. What matters here is that
 * it can never take the page down with it: success and failure share the one
 * {@code q()} channel, so an unguarded call would throw twice over.
 */
class FetchWireProtocolTest {

    @Test
    void theReturnChannelSwallowsItsOwnDeath() throws Exception {
        String script = FetchWireProtocol.buildScript("wsbgtest", "include", 7L,
                "https://example.invalid/data.json");
        assertTrue(script.contains("function q(s){try{window.wsbgFetchQuery("),
                "a missing wsbgFetchQuery must not throw out of q()");
        assertTrue(script.contains("}catch(e){}}"), "and the throw stops right there");
    }

    @Test
    void callerHeadersAreEmittedAsJsonLiterals() throws Exception {
        String script = FetchWireProtocol.buildScript("wsbgtest", "include", 7L,
                "https://example.invalid/feed.xml", "GET",
                Map.of("Accept", "application/rss+xml"), null);
        assertTrue(script.contains("headers:{\"Accept\":\"application/rss+xml\"}"),
                "the caller's negotiation must reach the page-side fetch");
    }

    @Test
    void aQuotedUrlCannotBreakOutOfTheScript() throws Exception {
        String script = FetchWireProtocol.buildScript("wsbgtest", "include", 1L,
                "https://example.invalid/x?q=\");alert(1);//");
        assertEquals(1, script.split("\\Qalert(1)\\E", -1).length - 1,
                "present exactly once — inside the JSON string literal, not as code");
        assertTrue(script.contains("URL=\"https://example.invalid/x?q=\\\");alert(1);//\""));
    }
}
