package de.bsommerfeld.tinyreddit.api;

import de.bsommerfeld.tinyfetch.api.BrowserSession;
import de.bsommerfeld.tinyfetch.api.CaptchaRequiredException;
import de.bsommerfeld.tinyfetch.api.CooldownException;
import de.bsommerfeld.tinyfetch.api.FetchException;
import de.bsommerfeld.tinyfetch.api.FetchRequest;
import de.bsommerfeld.tinyfetch.api.FetchResponse;
import de.bsommerfeld.tinyfetch.api.Fetcher;
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
    void jsonFirstAsAScriptOfTheFrontPage() throws Exception {
        reddit.answer("www.reddit.com/r/wallstreetbetsGER/new.json", ok(RedditFixtures.LISTING, "application/json"));

        Fetched<List<Post>> result = client().build().newPosts("wallstreetbetsGER", 25);

        assertEquals(Route.JSON, result.route());
        assertEquals(5, result.value().size());
        FetchRequest sent = reddit.requests.getFirst();
        assertEquals(FetchRequest.Kind.DATA, sent.kind());
        assertEquals("https://www.reddit.com/", sent.referer().orElseThrow().toString());
        assertEquals("https://www.reddit.com/r/wallstreetbetsGER/new.json?limit=25&raw_json=1", sent.uri().toString());
        assertTrue(sent.headers().isEmpty(), "nothing of our own on an anonymous route - just the browser");
    }

    @Test
    void visitorSessionIsOpenedOnceThenEveryRequestRidesIt() throws Exception {
        SessionFetcher withSession = new SessionFetcher(reddit);
        reddit.answer("www.reddit.com/r/", ok(RedditFixtures.LISTING, "application/json"));
        RedditClient client = RedditClient.builder(withSession).clock(now::get).build();

        client.newPosts("wallstreetbetsGER", 5);
        client.hotPosts("wallstreetbetsGER", 5);

        assertEquals(List.of("https://www.reddit.com/ [loid, token_v2]"), withSession.unlocks);
        assertEquals(2, reddit.requests.size());
    }

    @Test
    void unsolvedCaptchaStopsBothRoutesWithOneEngineRun() throws Exception {
        SessionFetcher refused = new SessionFetcher(reddit);
        refused.captcha = true;
        RedditClient client = RedditClient.builder(refused).clock(now::get).build();

        RedditException failure = assertThrows(RedditException.class, () -> client.newPosts("wallstreetbetsGER", 5));

        assertEquals(1, refused.unlocks.size(), "RSS shares the session and must not unlock again");
        assertTrue(failure.attempts().get(Route.JSON).startsWith("CAPTCHA unsolved"));
        assertTrue(failure.attempts().get(Route.RSS).startsWith("CAPTCHA unsolved"));
        assertTrue(reddit.requests.isEmpty(), "nothing went to Reddit without a session");

        now.set(now.get().plus(Duration.ofMinutes(31)));
        assertThrows(RedditException.class, () -> client.newPosts("wallstreetbetsGER", 5));
        assertEquals(2, refused.unlocks.size(), "tried again once the memory ran out");
    }

    @Test
    void walledJsonFallsBackToRssAndIsLeftAlone() throws Exception {
        reddit.answer("www.reddit.com/r/wallstreetbetsGER/new.json", response(200, CAPTCHA, "text/html", Wall.CHALLENGE));
        reddit.answer("www.reddit.com/r/wallstreetbetsGER/new.rss", ok(RedditFixtures.LISTING_FEED, "application/atom+xml"));
        RedditClient client = client().build();

        Fetched<List<Post>> first = client.newPosts("wallstreetbetsGER", 25);
        assertEquals(Route.RSS, first.route());
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
    void oauthFetchesATokenAndIdentifiesAsTheApp() throws Exception {
        reddit.answer("www.reddit.com/api/v1/access_token",
                ok("{\"access_token\": \"tok1\", \"expires_in\": 3600}", "application/json"));
        reddit.answer("oauth.reddit.com/r/wallstreetbetsGER/hot", ok(RedditFixtures.LISTING, "application/json"));
        String userAgent = "java:de.bsommerfeld.test:1.0 (by /u/tester)";

        Fetched<List<Post>> result = client().oauth("client-id", userAgent).build().hotPosts("wallstreetbetsGER", 10);

        assertEquals(Route.OAUTH, result.route());
        FetchRequest tokenRequest = reddit.requests.get(0);
        assertEquals("POST", tokenRequest.method());
        assertEquals("Basic Y2xpZW50LWlkOg==", header(tokenRequest, "authorization"));
        String form = new String(tokenRequest.body(), StandardCharsets.UTF_8);
        assertTrue(form.startsWith("grant_type=https%3A%2F%2Foauth.reddit.com%2Fgrants%2Finstalled_client&device_id="));

        FetchRequest dataRequest = reddit.requests.get(1);
        assertEquals("bearer tok1", header(dataRequest, "authorization"));
        assertEquals(userAgent, header(dataRequest, "user-agent"));
        assertEquals("/r/wallstreetbetsGER/hot", dataRequest.uri().getPath());
    }

    @Test
    void oauthRenewsARejectedTokenOnce() throws Exception {
        List<String> tokens = new ArrayList<>(List.of("old", "new"));
        reddit.answer("www.reddit.com/api/v1/access_token", request -> ok(
                "{\"access_token\": \"" + tokens.removeFirst() + "\", \"expires_in\": 3600}", "application/json"));
        reddit.answer("oauth.reddit.com/", request -> header(request, "authorization").equals("bearer old")
                ? response(401, "{}", "application/json", Wall.NONE)
                : ok(RedditFixtures.LISTING, "application/json"));

        Fetched<List<Post>> result = client().oauth("id", "ua").build().newPosts("wallstreetbetsGER", 5);

        assertEquals(Route.OAUTH, result.route());
        assertEquals(4, reddit.requests.size(), "token, 401, new token, data");
    }

    @Test
    void withoutOauthConfiguredTheRouteDoesNotExist() throws Exception {
        reddit.answer("www.reddit.com/", ok(RedditFixtures.LISTING, "application/json"));
        assertEquals(Route.JSON, client().build().newPosts("wallstreetbetsGER", 5).route());
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
        assertEquals(Duration.ofSeconds(1), policies.get("oauth.reddit.com").minInterval());
    }

    // ---- fake ----------------------------------------------------------------

    /** A fetcher with a session side: unlocking records the call and plants the cookies. */
    private static final class SessionFetcher implements Fetcher, BrowserSession {
        final ScriptedFetcher http;
        final List<String> unlocks = new ArrayList<>();
        final java.util.Set<String> cookies = new java.util.HashSet<>();
        boolean captcha;

        SessionFetcher(ScriptedFetcher http) {
            this.http = http;
        }

        @Override
        public FetchResponse fetch(FetchRequest request) throws FetchException {
            return http.fetch(request);
        }

        @Override
        public boolean hasCookie(String host, String name) {
            return host.equals("www.reddit.com") && cookies.contains(name);
        }

        @Override
        public boolean canUnlock() {
            return true;
        }

        @Override
        public void unlock(String url, java.util.Set<String> awaitCookies) throws FetchException {
            unlocks.add(url + " " + awaitCookies);
            if (captcha) {
                throw new CaptchaRequiredException("www.reddit.com", "the page is a CAPTCHA and no one solved it");
            }
            cookies.addAll(awaitCookies);
        }
    }

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

        void answer(String prefix, Answer answer) {
            answers.put(prefix, answer);
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
        return new FetchResponse(status, URI.create("https://www.reddit.com/"), "HTTP/2",
                Map.of("content-type", List.of(contentType)), body.getBytes(StandardCharsets.UTF_8), wall, false);
    }

    private static String header(FetchRequest request, String name) {
        return request.headers().stream()
                .filter(header -> header.getKey().equals(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }
}
