package de.bsommerfeld.tinybrowser;

import de.bsommerfeld.tinyfetch.engine.SocketFrame;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static de.bsommerfeld.tinybrowser.PageFetch.literal;

/**
 * The page side of the WebSockets: the scripts a tab runs for them, and the
 * messages the sockets send home through the router. Encoder here, decoder in
 * {@link ChromiumPage}; both read the layout from this one place.
 *
 * <pre>
 * &lt;tag&gt;O&lt;id&gt;&lt;protocol&gt;      the handshake went through
 * &lt;tag&gt;T&lt;id&gt;&lt;text&gt;          a text message
 * &lt;tag&gt;B&lt;id&gt;&lt;base64&gt;        a binary message
 * &lt;tag&gt;P&lt;id&gt;&lt;part&gt;          the next part of a message past {@link PageFetch#CHUNK};
 *                              the T or B with the rest ends it
 * &lt;tag&gt;X&lt;id&gt;&lt;code&gt;&lt;reason&gt;  closed - the socket's last message
 * </pre>
 * Fields are split by {@link PageFetch#DELIMITER}; the last one runs to the
 * end of the message, so a text may hold the delimiter itself.
 *
 * <h3>Texts cross JNI</h3>
 * and JCEF hands strings across as modified UTF-8, which differs from UTF-8
 * for {@code NUL} and for every character past the BMP - an emoji arrived
 * as six {@code U+FFFD}. So a text and a close reason leave the page with
 * those, and the backslash, as JavaScript's unicode escapes ({@link #unescape}
 * undoes them), and every script ships them as escapes too
 * ({@link PageFetch#literal}). A plain JSON message crosses as it is.
 *
 * <h3>The registry</h3>
 * A document holds its sockets in a registry the first open installs, under
 * a name only this page knows and not enumerable - a site's scripts walking
 * {@code window} do not come across it. A document that goes takes its
 * sockets along; the next open installs the registry in the new one.
 */
final class PageSocket {

    static final char OPENED = 'O';
    static final char TEXT = 'T';
    static final char BINARY = 'B';
    static final char PART = 'P';
    static final char CLOSED = 'X';

    private PageSocket() {
    }

    /** Whether a router message of {@code type} is a socket's. */
    static boolean carries(char type) {
        return type == OPENED || type == TEXT || type == BINARY || type == PART || type == CLOSED;
    }

    /**
     * The script that opens a socket - installing the registry first, when
     * the document has none yet. Every value is a JavaScript string literal.
     */
    static String open(String queryFunction, String tag, SocketFrame.Open open) {
        StringBuilder protocols = new StringBuilder("[");
        for (String protocol : open.protocols()) {
            if (protocols.length() > 1) {
                protocols.append(',');
            }
            protocols.append(literal(protocol));
        }
        protocols.append(']');
        return "(function(){var W=window,N=" + literal(registry(tag)) + ";"
                + "if(!W[N]){" + install(queryFunction, tag) + "}"
                + "W[N].o(" + open.id() + "," + literal(open.url()) + "," + protocols + ");})();";
    }

    /**
     * The script that sends a {@link SocketFrame.Message} on a socket, or
     * {@link SocketFrame.Close}s it. A socket the document does not hold -
     * gone with it, or closed meanwhile - is not there to send on.
     */
    static String send(String tag, SocketFrame frame) {
        String call = switch (frame) {
            case SocketFrame.Message message when message.binary() ->
                    "b(" + message.id() + "," + literal(Base64.getEncoder().encodeToString(message.data())) + ")";
            case SocketFrame.Message message ->
                    "t(" + message.id() + "," + literal(new String(message.data(), StandardCharsets.UTF_8)) + ")";
            case SocketFrame.Close close -> "c(" + close.id() + "," + close.code() + "," + literal(close.reason()) + ")";
            default -> throw new IllegalArgumentException("not for a page: " + frame);
        };
        return "(function(){var R=window[" + literal(registry(tag)) + "];if(R){R." + call + ";}})();";
    }

    /** A text as the page's {@code esc()} sent it, its escapes back to what they stand for. */
    static String unescape(String escaped) {
        if (escaped.indexOf('\\') < 0) {
            return escaped;
        }
        StringBuilder text = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == '\\' && i + 5 < escaped.length() && escaped.charAt(i + 1) == 'u') {
                text.append((char) Integer.parseInt(escaped, i + 2, i + 6, 16));
                i += 5;
            } else if (c == '\\' && i + 1 < escaped.length()) {
                text.append(escaped.charAt(++i));
            } else {
                text.append(c);
            }
        }
        return text.toString();
    }

    private static String registry(String tag) {
        return "_" + tag;
    }

    /**
     * The registry: {@code o} opens, {@code t} and {@code b} send text and
     * binary, {@code c} closes. Binary arrives as an {@code ArrayBuffer} and
     * goes home base64-encoded, as a fetch's body does. A send on a socket
     * that is gone or not open is dropped, as the browser drops it.
     */
    private static String install(String queryFunction, String tag) {
        return "(function(){var TAG=" + literal(tag) + ",D='\\u0001',CH=" + PageFetch.CHUNK + ",S={};"
                + "function q(s){try{W[" + literal(queryFunction) + "]("
                + "{request:s,onSuccess:function(){},onFailure:function(){}});}catch(e){}}"
                + "function out(id,k,d){var i=0;for(;d.length-i>CH;i+=CH){q(TAG+D+'" + PART + "'+D+id+D+d.substr(i,CH));}"
                + "q(TAG+D+k+D+id+D+d.substr(i));}"
                + "function esc(s){return s.replace(/[\\\\\\u0000\\ud800-\\udfff]/g,function(c){"
                + "return c==='\\\\'?'\\\\\\\\':'\\\\u'+('000'+c.charCodeAt(0).toString(16)).slice(-4);});}"
                + "function b64(b){var u=new Uint8Array(b),s='';"
                + "for(var i=0;i<u.length;i+=32768){s+=String.fromCharCode.apply(null,u.subarray(i,i+32768));}"
                + "return btoa(s);}"
                + "Object.defineProperty(W,N,{value:{"
                + "o:function(id,url,p){var w;try{w=new WebSocket(url,p);}"
                + "catch(e){q(TAG+D+'" + CLOSED + "'+D+id+D+1006+D+String(e));return;}"
                + "w.binaryType='arraybuffer';S[id]=w;"
                + "w.onopen=function(){q(TAG+D+'" + OPENED + "'+D+id+D+w.protocol);};"
                + "w.onmessage=function(e){if(typeof e.data==='string'){out(id,'" + TEXT + "',esc(e.data));}"
                + "else{out(id,'" + BINARY + "',b64(e.data));}};"
                + "w.onclose=function(e){delete S[id];q(TAG+D+'" + CLOSED + "'+D+id+D+e.code+D+esc(e.reason));};},"
                + "t:function(id,d){try{S[id].send(d);}catch(e){}},"
                + "b:function(id,d){try{var s=atob(d),u=new Uint8Array(s.length);"
                + "for(var i=0;i<s.length;i++){u[i]=s.charCodeAt(i);}S[id].send(u);}catch(e){}},"
                + "c:function(id,c,r){var w=S[id];if(w){try{w.close(c,r);}catch(e){w.close();}}}"
                + "}});})();";
    }
}
