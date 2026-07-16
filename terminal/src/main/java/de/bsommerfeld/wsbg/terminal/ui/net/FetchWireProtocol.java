package de.bsommerfeld.wsbg.terminal.ui.net;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.SecureRandom;

/**
 * The page↔Java return-channel protocol for {@link CefFetchClient}, defined in
 * one place: the field delimiter, the body chunk size, the per-client tag, and
 * the injected {@code fetch()} script (the encoder). The decoder
 * ({@code CefFetchClient.handleMessage}) reads the same {@link #DELIM}-delimited
 * layout:
 * <pre>
 *   &lt;tag&gt;M&lt;total&gt;&lt;json&gt;      (meta, once)
 *   &lt;tag&gt;C&lt;id&gt;&lt;seq&gt;&lt;data&gt;  (one per chunk)
 * </pre>
 */
final class FetchWireProtocol {

    /** Field delimiter inside a router message: a control char that can't occur in a URL or header. */
    static final char DELIM = '\u0001';

    /** Body chunk size (UTF-16 code units) per router message. */
    static final int CHUNK = 262144;

    private static final ObjectMapper JSON = new ObjectMapper();

    private FetchWireProtocol() {}

    /** A short, per-client tag so multiple clients sharing the one router ignore each other's traffic. */
    static String randomTag() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 8; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return "wsbg" + sb;
    }

    /**
     * Page-side abort for a server that never answers: without it the promise
     * (and eventually the whole body) stays alive in the hidden page until the
     * next anchor reload — the Java side times out cleanly, the page did not.
     * Comfortably above every caller timeout, so it never races a healthy fetch.
     */
    static final int PAGE_ABORT_MS = 60_000;

    /**
     * Builds the injected fetch script (the classic GET form). Delegates to
     * the full form with GET, the Accept-only header set and no body — the
     * emitted script is semantically identical to the pre-2026-07-16 one.
     */
    static String buildScript(String clientTag, String credentials, long id, String url) throws Exception {
        return buildScript(clientTag, credentials, id, url, "GET",
                java.util.Map.of("Accept", "application/json"), null);
    }

    /**
     * Builds the injected fetch script, full form (2026-07-16, the OpenWeb
     * leg): arbitrary method, request headers and an optional string body —
     * what a page-side XHR/fetch can express. The URL, tag, method, headers
     * and body are all emitted as JSON literals (valid JS literals) so no
     * value can break out of the script. The browser slices the response so
     * a multi-MB body survives the router's per-message ceiling.
     */
    static String buildScript(String clientTag, String credentials, long id, String url,
            String method, java.util.Map<String, String> headers, String body) throws Exception {
        String jsTag = JSON.writeValueAsString(clientTag);
        String jsUrl = JSON.writeValueAsString(url);
        String jsMethod = JSON.writeValueAsString(method == null ? "GET" : method);
        String jsHeaders = JSON.writeValueAsString(
                headers == null ? java.util.Map.of() : headers);
        String bodyField = body == null ? "" : ",body:" + JSON.writeValueAsString(body);
        return "(function(){var TAG=" + jsTag + ",ID=" + id + ",URL=" + jsUrl + ",D='\\u0001';"
                + "function q(s){window.wsbgFetchQuery({request:s,onSuccess:function(){},onFailure:function(){}});}"
                + "var AC=new AbortController();setTimeout(function(){AC.abort();}," + PAGE_ABORT_MS + ");"
                + "fetch(URL,{method:" + jsMethod + ",credentials:'" + credentials + "',signal:AC.signal,"
                + "headers:" + jsHeaders + bodyField + "}).then(function(r){"
                + "var h={};r.headers.forEach(function(v,k){h[k]=v;});"
                + "return r.text().then(function(t){"
                + "var CH=" + CHUNK + ",total=Math.max(1,Math.ceil(t.length/CH));"
                + "q(TAG+D+'M'+D+total+D+JSON.stringify({id:ID,status:r.status,len:t.length,headers:h}));"
                + "for(var i=0;i<total;i++){q(TAG+D+'C'+D+ID+D+i+D+t.substr(i*CH,CH));}"
                + "});}).catch(function(e){"
                + "q(TAG+D+'M'+D+'0'+D+JSON.stringify({id:ID,status:0,len:0,error:String(e),headers:{}}));"
                + "});})();";
    }
}
