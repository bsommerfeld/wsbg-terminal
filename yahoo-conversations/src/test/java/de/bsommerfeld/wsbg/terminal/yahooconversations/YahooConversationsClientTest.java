package de.bsommerfeld.wsbg.terminal.yahooconversations;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Only the board-id extraction is unit-tested — its fixture is a REAL quote
 * page excerpt (live 2026-07-16, escaped-JSON bootstrap form). The
 * conversation leg deliberately ships WITHOUT tests (user GO 2026-07-16):
 * its JSON shape cannot be pinned from outside the widget handshake, so it
 * gets re-pinned against the first live answer instead of asserted against
 * a guessed fixture.
 */
class YahooConversationsClientTest {

    private static String fixture() {
        try (InputStream in = YahooConversationsClientTest.class
                .getResourceAsStream("/yahoo-quote-snippet.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing", e);
        }
    }

    @Test
    void boardIdExtractsFromTheEscapedBootstrapJson() {
        assertEquals("finmb_24937", YahooConversationsClient.extractBoardId(fixture()),
                "the live page carries the id as ESCAPED JSON in a script block");
        assertEquals("finmb_1", YahooConversationsClient.extractBoardId(
                "{\"messageBoardId\":\"finmb_1\"}"), "the plain form matches too");
        assertNull(YahooConversationsClient.extractBoardId("<html>no board here</html>"));
        assertNull(YahooConversationsClient.extractBoardId(null));
    }

    @Test
    void conversationParserToleratesGarbage() {
        assertNull(YahooConversationsClient.parse("<html>wall</html>", "AAPL"));
        assertNull(YahooConversationsClient.parse("{\"unrelated\":true}", "AAPL"));
        assertNull(YahooConversationsClient.parse("", "AAPL"));
        assertNull(YahooConversationsClient.parse(null, "AAPL"));
        assertEquals(0, YahooConversationsClient.parse(
                "{\"conversation\":{\"comments\":[],\"users\":{}}}", "AAPL").size(),
                "an empty comment list is a VALID empty, not a miss");
    }
}
