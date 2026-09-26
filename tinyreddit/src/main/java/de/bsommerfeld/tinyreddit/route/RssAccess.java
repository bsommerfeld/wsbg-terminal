package de.bsommerfeld.tinyreddit.route;

import de.bsommerfeld.tinyfetch.api.FetchException;
import de.bsommerfeld.tinyfetch.api.FetchRequest;
import de.bsommerfeld.tinyfetch.api.FetchResponse;
import de.bsommerfeld.tinyfetch.api.Fetcher;
import de.bsommerfeld.tinyreddit.api.Route;
import de.bsommerfeld.tinyreddit.mapping.AtomFeed;
import de.bsommerfeld.tinyreddit.mapping.AtomMapper;
import de.bsommerfeld.tinyreddit.mapping.RedditText;
import de.bsommerfeld.tinyreddit.model.Comment;
import de.bsommerfeld.tinyreddit.model.Discussion;
import de.bsommerfeld.tinyreddit.model.Post;

import javax.xml.stream.XMLStreamException;
import java.util.List;

/**
 * {@link Route#RSS}: Reddit's Atom feeds on {@code www.reddit.com}, asked for
 * by a script of Reddit's front page in the visitor session, as the JSON route
 * does - the two share the host and its session.
 */
public final class RssAccess implements RouteAccess {

    /** Reddit's ceiling for feeds. */
    private static final int FEED_LIMIT = 100;

    private final Fetcher fetcher;
    private final RedditSession session;

    public RssAccess(Fetcher fetcher, RedditSession session) {
        this.fetcher = fetcher;
        this.session = session;
    }

    @Override
    public Route route() {
        return Route.RSS;
    }

    @Override
    public List<Post> listing(String subreddit, String sort, int limit)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        String path = "/r/" + RedditUrls.subreddit(subreddit) + "/" + RedditUrls.sort(sort) + ".rss";
        return AtomMapper.posts(feed(path, Math.min(limit, FEED_LIMIT)), subreddit);
    }

    @Override
    public List<Comment> latestComments(String subreddit, int limit)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        String path = "/r/" + RedditUrls.subreddit(subreddit) + "/comments/.rss";
        return AtomMapper.streamComments(feed(path, Math.min(limit, FEED_LIMIT)));
    }

    @Override
    public Discussion discussion(String permalink)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        String path = RedditText.normalizePermalink(permalink);
        RedditText.CommentPath coordinates = RedditText.commentPathOf(path + "/");
        String subreddit = coordinates == null ? "" : coordinates.subreddit();
        Discussion discussion = AtomMapper.discussion(feed(path + "/.rss", FEED_LIMIT), subreddit);
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

    private List<AtomFeed.Entry> feed(String path, int limit)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException {
        session.ensure();
        FetchResponse response = fetcher.fetch(FetchRequest.data(RedditUrls.of(RedditUrls.WWW, path, "limit=" + limit))
                .referer(RedditSession.HOME));
        if (!response.ok()) {
            throw new RouteRefused(response);
        }
        try {
            return AtomFeed.parse(response.text());
        } catch (XMLStreamException e) {
            throw new MalformedAnswerException("not an Atom feed: " + e.getMessage(), e);
        }
    }
}
