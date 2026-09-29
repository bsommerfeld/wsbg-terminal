package de.bsommerfeld.tinyreddit.route;

import de.bsommerfeld.tinyfetch.api.FetchException;
import de.bsommerfeld.tinyfetch.api.FetchRequest;
import de.bsommerfeld.tinyfetch.api.FetchResponse;
import de.bsommerfeld.tinyfetch.api.Fetcher;
import de.bsommerfeld.tinyreddit.api.Route;
import de.bsommerfeld.tinyreddit.json.Json;
import de.bsommerfeld.tinyreddit.json.JsonException;
import de.bsommerfeld.tinyreddit.json.JsonNode;
import de.bsommerfeld.tinyreddit.mapping.JsonMapper;
import de.bsommerfeld.tinyreddit.mapping.RedditText;
import de.bsommerfeld.tinyreddit.model.Comment;
import de.bsommerfeld.tinyreddit.model.Discussion;
import de.bsommerfeld.tinyreddit.model.Post;

import java.util.List;

/**
 * {@link Route#JSON} and {@link Route#OAUTH}: the same paths and the same
 * payloads, on two hosts and asked for in two ways.
 *
 * <ul>
 *   <li>JSON: {@code www.reddit.com/<path>.json}, asked for by Reddit's own
 *       front page - the browser tab parked there, in the visitor session
 *       that page set up for itself.</li>
 *   <li>OAuth: {@code oauth.reddit.com/<path>}, with the bearer token and the
 *       app's own user agent, as Reddit's API rules ask. A {@code 401} fetches
 *       a new token once.</li>
 * </ul>
 * Every URL carries {@code raw_json=1}, so text comes unescaped.
 */
public final class JsonAccess implements RouteAccess {

    /** Comments per comments-page request; Reddit's own ceiling is 500. */
    private static final int DISCUSSION_LIMIT = 500;
    private static final int DISCUSSION_DEPTH = 10;

    private final Fetcher fetcher;
    private final OAuthToken token;

    private JsonAccess(Fetcher fetcher, OAuthToken token) {
        this.fetcher = fetcher;
        this.token = token;
    }

    /** Anonymous, through {@code www.reddit.com}, in the visitor session. */
    public static JsonAccess anonymous(Fetcher fetcher) {
        return new JsonAccess(fetcher, null);
    }

    /** Application-only OAuth, through {@code oauth.reddit.com}. */
    public static JsonAccess oauth(Fetcher fetcher, OAuthToken token) {
        return new JsonAccess(fetcher, token);
    }

    @Override
    public Route route() {
        return token == null ? Route.JSON : Route.OAUTH;
    }

    @Override
    public List<Post> listing(String subreddit, String sort, int limit)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        String path = "/r/" + RedditUrls.subreddit(subreddit) + "/" + RedditUrls.sort(sort);
        return JsonMapper.posts(get(path, "limit=" + limit));
    }

    @Override
    public List<Comment> latestComments(String subreddit, int limit)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        String path = "/r/" + RedditUrls.subreddit(subreddit) + "/comments";
        return JsonMapper.streamComments(get(path, "limit=" + limit));
    }

    @Override
    public Discussion discussion(String permalink)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        JsonNode page = get(RedditText.normalizePermalink(permalink),
                "limit=" + DISCUSSION_LIMIT + "&depth=" + DISCUSSION_DEPTH);
        if (!page.isArray() || page.size() < 1) {
            throw new MalformedAnswerException("comments page is not a two-part array", null);
        }
        return JsonMapper.discussion(page);
    }

    @Override
    public boolean canLookUpPosts() {
        return true;
    }

    @Override
    public List<Post> posts(List<String> fullnames)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        return JsonMapper.posts(get("/by_id/" + String.join(",", fullnames), null));
    }

    private JsonNode get(String path, String query)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        String fullQuery = query == null ? "raw_json=1" : query + "&raw_json=1";
        FetchResponse response;
        if (token == null) {
            response = fetcher.fetch(FetchRequest.of(RedditUrls.of(RedditUrls.WWW, path + ".json", fullQuery)));
        } else {
            response = authorized(RedditUrls.of(RedditUrls.OAUTH, path, fullQuery));
        }
        if (!response.ok()) {
            throw new RouteRefused(response);
        }
        try {
            return Json.parse(response.text());
        } catch (JsonException e) {
            throw new MalformedAnswerException("not JSON: " + e.getMessage(), e);
        }
    }

    private FetchResponse authorized(String url)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        FetchResponse response = fetcher.fetch(bearer(url));
        if (response.status() == 401) {
            token.invalidate();
            response = fetcher.fetch(bearer(url));
        }
        return response;
    }

    private FetchRequest bearer(String url)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        return FetchRequest.of(url)
                .header("authorization", "bearer " + token.current())
                .header("user-agent", token.userAgent());
    }
}
