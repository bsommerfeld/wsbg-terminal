package de.bsommerfeld.tinyrss.api;

import de.bsommerfeld.tinyfetch.api.CooldownException;
import de.bsommerfeld.tinyfetch.api.FetchException;
import de.bsommerfeld.tinyfetch.api.FetchRequest;
import de.bsommerfeld.tinyfetch.api.FetchResponse;
import de.bsommerfeld.tinyfetch.api.Fetcher;
import de.bsommerfeld.tinyfetch.api.Step;
import de.bsommerfeld.tinyfetch.api.Wall;
import de.bsommerfeld.tinyrss.FeedFixtures;
import de.bsommerfeld.tinyrss.model.Feed;
import de.bsommerfeld.tinyrss.model.FeedLink;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedReaderTest {

    private final List<FetchRequest> requests = new ArrayList<>();

    private FeedReader answering(FetchResponse response) {
        return new FeedReader(request -> {
            requests.add(request);
            return response;
        });
    }

    @Test
    void oneRequestAsTheBrowserMakesIt() throws Exception {
        String url = "https://www.tagesschau.de/wirtschaft/index~rss2.xml";
        Feed feed = answering(ok(FeedFixtures.RSS, "application/rss+xml; charset=utf-8", url)).read(url);

        assertEquals(2, feed.entries().size());
        assertEquals(1, requests.size());
        assertEquals(url, requests.getFirst().uri().toString());
        assertTrue(requests.getFirst().headers().isEmpty(), "no Accept of our own - some houses answer a strict one with 406");
        assertEquals(Set.of(Step.FETCH), requests.getFirst().steps(), "a feed answers the first request - no page before it");
    }

    @Test
    void aFeedBehindAVisitorCheckGetsTheStepsItNeeds() throws Exception {
        String url = "https://www.reddit.com/r/wallstreetbetsGER/new.rss";
        FeedReader reddit = new FeedReader(request -> {
            requests.add(request);
            return ok(FeedFixtures.ATOM, "application/atom+xml", url);
        }, Step.LOAD_PAGE, Step.READINESS_CHECK, Step.FETCH);

        reddit.read(url);
        assertEquals(Step.ALL, requests.getFirst().steps());
        assertThrows(IllegalArgumentException.class, () -> new FeedReader(request -> null, Step.LOAD_PAGE),
                "steps without a fetch are refused at once, not at the first read");
    }

    @Test
    void relativeLinksResolveAgainstWhereTheAnswerCameFrom() throws Exception {
        String rss = "<rss><channel><item><title>T</title><link>/artikel/1.html</link></item></channel></rss>";
        Feed feed = answering(ok(rss, "text/xml", "https://en.mercopress.com/rss/")).read("https://www.mercopress.com/rss/");
        assertEquals("https://en.mercopress.com/artikel/1.html", feed.entries().getFirst().link());
    }

    @Test
    void errorStatusesAndWallsAreRefusals() {
        FeedRefusedException gone = assertThrows(FeedRefusedException.class,
                () -> answering(response(404, "nope", "text/plain", Wall.NONE)).read("https://example.com/rss"));
        assertEquals(404, gone.status());

        FeedRefusedException walled = assertThrows(FeedRefusedException.class, () -> answering(
                response(200, "<title>Just a moment...</title>", "text/html", Wall.CHALLENGE)).read("https://example.com/rss"));
        assertEquals(Wall.CHALLENGE, walled.wall());
        assertEquals(200, walled.response().status());
    }

    @Test
    void aPausedHostIsTinyFetchsAnswer() {
        CooldownException paused = new CooldownException("example.com", Instant.parse("2026-09-30T14:00:00Z"), Wall.THROTTLED);
        Fetcher fetcher = request -> {
            throw paused;
        };
        assertSame(paused, assertThrows(CooldownException.class, () -> new FeedReader(fetcher).read("https://example.com/rss")));
    }

    @Test
    void aPageInsteadOfTheFeedIsNoFeed() {
        // börse-frankfurt's /rss answers its web app with a 200 (measured 2026-09-30)
        assertThrows(NotAFeedException.class, () -> answering(ok("<!DOCTYPE html><html><body>App</body></html>",
                "text/html", "https://live.deutsche-boerse.com/rss")).read("https://www.boerse-frankfurt.de/rss"));
    }

    @Test
    void discoverFindsTheFeedsOfAPage() throws Exception {
        String page = "<html><head><link rel=\"alternate\" type=\"application/rss+xml\" title=\"RSS\" href=\"/feed/\"></head></html>";
        List<FeedLink> found = answering(ok(page, "text/html", "https://www.prensa-latina.cu/"))
                .discover("https://www.prensa-latina.cu/");
        assertEquals(List.of(new FeedLink("https://www.prensa-latina.cu/feed/", "RSS", "application/rss+xml")), found);
    }

    @Test
    void failuresWithoutAnswerPassThrough() {
        FeedReader reader = new FeedReader(request -> {
            throw new FetchException("timeout");
        });
        assertThrows(FetchException.class, () -> reader.discover("https://example.com/"));
    }

    private static FetchResponse ok(String body, String contentType, String url) {
        return new FetchResponse(200, URI.create(url), Map.of("content-type", List.of(contentType)),
                body.getBytes(StandardCharsets.UTF_8), Wall.NONE);
    }

    private static FetchResponse response(int status, String body, String contentType, Wall wall) {
        return new FetchResponse(status, URI.create("https://example.com/rss"),
                Map.of("content-type", List.of(contentType)), body.getBytes(StandardCharsets.UTF_8), wall);
    }
}
