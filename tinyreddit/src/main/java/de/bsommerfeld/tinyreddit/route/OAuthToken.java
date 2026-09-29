package de.bsommerfeld.tinyreddit.route;

import de.bsommerfeld.tinyfetch.api.FetchException;
import de.bsommerfeld.tinyfetch.api.FetchRequest;
import de.bsommerfeld.tinyfetch.api.FetchResponse;
import de.bsommerfeld.tinyfetch.api.Fetcher;
import de.bsommerfeld.tinyreddit.json.Json;
import de.bsommerfeld.tinyreddit.json.JsonException;
import de.bsommerfeld.tinyreddit.json.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.function.Supplier;

/**
 * An application-only OAuth token ({@code installed_client} grant): it
 * authenticates the registered app by its client id, no Reddit user signs in.
 * Fetched when first needed, kept until shortly before it expires.
 */
public final class OAuthToken {

    private static final String GRANT = "https://oauth.reddit.com/grants/installed_client";
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);

    private final Fetcher fetcher;
    private final String clientId;
    private final String deviceId;
    private final String userAgent;
    private final Supplier<Instant> clock;

    private String token;
    private Instant expiresAt = Instant.EPOCH;

    /**
     * @param deviceId  20-30 characters naming this installation; keep it stable across runs
     * @param userAgent the app's user agent, {@code <platform>:<app id>:<version> (by /u/<user>)}
     */
    public OAuthToken(Fetcher fetcher, String clientId, String deviceId, String userAgent, Supplier<Instant> clock) {
        this.fetcher = fetcher;
        this.clientId = clientId;
        this.deviceId = deviceId;
        this.userAgent = userAgent;
        this.clock = clock;
    }

    public String userAgent() {
        return userAgent;
    }

    /** A valid token, fetched if there is none or it is about to expire. */
    public synchronized String current()
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        if (token == null || !clock.get().isBefore(expiresAt.minus(EXPIRY_MARGIN))) {
            refresh();
        }
        return token;
    }

    /** Drops the token, e.g. after a {@code 401}; the next {@link #current()} fetches a new one. */
    public synchronized void invalidate() {
        token = null;
    }

    private void refresh() throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        String credentials = Base64.getEncoder()
                .encodeToString((clientId + ":").getBytes(StandardCharsets.UTF_8));
        String form = "grant_type=" + URLEncoder.encode(GRANT, StandardCharsets.UTF_8)
                + "&device_id=" + URLEncoder.encode(deviceId, StandardCharsets.UTF_8);

        FetchResponse response = fetcher.fetch(FetchRequest
                .of(RedditUrls.of(RedditUrls.WWW, "/api/v1/access_token", null))
                .post("application/x-www-form-urlencoded", form)
                .header("authorization", "Basic " + credentials)
                .header("user-agent", userAgent));
        if (!response.ok()) {
            throw new RouteRefused(response);
        }
        try {
            JsonNode body = Json.parse(response.text());
            String accessToken = body.path("access_token").asText("");
            if (accessToken.isEmpty()) {
                throw new MalformedAnswerException("token answer without access_token: "
                        + body.path("error").asText("?"), null);
            }
            token = accessToken;
            expiresAt = clock.get().plusSeconds(body.path("expires_in").asLong(3600));
        } catch (JsonException e) {
            throw new MalformedAnswerException("token answer is not JSON", e);
        }
    }
}
