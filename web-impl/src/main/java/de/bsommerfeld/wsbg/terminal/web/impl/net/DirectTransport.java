package de.bsommerfeld.wsbg.terminal.web.impl.net;

import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.Transport;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * The {@link FetchUtil#DIRECT} transport: a JDK {@link HttpClient} GET with the
 * caller's headers, completed by the ones a real browser always sends. This is
 * the universal transport — it works against any host that serves a plain
 * client, and is what a mode array drops to when the browser is unavailable.
 *
 * <p><b>The header set is not cosmetic.</b> A growing number of hosts refuse a
 * request that claims to be Chrome and then omits every other header Chrome
 * sends — a bot by its own admission. Measured 2026-08-11 on hosts the house
 * had already written off as walled — with the full set they answer 200 and
 * had done so all along (the St. Louis Fed's CSV export and Les Echos' feed
 * among them). Filling these in HERE lifts every source in the house at once.
 * The user agent too: ONE session identity per transport instance, drawn once
 * — sources stop rolling their own.
 *
 * <p>Compression is part of that set and is decoded here — the JDK client
 * hands back raw bytes and decompresses nothing. Only {@code Referer} stays
 * absent: it belongs to whoever knows where the request came from, which is
 * the caller, not the transport.
 */
public final class DirectTransport implements Transport {

    /**
     * What a browser sends beside the user agent. Applied only where the
     * CALLER named nothing: a source that deliberately sets its own Accept or
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
            // into noise (see decode()).
            "Accept-Encoding", "gzip, deflate");

    private final HttpClient http;
    /**
     * The same client speaking HTTP/1.1. Some walls do not read the request at
     * all - they fingerprint the HTTP/2 handshake and reset the stream, which
     * arrives as {@code RST_STREAM: Internal error} rather than as a status
     * code (measured 2026-08-11 against the St. Louis Fed). A protocol nobody
     * can fingerprint is the cheapest second attempt there is.
     */
    private final HttpClient http11;

    /** ONE session identity for every request this transport sends. */
    private final String userAgent = BrowserUserAgent.random();

    public DirectTransport() {
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
    public FetchUtil util() {
        return FetchUtil.DIRECT;
    }

    @Override
    public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) throws Exception {
        HttpResponse<byte[]> resp = send(url, headers, timeout);
        return WebResponse.text(resp.statusCode(),
                decode(resp.body(), resp.headers().firstValue("Content-Encoding").orElse(null),
                        resp.headers().firstValue("Content-Type").orElse(null)),
                firstValues(resp));
    }

    @Override
    public boolean supportsBinary() {
        return true;
    }

    @Override
    public boolean supportsPost() {
        return true;
    }

    @Override
    public WebResponse post(String url, Map<String, String> headers, String body,
            String contentType, Duration timeout) throws Exception {
        Map<String, String> withType = new HashMap<>(headers == null ? Map.of() : headers);
        if (contentType != null && !contentType.isBlank()) {
            withType.putIfAbsent("Content-Type", contentType);
        }
        HttpResponse<byte[]> resp = send(url, withType, timeout,
                HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        return WebResponse.text(resp.statusCode(),
                decode(resp.body(), resp.headers().firstValue("Content-Encoding").orElse(null),
                        resp.headers().firstValue("Content-Type").orElse(null)),
                firstValues(resp));
    }

    @Override
    public WebResponse fetchBinary(String url, Map<String, String> headers, Duration timeout)
            throws Exception {
        HttpResponse<byte[]> resp = send(url, headers, timeout);
        return WebResponse.binary(resp.statusCode(),
                decompress(resp.body(), resp.headers().firstValue("Content-Encoding").orElse(null)),
                firstValues(resp));
    }

    private HttpResponse<byte[]> send(String url, Map<String, String> headers, Duration timeout)
            throws Exception {
        return send(url, headers, timeout, null);
    }

    private HttpResponse<byte[]> send(String url, Map<String, String> headers, Duration timeout,
            HttpRequest.BodyPublisher postBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout);
        if (postBody == null) {
            b.GET();
        } else {
            b.POST(postBody);
        }
        Map<String, String> named = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) named.put(e.getKey(), e.getValue());
            }
        }
        named.putIfAbsent("User-Agent", userAgent);
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
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.io.IOException h2Refused) {
            // A stream reset is not an answer - it is the connection being cut
            // before one was given, and the second attempt costs one request.
            return http11.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    private static Map<String, String> firstValues(HttpResponse<byte[]> resp) {
        Map<String, String> out = new HashMap<>();
        resp.headers().map().forEach((k, v) -> {
            if (v != null && !v.isEmpty()) out.put(k, v.get(0));
        });
        return out;
    }

    /**
     * The body as text, decompressed where the host compressed it. The JDK
     * client does NOT do this itself. The charset comes from the content type
     * where the host states one; feeds that declare their encoding only inside
     * the XML declaration are read as UTF-8, which is what they are in practice.
     */
    private static String decode(byte[] raw, String encoding, String contentType) {
        if (raw == null) return null;
        return new String(decompress(raw, encoding), charset(contentType));
    }

    private static byte[] decompress(byte[] raw, String encoding) {
        if (raw == null) return new byte[0];
        try {
            if (encoding != null && encoding.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
                try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                        new java.io.ByteArrayInputStream(raw))) {
                    return in.readAllBytes();
                }
            }
            if (encoding != null
                    && encoding.toLowerCase(java.util.Locale.ROOT).contains("deflate")) {
                try (java.util.zip.InflaterInputStream in = new java.util.zip.InflaterInputStream(
                        new java.io.ByteArrayInputStream(raw))) {
                    return in.readAllBytes();
                }
            }
        } catch (Exception notCompressedAfterAll) {
            return raw;
        }
        return raw;
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
