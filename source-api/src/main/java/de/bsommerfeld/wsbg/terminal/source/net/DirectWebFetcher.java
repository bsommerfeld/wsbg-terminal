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
 * caller's headers applied verbatim. This is the universal fallback strategy and
 * the historical behaviour — it works wherever bot detection isn't in play, and
 * is what the chain drops to when the browser strategy is disabled or unavailable.
 */
public final class DirectWebFetcher implements WebFetcher {

    private final HttpClient http;

    public DirectWebFetcher() {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
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
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    // Skip headers the JDK client forbids callers from setting.
                    try {
                        b.header(e.getKey(), e.getValue());
                    } catch (IllegalArgumentException ignored) {
                        // restricted header (e.g. Host) — let the client manage it
                    }
                }
            }
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, String> out = new HashMap<>();
        resp.headers().map().forEach((k, v) -> {
            if (v != null && !v.isEmpty()) out.put(k, v.get(0));
        });
        return new WebResponse(resp.statusCode(), resp.body(), out);
    }
}
