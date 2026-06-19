package de.bsommerfeld.wsbg.terminal.reddit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Application-only ("userless") OAuth transport against {@code oauth.reddit.com}.
 *
 * <p>
 * This is the production fetch path. The public {@code www.reddit.com/.json}
 * endpoints often reject headless clients with a 403, but the official OAuth API
 * host is the supported programmatic interface — a plain {@link HttpClient}
 * carrying a bearer token is served normally, with no browser involved.
 *
 * <h3>No user login</h3>
 * The token is obtained via the {@code installed_client} grant, which is
 * application-only: it authenticates the <em>app</em> (by its client ID), not a
 * Reddit user. End users never sign in and need no Reddit account. The app is
 * registered once by the developer at {@code reddit.com/prefs/apps} as an
 * "installed app" (no client secret); its client ID is supplied via
 * {@code reddit.oauth-client-id}.
 *
 * <h3>Token lifecycle</h3>
 * Tokens are fetched on demand and cached until shortly before they expire,
 * then transparently refreshed. A {@code 401} on a data request forces a single
 * refresh-and-retry in case a token was revoked early.
 *
 * <h3>URL rewriting</h3>
 * {@link RedditScraper} builds {@code https://www.reddit.com/...} URLs. This
 * transport rewrites the host to {@code https://oauth.reddit.com/...}; the paths
 * (listings, {@code /by_id/}, comment trees) are identical on the OAuth host.
 */
@Singleton
public final class OAuthRedditTransport implements RedditTransport {

    private static final Logger LOG = LoggerFactory.getLogger(OAuthRedditTransport.class);

    private static final String WWW_BASE = "https://www.reddit.com";
    private static final String OAUTH_BASE = "https://oauth.reddit.com";
    private static final String TOKEN_URL = WWW_BASE + "/api/v1/access_token";
    private static final String INSTALLED_CLIENT_GRANT =
            "https://oauth.reddit.com/grants/installed_client";

    /** Refresh this long before the token actually expires. */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final String clientId;
    private final String deviceId;

    private volatile String accessToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    @Inject
    public OAuthRedditTransport(GlobalConfig config) {
        this.clientId = config.getReddit().getOauthClientId();
        this.deviceId = randomDeviceId();
        if (clientId == null || clientId.isBlank()) {
            LOG.error("reddit.oauth-client-id is not set — Reddit access will fail. "
                    + "Register an installed app at https://www.reddit.com/prefs/apps "
                    + "and put its client ID in config.toml.");
        }
    }

    @Override
    public RedditResponse get(String url) throws Exception {
        RedditResponse response = sendAuthorized(toOAuthUrl(url));
        // A 401 usually means the cached token was revoked/expired early —
        // refresh once and retry before giving up.
        if (response.statusCode() == 401) {
            LOG.info("Reddit OAuth token rejected (401); refreshing and retrying.");
            refreshToken();
            response = sendAuthorized(toOAuthUrl(url));
        }
        return response;
    }

    private RedditResponse sendAuthorized(String oauthUrl) throws Exception {
        String token = currentToken();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(oauthUrl))
                .header("User-Agent", RedditUserAgent.VALUE)
                .header("Authorization", "bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        Map<String, String> headers = new HashMap<>();
        response.headers().map().forEach((k, v) -> {
            if (v != null && !v.isEmpty()) headers.put(k, v.get(0));
        });
        return new RedditResponse(response.statusCode(), response.body(), headers);
    }

    /** Maps a {@code www.reddit.com} URL onto the OAuth host. */
    private static String toOAuthUrl(String url) {
        if (url.startsWith(WWW_BASE)) {
            return OAUTH_BASE + url.substring(WWW_BASE.length());
        }
        return url;
    }

    /** Returns a valid token, refreshing if the cached one is missing or stale. */
    private String currentToken() throws Exception {
        String token = accessToken;
        if (token != null && Instant.now().isBefore(expiresAt.minus(EXPIRY_MARGIN))) {
            return token;
        }
        return refreshToken();
    }

    private synchronized String refreshToken() throws Exception {
        // Another thread may have refreshed while we waited on the monitor.
        if (accessToken != null && Instant.now().isBefore(expiresAt.minus(EXPIRY_MARGIN))) {
            return accessToken;
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("reddit.oauth-client-id is not configured");
        }

        String basic = Base64.getEncoder()
                .encodeToString((clientId + ":").getBytes(StandardCharsets.UTF_8));
        String form = "grant_type=" + urlEncode(INSTALLED_CLIENT_GRANT)
                + "&device_id=" + urlEncode(deviceId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("User-Agent", RedditUserAgent.VALUE)
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Reddit token request failed: HTTP "
                    + response.statusCode() + " — " + truncate(response.body()));
        }

        JsonNode node = mapper.readTree(response.body());
        String token = node.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Reddit token response had no access_token: "
                    + truncate(response.body()));
        }
        long expiresIn = node.path("expires_in").asLong(3600L);

        accessToken = token;
        expiresAt = Instant.now().plusSeconds(expiresIn);
        LOG.info("Acquired Reddit OAuth app token (expires in {}s).", expiresIn);
        return token;
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** Reddit wants a 20–30 character unique device identifier. */
    private static String randomDeviceId() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(30);
        for (int i = 0; i < 30; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
