package de.bsommerfeld.tinybrowser;

import de.bsommerfeld.tinyfetch.engine.SocketFrame;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scripts a socket tab runs - their shape and their escaping. What they
 * do in a real document, the live test shows.
 */
class PageSocketTest {

    @Test
    void theFirstOpenInstallsTheRegistryOutOfSight() {
        String script = PageSocket.open("_query", "ttag", new SocketFrame.Open(5, "wss://push.example.invalid/q",
                List.of("v2", "v1"), null));
        assertTrue(script.contains("N=\"_ttag\";if(!W[N]){"), "installed only where there is none yet");
        assertTrue(script.contains("Object.defineProperty(W,N,{value:{"), "not enumerable - a site walking window misses it");
        assertTrue(script.endsWith("W[N].o(5,\"wss://push.example.invalid/q\",[\"v2\",\"v1\"]);})();"));
    }

    @Test
    void aQuotedUrlCannotBreakOutOfTheScript() {
        String script = PageSocket.open("_query", "ttag", new SocketFrame.Open(1,
                "wss://x.invalid/?q=\");alert(1);//</script>", List.of(), null));
        assertEquals(1, script.split("\\Qalert(1)\\E", -1).length - 1, "present once - inside the literal");
        assertTrue(script.contains("[]);})();"), "no subprotocols, an empty array");
    }

    @Test
    void textBinaryAndCloseBecomeRegistryCalls() {
        String text = PageSocket.send("ttag", new SocketFrame.Message(3, false, "{\"a\":\"ä\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("(function(){var R=window[\"_ttag\"];if(R){R.t(3,\"{\\\"a\\\":\\\"ä\\\"}\");}})();", text);

        byte[] bytes = {0, (byte) 0xff, 7};
        String binary = PageSocket.send("ttag", new SocketFrame.Message(3, true, bytes));
        assertTrue(binary.contains("R.b(3,\"" + Base64.getEncoder().encodeToString(bytes) + "\")"));

        assertTrue(PageSocket.send("ttag", new SocketFrame.Close(3, 4001, "bye")).contains("R.c(3,4001,\"bye\")"));
    }

    @Test
    void whatModifiedUtf8WouldBreakCrossesAsEscapes() {
        String script = PageSocket.open("_query", "ttag", new SocketFrame.Open(1, "wss://x.invalid/", List.of(), null));
        assertTrue(script.contains("s.replace(/[\\\\\\u0000\\ud800-\\udfff]/g,"), "NUL, surrogates and the backslash");
        assertEquals("kurs \\ \u0000 🚀 ä",
                PageSocket.unescape("kurs \\\\ \\u0000 \\ud83d\\ude80 ä"), "back to what they stood for");
        assertEquals("{\"plain\":1}", PageSocket.unescape("{\"plain\":1}"));
        assertTrue(PageSocket.send("ttag", new SocketFrame.Message(2, false,
                "🚀".getBytes(StandardCharsets.UTF_8))).contains("R.t(2,\"\\ud83d\\ude80\")"),
                "and on the way into the page as well");
    }

    @Test
    void socketMessagesDoNotCollideWithFetchMessages() {
        for (char type : new char[] {'M', 'C', 'E'}) {
            assertFalse(PageSocket.carries(type), "a fetch's " + type);
        }
        for (char type : new char[] {PageSocket.OPENED, PageSocket.TEXT, PageSocket.BINARY, PageSocket.PART,
                PageSocket.CLOSED}) {
            assertTrue(PageSocket.carries(type));
        }
    }
}
