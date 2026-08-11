package de.bsommerfeld.wsbg.terminal.source.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * The plain-HTTP {@link WebFetcher}: a JDK {@link HttpClient} GET with the
 * caller's headers, completed by the ones a real browser always sends. This is
 * the universal fallback strategy and the historical behaviour — it works
 * against any host that serves a plain client, and is what the chain drops to
 * when the browser strategy is disabled or unavailable.
 *
 * <p><b>The header set is not cosmetic.</b> Callers hand over a user agent and
 * an Accept, and a growing number of hosts refuse exactly that shape: a request
 * that claims to be Chrome and then omits every other header Chrome sends is a
 * bot by its own admission. Measured 2026-08-11 on hosts the house had already
 * written off as walled — with the full set they answer 200 and had done so all
 * along: the St. Louis Fed's CSV export (268 KB) and Les Echos' feed among them.
 * Filling these in HERE lifts every source in the house at once instead of
 * thirty call sites doing it separately.
 *
 * <p>Compression is part of that set and is decoded here ({@link #body}) - the
 * JDK client hands back raw bytes and decompresses nothing. It is not optional
 * either: measured against the same host, the full header set WITHOUT
 * Accept-Encoding is refused and Accept-Encoding without the rest is refused -
 * only both together are answered. Only {@code Referer} stays absent: it
 * belongs to whoever knows where the request came from, which is the caller,
 * not the transport.
 */
public final class DirectWebFetcher implements WebFetcher {

    /**
     * What a browser sends beside the user agent. Applied only where the
     * CALLER named nothing: a client that deliberately sets its own Accept or
     * its own language keeps it.
     */
    private static final Map<String, String> BROWSER_DEFAULTS = Map.of(
            "Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7",
            "Upgrade-Insecure-Requests", "1",
            "Sec-Fetch-Dest", "document",
            "Sec-Fetch-Mode", "navigate",
            "Sec-Fetch-Site", "none",
            "Sec-Fetch-User", "?1",
            "sec-ch-ua-mobile", "?0",
            "sec-ch-ua-platform", "\"macOS\"",
            // Only what this client can actually decode - brotli is not in the
            // JDK, and asking for what one cannot read is how a body turns
            // into noise (see body()).
            "Accept-Encoding", "gzip, deflate");

    private final HttpClient http;
    /**
     * The same client speaking HTTP/1.1. Some walls do not read the request at
     * all - they fingerprint the HTTP/2 handshake and reset the stream, which
     * arrives as {@code RST_STREAM: Internal error} rather than as a status
     * code (measured 2026-08-11 against the St. Louis Fed, which serves the
     * identical URL over HTTP/1.1 without complaint). A protocol nobody can
     * fingerprint is the cheapest second attempt there is.
     */
    private final HttpClient http11;

    public DirectWebFetcher() {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.http11 = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public String name() {
        return "direct";
    }

    @Override
    public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .GET();
        Map<String, String> named = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) named.put(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, String> e : BROWSER_DEFAULTS.entrySet()) {
            named.putIfAbsent(e.getKey(), e.getValue());
        }
        // The client-hints brand list has to agree with the user agent it
        // travels with - a request announcing Chrome 126 in one header and
        // Chrome 131 in the next is a mismatch a wall can read off directly.
        named.putIfAbsent("sec-ch-ua", brandList(named.get("User-Agent")));
        named.putIfAbsent("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        for (Map.Entry<String, String> e : named.entrySet()) {
            // Skip headers the JDK client forbids callers from setting.
            try {
                b.header(e.getKey(), e.getValue());
            } catch (IllegalArgumentException ignored) {
                // restricted header (e.g. Host) — let the client manage it
            }
        }
        HttpRequest request = b.build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.io.IOException h2Refused) {
            // A stream reset is not an answer - it is the connection being cut
            // before one was given, and the second attempt costs one request.
            resp = http11.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
        Map<String, String> out = new HashMap<>();
        resp.headers().map().forEach((k, v) -> {
            if (v != null && !v.isEmpty()) out.put(k, v.get(0));
        });
        return new WebResponse(resp.statusCode(),
                body(resp.body(), resp.headers().firstValue("Content-Encoding").orElse(null),
                        resp.headers().firstValue("Content-Type").orElse(null)),
                out);
    }

    /**
     * The body as text, decompressed where the host compressed it. The JDK
     * client does NOT do this itself - it hands back the raw bytes - so
     * advertising {@code Accept-Encoding} without decoding here would turn
     * every answer into binary noise. Advertising nothing at all is not free
     * either: a client that asks for uncompressed HTML in 2026 is one more
     * thing a wall can notice.
     *
     * <p>The charset comes from the content type where the host states one;
     * feeds that declare their encoding only inside the XML declaration are
     * read as UTF-8, which is what they are in practice.
     */
    private static String body(byte[] raw, String encoding, String contentType) {
        if (raw == null) return null;
        byte[] bytes = raw;
        try {
            if (encoding != null && encoding.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
                try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                        new java.io.ByteArrayInputStream(raw))) {
                    bytes = in.readAllBytes();
                }
            } else if (encoding != null
                    && encoding.toLowerCase(java.util.Locale.ROOT).contains("deflate")) {
                try (java.util.zip.InflaterInputStream in = new java.util.zip.InflaterInputStream(
                        new java.io.ByteArrayInputStream(raw))) {
                    bytes = in.readAllBytes();
                }
            }
        } catch (Exception notCompressedAfterAll) {
            bytes = raw;
        }
        return new String(bytes, charset(contentType));
    }

    private static java.nio.charset.Charset charset(String contentType) {
        if (contentType != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("charset=\\s*\"?([A-Za-z0-9_.:-]+)")
                    .matcher(contentType);
            if (m.find()) {
                try {
                    return java.nio.charset.Charset.forName(m.group(1));
                } catch (Exception unknownCharset) {
                    // fall through to UTF-8
                }
            }
        }
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    /**
     * The client-hints brand list matching a user agent's Chrome version, or a
     * plausible one when the agent names none. Package-private for tests.
     */
    static String brandList(String userAgent) {
        String version = "126";
        if (userAgent != null) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(userAgent);
            if (m.find()) version = m.group(1);
        }
        return "\"Chromium\";v=\"" + version + "\", \"Google Chrome\";v=\"" + version
                + "\", \"Not:A-Brand\";v=\"24\"";
    }
}
