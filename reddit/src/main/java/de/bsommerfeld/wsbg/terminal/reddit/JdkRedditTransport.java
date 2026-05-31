package de.bsommerfeld.wsbg.terminal.reddit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * Plain JDK {@link HttpClient} transport — the original fetch path.
 *
 * <p>
 * This still works for the public HTML pages but is blocked by Reddit's bot
 * detection on the {@code .json} API endpoint (HTTP 403). It is retained as the
 * default for unit tests and CLI usage; production wiring binds
 * {@link OAuthRedditTransport} instead. See {@link RedditTransport}.
 */
public final class JdkRedditTransport implements RedditTransport {

    private final HttpClient httpClient;

    public JdkRedditTransport() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    @Override
    public RedditResponse get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", RedditUserAgent.VALUE)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        Map<String, String> headers = new HashMap<>();
        response.headers().map().forEach((k, v) -> {
            if (v != null && !v.isEmpty()) headers.put(k, v.get(0));
        });
        return new RedditResponse(response.statusCode(), response.body(), headers);
    }
}
