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
 * {@link Route#JSON}: {@code www.reddit.com/<path>.json}, asked for by
 * Reddit's own front page - the browser tab parked there, in the visitor
 * session that page set up for itself. Every URL carries {@code raw_json=1},
 * so text comes unescaped.
 */
public final class JsonAccess implements RouteAccess {

    /** Comments per comments-page request; Reddit's own ceiling is 500. */
    private static final int DISCUSSION_LIMIT = 500;
    private static final int DISCUSSION_DEPTH = 10;

    private final Fetcher fetcher;

    public JsonAccess(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public Route route() {
        return Route.JSON;
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
        FetchResponse response = fetcher.fetch(
                FetchRequest.of(RedditUrls.of(RedditUrls.WWW, path + ".json", fullQuery)));
        if (!response.ok()) {
            throw new RouteRefused(response);
        }
        try {
            return Json.parse(response.text());
        } catch (JsonException e) {
            throw new MalformedAnswerException("not JSON: " + e.getMessage(), e);
        }
    }
}
