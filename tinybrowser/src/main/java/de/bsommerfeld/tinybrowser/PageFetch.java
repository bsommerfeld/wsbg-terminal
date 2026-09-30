package de.bsommerfeld.tinybrowser;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The page side of a fetch: the script a tab runs, and the messages it sends
 * home through the router. Encoder here, decoder in {@link ChromiumPage}; both
 * read the layout from this one place.
 *
 * <pre>
 * &lt;tag&gt;M&lt;id&gt;&lt;total&gt;&lt;status&gt;&lt;url&gt;&lt;headers&gt;  meta, once
 * &lt;tag&gt;C&lt;id&gt;&lt;seq&gt;&lt;data&gt;                     one per body chunk
 * &lt;tag&gt;E&lt;id&gt;&lt;message&gt;                        no answer: fetch() failed
 * </pre>
 * Fields are split by {@link #DELIMITER}, header names and values by
 * {@link #HEADER_DELIMITER} - control characters no URL or header can hold.
 * The body travels base64-encoded, so it arrives byte for byte, and in chunks:
 * a listing can be several MB, past what one router message carries.
 */
final class PageFetch {

    static final char DELIMITER = '\u0001';
    static final char HEADER_DELIMITER = '\u0002';

    /** Base64 characters per router message. */
    static final int CHUNK = 262_144;

    private PageFetch() {
    }

    /** A short tag per tab, so the router can tell whose message it carries. */
    static String randomTag() {
        SecureRandom random = new SecureRandom();
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder tag = new StringBuilder("t");
        for (int i = 0; i < 8; i++) {
            tag.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return tag.toString();
    }

    /**
     * The script for one fetch. Every value is emitted as a JavaScript string
     * literal, so no URL, header or body can break out of it.
     *
     * @param queryFunction the router's page-side function
     * @param credentials   {@code include} - the site's cookies go along
     * @param body          sent as bytes; {@code null} for none
     * @param abortMillis   when the page gives up on a server that never answers -
     *                      without it the promise, and eventually the whole body,
     *                      stays alive in the page until the tab is re-anchored
     */
    static String script(String queryFunction, String tag, String credentials, long id, String url, String method,
            Map<String, String> headers, byte[] body, long abortMillis) {
        StringBuilder headerLiteral = new StringBuilder("{");
        headers.forEach((name, value) -> {
            if (headerLiteral.length() > 1) {
                headerLiteral.append(',');
            }
            headerLiteral.append(literal(name)).append(':').append(literal(value));
        });
        headerLiteral.append('}');
        String bodyField = body == null ? ""
                : ",body:Uint8Array.from(atob(" + literal(Base64.getEncoder().encodeToString(body))
                        + "),function(c){return c.charCodeAt(0);})";
        /*
         * q() is the one way home - success and failure share it. When the
         * channel is gone (the document navigated, or Chromium swapped in an
         * error page), an unguarded call would throw inside then(), catch()
         * would call q() again and throw again, and the page would end in an
         * unhandled rejection while the engine waits out its timeout. Swallowed
         * here, the silence reaches Tab, which treats it as a health signal.
        */
        return "(function(){var TAG=" + literal(tag) + ",ID=" + id + ",D='\\u0001',H='\\u0002';"
                + "function q(s){try{window[" + literal(queryFunction) + "]("
                + "{request:s,onSuccess:function(){},onFailure:function(){}});}catch(e){}}"
                + "var AC=new AbortController();setTimeout(function(){AC.abort();}," + abortMillis + ");"
                + "fetch(" + literal(url) + ",{method:" + literal(method) + ",credentials:" + literal(credentials)
                + ",signal:AC.signal,headers:" + headerLiteral + bodyField + "}).then(function(r){"
                + "var h=[];r.headers.forEach(function(v,k){h.push(k+H+v);});"
                + "return r.arrayBuffer().then(function(b){"
                + "var u=new Uint8Array(b),s='';"
                + "for(var i=0;i<u.length;i+=32768){s+=String.fromCharCode.apply(null,u.subarray(i,i+32768));}"
                + "var t=btoa(s),CH=" + CHUNK + ",total=Math.max(1,Math.ceil(t.length/CH));"
                + "q(TAG+D+'M'+D+ID+D+total+D+r.status+D+r.url+D+h.join(H));"
                + "for(var j=0;j<total;j++){q(TAG+D+'C'+D+ID+D+j+D+t.substr(j*CH,CH));}"
                + "});}).catch(function(e){q(TAG+D+'E'+D+ID+D+String(e));});})();";
    }

    /** The header field of a meta message, as pairs. */
    static List<Map.Entry<String, String>> headers(String joined) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        if (joined.isEmpty()) {
            return headers;
        }
        String[] parts = joined.split(String.valueOf(HEADER_DELIMITER), -1);
        for (int i = 0; i + 1 < parts.length; i += 2) {
            headers.add(Map.entry(parts[i], parts[i + 1]));
        }
        return headers;
    }

    /** {@code value} as a double-quoted JavaScript string literal. */
    static String literal(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case ' ' -> out.append("\\u2028");
                case ' ' -> out.append("\\u2029");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || Character.isSurrogate(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
