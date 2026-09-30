package de.bsommerfeld.tinyreddit.api;

import de.bsommerfeld.tinyfetch.api.CooldownException;
import de.bsommerfeld.tinyfetch.api.FetchException;
import de.bsommerfeld.tinyfetch.api.FetchRequest;
import de.bsommerfeld.tinyfetch.api.FetchResponse;
import de.bsommerfeld.tinyfetch.api.Fetcher;
import de.bsommerfeld.tinyfetch.api.Step;
import de.bsommerfeld.tinyfetch.api.Wall;
import de.bsommerfeld.tinyreddit.RedditFixtures;
import de.bsommerfeld.tinyreddit.model.Post;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedditClientTest {

    private static final String CAPTCHA = "<title>Reddit - Prove your humanity</title>";

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-25T12:00:00Z"));
    private final ScriptedFetcher reddit = new ScriptedFetcher();

    private RedditClient.Builder client() {
        return RedditClient.builder(reddit).clock(now::get);
    }

    @Test
    void jsonFirstAsTheFrontPageAsksForIt() throws Exception {
        reddit.answer("www.reddit.com/r/wallstreetbetsGER/new.json", ok(RedditFixtures.LISTING, "application/json"));

        Fetched<List<Post>> result = client().build().newPosts("wallstreetbetsGER", 25);

        assertEquals(Route.JSON, result.route());
        assertEquals(5, result.value().size());
        FetchRequest sent = reddit.requests.getFirst();
        assertEquals("GET", sent.method());
        assertEquals("https://www.reddit.com/r/wallstreetbetsGER/new.json?limit=25&raw_json=1", sent.uri().toString());
        assertTrue(sent.headers().isEmpty(), "nothing of our own - just the browser");
    }

    @Test
    void walledJsonFallsBackToRssAndIsLeftAlone() throws Exception {
        reddit.answer("www.reddit.com/r/wallstreetbetsGER/new.json", response(200, CAPTCHA, "text/html", Wall.CHALLENGE));
        reddit.answer("www.reddit.com/r/wallstreetbetsGER/new.rss", ok(RedditFixtures.LISTING_FEED, "application/atom+xml"));
        RedditClient client = client().build();

        Fetched<List<Post>> first = client.newPosts("wallstreetbetsGER", 25);
        assertEquals(Route.RSS, first.route());
        assertEquals(Step.ALL, reddit.requests.get(1).steps(), "RSS rides the same visitor session as JSON");
        assertEquals(Instant.parse("2026-09-25T12:10:00Z"), client.demotedUntil(Route.JSON).orElseThrow());

        reddit.requests.clear();
        client.newPosts("wallstreetbetsGER", 25);
        assertEquals(1, reddit.requests.size(), "JSON is not asked again while demoted");

        now.set(Instant.parse("2026-09-25T12:10:01Z"));
        reddit.requests.clear();
        client.newPosts("wallstreetbetsGER", 25);
        assertTrue(reddit.requests.getFirst().uri().getPath().endsWith(".json"), "retried after the demotion");
    }

    @Test
    void pausedHostDemotesUntilThePauseEnds() throws Exception {
        Instant pauseEnd = Instant.parse("2026-09-25T14:00:00Z");
        reddit.fail("www.reddit.com", new CooldownException("www.reddit.com", pauseEnd, Wall.CHALLENGE));
        RedditClient client = client().build();

        RedditException failure = assertThrows(RedditException.class,
                () -> client.newPosts("wallstreetbetsGER", 25));
        assertEquals(List.of(Route.JSON, Route.RSS), List.copyOf(failure.attempts().keySet()));
        assertEquals(pauseEnd, client.demotedUntil(Route.RSS).orElseThrow(), "the pause outlasts the 10 minutes");

        reddit.requests.clear();
        assertThrows(RedditException.class, () -> client.newPosts("wallstreetbetsGER", 25));
        assertTrue(reddit.requests.isEmpty(), "while every route is left alone, nothing is sent");
    }

    @Test
    void aGlitchSkipsTheRouteForThisCallOnly() throws Exception {
        reddit.answer("www.reddit.com/r/wallstreetbetsGER/new.json", response(500, "oops", "text/plain", Wall.NONE));
        reddit.answer("www.reddit.com/r/wallstreetbetsGER/new.rss", ok(RedditFixtures.LISTING_FEED, "application/atom+xml"));
        RedditClient client = client().build();

        assertEquals(Route.RSS, client.newPosts("wallstreetbetsGER", 25).route());
        assertTrue(client.demotedUntil(Route.JSON).isEmpty());
    }

    @Test
    void notFoundIsFinal() {
        reddit.answer("www.reddit.com/r/gibtsnicht/new.json", response(404, "{}", "application/json", Wall.NONE));
        assertThrows(NotFoundException.class, () -> client().build().newPosts("gibtsnicht", 25));
        assertEquals(1, reddit.requests.size(), "RSS would say the same - not asked");
    }

    @Test
    void lookupByIdSkipsRssAndBatchesByHundred() throws Exception {
        reddit.answer("www.reddit.com/by_id/", ok(RedditFixtures.LISTING, "application/json"));
        List<String> ids = IntStream.range(0, 150).mapToObj(i -> "id" + i).toList();

        Fetched<List<Post>> result = client().build().posts(ids);

        assertEquals(Route.JSON, result.route());
        assertEquals(2, reddit.requests.size());
        assertTrue(reddit.requests.getFirst().uri().getPath().startsWith("/by_id/t3_id0,t3_id1,"));
    }

    @Test
    void lookupByIdOverRssAloneIsImpossible() {
        RedditClient rssOnly = client().routes(Route.RSS).build();
        RedditException failure = assertThrows(RedditException.class, () -> rssOnly.posts(List.of("t3_a1")));
        assertEquals("cannot look up by id", failure.attempts().get(Route.RSS));
        assertTrue(reddit.requests.isEmpty());
    }

    @Test
    void limitsAreBoundedAndNamesChecked() throws Exception {
        reddit.answer("www.reddit.com/", ok(RedditFixtures.LISTING, "application/json"));
        client().build().newPosts("wallstreetbetsGER", 5000);
        assertTrue(reddit.requests.getFirst().uri().getQuery().startsWith("limit=100&"));
        assertThrows(IllegalArgumentException.class, () -> client().build().newPosts("../etc", 5));
    }

    @Test
    void hostPoliciesKeepTheAnonymousBudget() {
        Map<String, de.bsommerfeld.tinyfetch.api.HostPolicy> policies = RedditClient.hostPolicies();
        assertEquals(Duration.ofSeconds(6), policies.get("www.reddit.com").minInterval());
    }

    // ---- fake ----------------------------------------------------------------

    @FunctionalInterface
    private interface Answer {
        FetchResponse to(FetchRequest request) throws FetchException;
    }

    /** Answers by the longest matching {@code host + path} prefix; records every request. */
    private static final class ScriptedFetcher implements Fetcher {
        final List<FetchRequest> requests = new ArrayList<>();
        private final Map<String, Answer> answers = new LinkedHashMap<>();

        void answer(String prefix, FetchResponse response) {
            answers.put(prefix, request -> response);
        }

        void fail(String prefix, FetchException failure) {
            answers.put(prefix, request -> {
                throw failure;
            });
        }

        @Override
        public FetchResponse fetch(FetchRequest request) throws FetchException {
            requests.add(request);
            String key = request.uri().getHost() + request.uri().getPath();
            String best = null;
            for (String prefix : answers.keySet()) {
                if (key.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                    best = prefix;
                }
            }
            if (best == null) {
                throw new FetchException("no scripted answer for " + key);
            }
            return answers.get(best).to(request);
        }
    }

    private static FetchResponse ok(String body, String contentType) {
        return response(200, body, contentType, Wall.NONE);
    }

    private static FetchResponse response(int status, String body, String contentType, Wall wall) {
        return new FetchResponse(status, URI.create("https://www.reddit.com/"),
                Map.of("content-type", List.of(contentType)), body.getBytes(StandardCharsets.UTF_8), wall);
    }
}
