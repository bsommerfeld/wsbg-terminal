package de.bsommerfeld.tinyrss.api;

import de.bsommerfeld.tinyfetch.api.FetchException;
import de.bsommerfeld.tinyfetch.api.FetchRequest;
import de.bsommerfeld.tinyfetch.api.FetchResponse;
import de.bsommerfeld.tinyfetch.api.Fetcher;
import de.bsommerfeld.tinyfetch.api.Step;
import de.bsommerfeld.tinyrss.model.Feed;
import de.bsommerfeld.tinyrss.model.FeedLink;

import java.util.List;
import java.util.Objects;

/**
 * Reads any site's feed.
 *
 * <h2>Traffic</h2>
 * Everything goes out through the {@link Fetcher} it is given - in production
 * a {@code TinyFetch}, at the host's pace, paused after the first wall. A feed
 * is a file that answers its first request, so by default it is fetched as
 * {@link Step#FETCH} alone: one request from the browser's network stack, no
 * page loaded first, no readiness probing - every one of those would be one
 * more request a strict rate limit counts (FinancialJuice banned the tab's
 * three, and answers the single one; the whole corpus of 131 feeds answered
 * it as well as it answers curl, measured 2026-09-30). A feed behind a
 * visitor check gets the steps it needs through the second constructor.
 * No {@code Accept} of our own: a strict one gets {@code 406} from some
 * houses. Every call is exactly one fetch; this reader never retries, follows
 * a discovered link or polls on its own - how often to ask is the caller's
 * decision.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * FeedReader feeds = new FeedReader(fetch);
 *
 * Feed news = feeds.read("https://www.tagesschau.de/wirtschaft/index~rss2.xml");
 * for (Entry entry : news.entries()) {
 *     show(entry.title(), entry.link(), entry.time());
 * }
 *
 * // a site, not a feed: ask the page which feeds it has
 * List<FeedLink> found = feeds.discover("https://www.heise.de/");
 * }</pre>
 *
 * <h2>What it does not do</h2>
 * No storage, no change detection, no scheduling: it returns what the feed
 * says now. {@link de.bsommerfeld.tinyrss.model.Entry#id()} is stable across
 * reads, so telling new entries from seen ones is a set lookup - the
 * caller's.
 */
public final class FeedReader {

    private final Fetcher fetcher;
    private final Step[] steps;

    /** Reads with {@link Step#FETCH} alone - one request per feed, no page. */
    public FeedReader(Fetcher fetcher) {
        this(fetcher, Step.FETCH);
    }

    /**
     * Reads with the given steps - {@code LOAD_PAGE, READINESS_CHECK, FETCH}
     * for a feed on a site that checks its visitors first.
     *
     * @throws IllegalArgumentException steps that make no request ({@link Step#of})
     */
    public FeedReader(Fetcher fetcher, Step first, Step... more) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        Step[] steps = new Step[more.length + 1];
        steps[0] = first;
        System.arraycopy(more, 0, steps, 1, more.length);
        Step.of(steps);
        this.steps = steps;
    }

    /**
     * Reads the feed at {@code url} - one request.
     *
     * @throws FeedRefusedException the host answered with an error status or a wall
     * @throws NotAFeedException    the answer is no feed - an HTML page, JSON, nothing
     * @throws FetchException       no answer: network, timeout, or the host is paused ({@code CooldownException})
     */
    public Feed read(String url)
            throws FetchException, FeedRefusedException, NotAFeedException, InterruptedException {
        FetchResponse response = fetch(url);
        return FeedParser.parse(response.body(), response.header("content-type").orElse(null),
                response.url().toString());
    }

    /**
     * The feeds a page announces - one request. Hand it a site's front page
     * or an article: the page's {@code <link rel="alternate">} feeds come
     * back, in the page's order. A URL that already is a feed comes back as
     * itself. Empty when the page names none.
     *
     * @throws FeedRefusedException the host answered with an error status or a wall
     * @throws FetchException       no answer: network, timeout, or the host is paused
     */
    public List<FeedLink> discover(String pageUrl) throws FetchException, FeedRefusedException, InterruptedException {
        FetchResponse response = fetch(pageUrl);
        return FeedParser.links(response.body(), response.header("content-type").orElse(null),
                response.url().toString());
    }

    private FetchResponse fetch(String url) throws FetchException, FeedRefusedException, InterruptedException {
        FetchResponse response = fetcher.fetch(FetchRequest.of(url, steps));
        if (!response.ok()) {
            throw new FeedRefusedException(response);
        }
        return response;
    }
}
