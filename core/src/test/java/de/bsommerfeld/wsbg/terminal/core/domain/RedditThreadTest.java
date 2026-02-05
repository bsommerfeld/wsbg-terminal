package de.bsommerfeld.wsbg.terminal.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedditThreadTest {

    @Test
    void fullConstructor_shouldPreserveAllFields() {
        var thread = new RedditThread("t3_1", "wsb", "Title", "author", "body",
                1000L, "/r/wsb/1", 100, 0.95, 50, 2000L, "http://img.png");

        assertEquals("t3_1", thread.id());
        assertEquals("wsb", thread.subreddit());
        assertEquals("Title", thread.title());
        assertEquals("author", thread.author());
        assertEquals("body", thread.textContent());
        assertEquals(1000L, thread.createdUtc());
        assertEquals("/r/wsb/1", thread.permalink());
        assertEquals(100, thread.score());
        assertEquals(0.95, thread.upvoteRatio(), 0.001);
        assertEquals(50, thread.numComments());
        assertEquals(2000L, thread.lastActivityUtc());
        assertEquals("http://img.png", thread.imageUrl());
    }

    @Test
    void convenienceConstructor_twoArgsLess_shouldSetDefaultActivity() {
        var thread = new RedditThread("t3_1", "wsb", "Title", "author", "body",
                1000L, "/r/wsb/1", 100, 0.95, 50);

        // lastActivityUtc defaults to createdUtc
        assertEquals(1000L, thread.lastActivityUtc());
        assertNull(thread.imageUrl());
    }

    @Test
    void convenienceConstructor_noImage_shouldSetNullImage() {
        var thread = new RedditThread("t3_1", "wsb", "Title", "author", "body",
                1000L, "/r/wsb/1", 100, 0.95, 50, 2000L);

        assertEquals(2000L, thread.lastActivityUtc());
        assertNull(thread.imageUrl());
    }

    @Test
    void textContent_shouldAllowNull() {
        var thread = new RedditThread("t3_1", "wsb", "Title", "author", null,
                1000L, "/r/wsb/1", 100, 0.95, 50);

        assertNull(thread.textContent());
    }

    @Test
    void equality_shouldMatchOnAllFields() {
        var a = new RedditThread("t3_1", "wsb", "T", "a", "b", 1L, "/p", 0, 0.0, 0, 1L, null);
        var b = new RedditThread("t3_1", "wsb", "T", "a", "b", 1L, "/p", 0, 0.0, 0, 1L, null);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equality_shouldDifferOnScore() {
        var a = new RedditThread("t3_1", "wsb", "T", "a", "b", 1L, "/p", 0, 0.0, 0);
        var b = new RedditThread("t3_1", "wsb", "T", "a", "b", 1L, "/p", 99, 0.0, 0);

        assertNotEquals(a, b);
    }

    @Test
    void zeroScore_shouldBeValid() {
        var thread = new RedditThread("t3_1", "wsb", "T", "a", null, 1L, "/p", 0, 0.0, 0);
        assertEquals(0, thread.score());
    }
}
