package de.bsommerfeld.wsbg.terminal.reddit.support;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sub-wide comment stream's one hard requirement: every comment must find
 * its parent thread from its own link, with no extra request. The fixtures are
 * verbatim shapes from the live feeds (probed 2026-08-10).
 */
class CommentStreamTest {

    @Test
    @DisplayName("recovers thread id, subreddit and thread permalink from a comment URL")
    void parsesAbsoluteCommentUrl() {
        CommentStream.Parent p = CommentStream.parentOf(
                "https://www.reddit.com/r/wallstreetbets/comments/1vkgk1x/"
                        + "daily_discussion_thread_for_august_10_2026/p2v8nvs/");

        assertNotNull(p);
        assertEquals("t3_1vkgk1x", p.threadId());
        assertEquals("wallstreetbets", p.subreddit());
        assertEquals("/r/wallstreetbets/comments/1vkgk1x/"
                + "daily_discussion_thread_for_august_10_2026/", p.permalink());
    }

    @Test
    @DisplayName("accepts a bare path as the JSON permalink field delivers it")
    void parsesBarePath() {
        CommentStream.Parent p = CommentStream.parentOf(
                "/r/wallstreetbetsGER/comments/1vkqcb6/ich_will_auch_entschaedigung/p2v9kn9/");

        assertNotNull(p);
        assertEquals("t3_1vkqcb6", p.threadId());
        assertEquals("wallstreetbetsGER", p.subreddit());
    }

    @Test
    @DisplayName("survives a non-ASCII slug — German titles carry umlauts")
    void parsesUmlautSlug() {
        CommentStream.Parent p = CommentStream.parentOf(
                "https://www.reddit.com/r/wallstreetbetsGER/comments/1vkqcb6/"
                        + "ich_will_auch_entschädigung/p2v9kn9/");

        assertNotNull(p);
        assertEquals("t3_1vkqcb6", p.threadId());
    }

    @Test
    @DisplayName("guesses nothing when the link carries no comment path")
    void rejectsForeignLinks() {
        assertNull(CommentStream.parentOf(null));
        assertNull(CommentStream.parentOf(""));
        assertNull(CommentStream.parentOf("https://www.reddit.com/user/someone"));
        assertNull(CommentStream.parentOf("https://example.com/r/x/comments/"));
    }

    @Test
    @DisplayName("splits the Atom entry title at the first ' on ' — usernames have no spaces")
    void extractsThreadTitle() {
        assertEquals("Daily Discussion Thread for August 10, 2026",
                CommentStream.threadTitleOf(
                        "/u/znightmaree on Daily Discussion Thread for August 10, 2026"));
        // A thread title that itself contains " on " keeps its remainder intact.
        assertEquals("Betting on GME again",
                CommentStream.threadTitleOf("/u/someone on Betting on GME again"));
        assertNull(CommentStream.threadTitleOf("no separator here"));
        assertNull(CommentStream.threadTitleOf(null));
    }

    @Test
    @DisplayName("stub thread carries only what the stream knows — no fabricated numbers")
    void stubCarriesNoFakeMeasurements() {
        CommentStream.Parent p = CommentStream.parentOf(
                "/r/wallstreetbets/comments/1vkgk1x/daily_discussion_thread/p2v8nvs/");
        RedditThread stub = CommentStream.stubThread(p, "Daily Discussion Thread", 1_770_000_000L);

        assertEquals("t3_1vkgk1x", stub.id());
        assertEquals("wallstreetbets", stub.subreddit());
        assertEquals("Daily Discussion Thread", stub.title());
        assertEquals(1_770_000_000L, stub.createdUtc());
        // Score and comment count belong to the listing scan; a stub must not
        // invent them, or a fabricated zero reads as a measurement downstream.
        assertEquals(0, stub.score());
        assertEquals(0, stub.numComments());
        assertTrue(stub.imageUrls().isEmpty());
    }

    @Test
    @DisplayName("stub falls back to the thread id when no title came through")
    void stubWithoutTitle() {
        CommentStream.Parent p = CommentStream.parentOf(
                "/r/wallstreetbets/comments/1vkgk1x/slug/p2v8nvs/");
        assertEquals("t3_1vkgk1x", CommentStream.stubThread(p, null, 1L).title());
    }
}
