package de.bsommerfeld.tinyreddit.route;

import de.bsommerfeld.tinyfetch.api.FetchException;
import de.bsommerfeld.tinyfetch.api.Fetcher;
import de.bsommerfeld.tinyfetch.api.Step;
import de.bsommerfeld.tinyreddit.api.Route;
import de.bsommerfeld.tinyreddit.mapping.RedditText;
import de.bsommerfeld.tinyreddit.mapping.RssMapper;
import de.bsommerfeld.tinyreddit.model.Comment;
import de.bsommerfeld.tinyreddit.model.Discussion;
import de.bsommerfeld.tinyreddit.model.Post;
import de.bsommerfeld.tinyrss.api.FeedReader;
import de.bsommerfeld.tinyrss.api.FeedRefusedException;
import de.bsommerfeld.tinyrss.api.NotAFeedException;
import de.bsommerfeld.tinyrss.model.Entry;

import java.util.List;

/**
 * {@link Route#RSS}: Reddit's Atom feeds on {@code www.reddit.com}, asked for
 * by Reddit's own front page in its visitor session, as the JSON route does -
 * the two share the host, its tab and its session. TinyRss reads them.
 */
public final class RssAccess implements RouteAccess {

    /** Reddit's ceiling for feeds. */
    private static final int FEED_LIMIT = 100;

    private final FeedReader feeds;

    public RssAccess(Fetcher fetcher) {
        // Reddit answers only inside the visitor session its front page sets up.
        this.feeds = new FeedReader(fetcher, Step.LOAD_PAGE, Step.READINESS_CHECK, Step.FETCH);
    }

    @Override
    public Route route() {
        return Route.RSS;
    }

    @Override
    public List<Post> listing(String subreddit, String sort, int limit)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        String path = "/r/" + RedditUrls.subreddit(subreddit) + "/" + RedditUrls.sort(sort) + ".rss";
        return RssMapper.posts(feed(path, Math.min(limit, FEED_LIMIT)), subreddit);
    }

    @Override
    public List<Comment> latestComments(String subreddit, int limit)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        String path = "/r/" + RedditUrls.subreddit(subreddit) + "/comments/.rss";
        return RssMapper.streamComments(feed(path, Math.min(limit, FEED_LIMIT)));
    }

    @Override
    public Discussion discussion(String permalink)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        String path = RedditText.normalizePermalink(permalink);
        RedditText.CommentPath coordinates = RedditText.commentPathOf(path + "/");
        String subreddit = coordinates == null ? "" : coordinates.subreddit();
        Discussion discussion = RssMapper.discussion(feed(path + "/.rss", FEED_LIMIT), subreddit);
        if (discussion == null) {
            throw new MalformedAnswerException("comment feed without its post: " + path, null);
        }
        return discussion;
    }

    /** Atom has no lookup by id. */
    @Override
    public boolean canLookUpPosts() {
        return false;
    }

    @Override
    public List<Post> posts(List<String> fullnames) {
        throw new UnsupportedOperationException("RSS has no lookup by id");
    }

    private List<Entry> feed(String path, int limit)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        try {
            return feeds.read(RedditUrls.of(RedditUrls.WWW, path, "limit=" + limit)).entries();
        } catch (FeedRefusedException refused) {
            throw new RouteRefused(refused.response());
        } catch (NotAFeedException malformed) {
            throw new MalformedAnswerException(malformed.getMessage(), malformed);
        }
    }
}
