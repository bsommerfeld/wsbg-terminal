package de.bsommerfeld.tinyreddit.api;

import de.bsommerfeld.tinyfetch.api.CooldownException;
import de.bsommerfeld.tinyfetch.api.FetchException;
import de.bsommerfeld.tinyfetch.api.Fetcher;
import de.bsommerfeld.tinyfetch.api.HostPolicy;
import de.bsommerfeld.tinyreddit.model.Comment;
import de.bsommerfeld.tinyreddit.model.Discussion;
import de.bsommerfeld.tinyreddit.model.Post;
import de.bsommerfeld.tinyreddit.route.JsonAccess;
import de.bsommerfeld.tinyreddit.route.MalformedAnswerException;
import de.bsommerfeld.tinyreddit.route.RouteAccess;
import de.bsommerfeld.tinyreddit.route.RouteRefused;
import de.bsommerfeld.tinyreddit.route.RssAccess;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Reads Reddit: listings, the subreddit-wide comment stream, comment pages
 * and posts by id - over whichever {@link Route} currently works.
 *
 * <h2>Traffic</h2>
 * Everything goes out through the {@link Fetcher} it is given - in production
 * a {@code TinyFetch}, so every route is the user's own browser
 * reading Reddit, at a person's pace, stopping at the first sign of refusal.
 * Give that client {@link #hostPolicies()}: Reddit's anonymous budget is small,
 * and the policy is what keeps a poll loop inside it. Every call is exactly one
 * request per route tried; this client never loops, retries or fans out on
 * its own - how often to ask is the caller's decision.
 *
 * <h2>The visitor session</h2>
 * Reddit answers anonymous requests only inside a visitor session it issues
 * to a browser that ran its front page ({@code loid}, {@code token_v2}).
 * With {@code TinyFetch} there is nothing to arrange: its browser tab parked
 * on {@code www.reddit.com} is that front page, sets the session up the way
 * any visitor's does, and asks for JSON and RSS as its own scripts would.
 *
 * <h2>Routes</h2>
 * Routes are tried best data first ({@link Route}). A route that Reddit
 * refuses (a wall) or whose host is paused is left alone for
 * {@link Builder#demotion} or until the pause ends, whichever is later, and
 * the next route answers meanwhile. A route that merely failed (network,
 * malformed answer) is only skipped for that one call. {@code JSON} and
 * {@code RSS} share {@code www.reddit.com}: when Reddit pauses the host, RSS
 * fails at once as well, without a request - the refusal was for the
 * network, not the format.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TinyFetch.Builder http = TinyFetch.builder().engine(browserEngine);
 * RedditClient.hostPolicies().forEach(http::policy);
 *
 * try (TinyFetch fetch = http.build()) {
 *     RedditClient reddit = RedditClient.builder(fetch).build();
 *
 *     Fetched<List<Post>> fresh = reddit.newPosts("wallstreetbetsGER", 50);
 *     for (Post post : fresh.value()) {
 *         ...
 *     }
 *     if (fresh.route() == Route.RSS) {
 *         // scores are unknown, not zero
 *     }
 * } catch (RedditException unreachable) {
 *     // every route failed; unreachable.attempts() says why
 * }
 * }</pre>
 *
 * <h2>What it does not do</h2>
 * No storage, no change detection, no scheduling: it returns what Reddit says
 * now. Comparing with what was seen before belongs to the caller.
 */
public final class RedditClient {

    /** Most items one listing request asks for; Reddit's own ceiling. */
    public static final int MAX_LIMIT = 100;

    /** Most ids per {@link #posts} request; beyond it Reddit answers 414. */
    private static final int IDS_PER_REQUEST = 100;

    private final List<RouteAccess> routes;
    private final Duration demotion;
    private final Supplier<Instant> clock;
    private final Map<Route, Instant> demotedUntil = new ConcurrentHashMap<>();

    private RedditClient(List<RouteAccess> routes, Duration demotion, Supplier<Instant> clock) {
        this.routes = List.copyOf(routes);
        this.demotion = demotion;
        this.clock = clock;
    }

    public static Builder builder(Fetcher fetcher) {
        return new Builder(fetcher);
    }

    /**
     * The pace Reddit's host is to be asked at - hand these to the
     * {@code TinyFetch} builder. {@code www.reddit.com}: one request every
     * 6 s at most (10 a minute, Reddit's anonymous budget) plus jitter.
     */
    public static Map<String, HostPolicy> hostPolicies() {
        return Map.of("www.reddit.com", HostPolicy.defaults().withMinInterval(Duration.ofSeconds(6)));
    }

    /** The newest posts of a subreddit, newest first. */
    public Fetched<List<Post>> newPosts(String subreddit, int limit) throws RedditException, InterruptedException {
        return listing(subreddit, "new", limit);
    }

    /** The subreddit's front page, by Reddit's hotness. */
    public Fetched<List<Post>> hotPosts(String subreddit, int limit) throws RedditException, InterruptedException {
        return listing(subreddit, "hot", limit);
    }

    /**
     * The newest comments of the whole subreddit, across all its posts, in
     * one request - far cheaper than asking post by post.
     */
    public Fetched<List<Comment>> latestComments(String subreddit, int limit)
            throws RedditException, InterruptedException {
        int bounded = bound(limit);
        return attempt("comments of r/" + subreddit, false, access -> access.latestComments(subreddit, bounded));
    }

    /** A post with its comments. */
    public Fetched<Discussion> discussion(String permalink) throws RedditException, InterruptedException {
        Objects.requireNonNull(permalink, "permalink");
        return attempt(permalink, false, access -> access.discussion(permalink));
    }

    /**
     * The current state of known posts - score, comment count - by fullname
     * ({@code t3_...}; bare ids get the prefix). Not available over RSS. Up to
     * 100 ids per request, so larger sets cost one request per hundred.
     */
    public Fetched<List<Post>> posts(Collection<String> ids) throws RedditException, InterruptedException {
        List<String> fullnames = new ArrayList<>(new LinkedHashSet<>(ids.stream()
                .map(id -> id.startsWith("t3_") ? id : "t3_" + id)
                .toList()));
        if (fullnames.isEmpty()) {
            return new Fetched<>(List.of(), routes.getFirst().route());
        }
        List<Post> all = new ArrayList<>();
        Route route = null;
        for (int from = 0; from < fullnames.size(); from += IDS_PER_REQUEST) {
            List<String> chunk = fullnames.subList(from, Math.min(fullnames.size(), from + IDS_PER_REQUEST));
            Fetched<List<Post>> part = attempt(chunk.size() + " posts by id", true, access -> access.posts(chunk));
            all.addAll(part.value());
            route = part.route();
        }
        return new Fetched<>(all, route);
    }

    /** When {@code route} will be tried again, if it is currently left alone. */
    public Optional<Instant> demotedUntil(Route route) {
        Instant until = demotedUntil.get(route);
        return until != null && clock.get().isBefore(until) ? Optional.of(until) : Optional.empty();
    }

    private Fetched<List<Post>> listing(String subreddit, String sort, int limit)
            throws RedditException, InterruptedException {
        int bounded = bound(limit);
        return attempt("r/" + subreddit + "/" + sort, false, access -> access.listing(subreddit, sort, bounded));
    }

    // ---- route walk -------------------------------------------------------

    @FunctionalInterface
    private interface Call<T> {
        T on(RouteAccess access) throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException;
    }

    private <T> Fetched<T> attempt(String what, boolean needsLookup, Call<T> call)
            throws RedditException, InterruptedException {
        Map<Route, String> attempts = new LinkedHashMap<>();
        for (RouteAccess access : routes) {
            Route route = access.route();
            if (needsLookup && !access.canLookUpPosts()) {
                attempts.put(route, "cannot look up by id");
                continue;
            }
            Optional<Instant> demoted = demotedUntil(route);
            if (demoted.isPresent()) {
                attempts.put(route, "left alone until " + demoted.get());
                continue;
            }
            try {
                T value = call.on(access);
                demotedUntil.remove(route);
                return new Fetched<>(value, route);
            } catch (CooldownException paused) {
                demote(route, paused.until());
                attempts.put(route, "host paused after " + paused.reason() + " until " + paused.until());
            } catch (RouteRefused refused) {
                if (refused.status() == 404) {
                    throw new NotFoundException(what, route);
                }
                if (refused.isRefusal()) {
                    demote(route, clock.get());
                }
                attempts.put(route, refused.getMessage());
            } catch (FetchException | MalformedAnswerException failed) {
                attempts.put(route, failed.getMessage());
            }
        }
        throw new RedditException(what + ": no route answered", attempts);
    }

    /** Leaves a route alone for the demotion time, or until {@code atLeastUntil} if later. */
    private void demote(Route route, Instant atLeastUntil) {
        Instant byPolicy = clock.get().plus(demotion);
        demotedUntil.put(route, atLeastUntil.isAfter(byPolicy) ? atLeastUntil : byPolicy);
    }

    private static int bound(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    // ---- builder ------------------------------------------------------------

    /** Configures a {@link RedditClient}. It reads over JSON, then RSS, unless told otherwise. */
    public static final class Builder {

        private final Fetcher fetcher;
        private List<Route> order = List.of(Route.JSON, Route.RSS);
        private Duration demotion = Duration.ofMinutes(10);
        private Supplier<Instant> clock = Instant::now;

        private Builder(Fetcher fetcher) {
            this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        }

        /** The routes to use, in the order to try them. */
        public Builder routes(Route... order) {
            if (order.length == 0) {
                throw new IllegalArgumentException("at least one route");
            }
            this.order = List.of(order);
            return this;
        }

        /** How long a refused route is left alone at least; 10 minutes unless set. */
        public Builder demotion(Duration demotion) {
            this.demotion = Objects.requireNonNull(demotion, "demotion");
            return this;
        }

        /** The clock for demotion; for tests. */
        public Builder clock(Supplier<Instant> clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public RedditClient build() {
            List<RouteAccess> routes = new ArrayList<>();
            for (Route route : new LinkedHashSet<>(order)) {
                routes.add(switch (route) {
                    case JSON -> new JsonAccess(fetcher);
                    case RSS -> new RssAccess(fetcher);
                });
            }
            return new RedditClient(routes, demotion, clock);
        }
    }
}
