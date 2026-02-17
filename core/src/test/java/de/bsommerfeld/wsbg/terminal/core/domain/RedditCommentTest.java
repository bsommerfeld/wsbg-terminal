package de.bsommerfeld.wsbg.terminal.core.domain;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedditCommentTest {

    @Test
    void canonicalConstructor_shouldGuardNullImageUrls() {
        var comment = new RedditComment("t1_1", "t3_1", "t3_1", "user",
                "body", 10, 1000L, 2000L, 3000L, null);

        assertNotNull(comment.imageUrls());
        assertTrue(comment.imageUrls().isEmpty());
    }

    @Test
    void canonicalConstructor_shouldPreserveProvidedImageUrls() {
        List<String> urls = List.of("http://img1.png", "http://img2.png");
        var comment = new RedditComment("t1_1", "t3_1", "t3_1", "user",
                "body", 10, 1000L, 2000L, 3000L, urls);

        assertEquals(2, comment.imageUrls().size());
        assertEquals("http://img1.png", comment.imageUrls().get(0));
    }

    @Test
    void convenienceConstructor_shouldSetEmptyImageUrls() {
        var comment = new RedditComment("t1_1", "t3_1", "t3_1", "user",
                "body", 10, 1000L, 2000L, 3000L);

        assertTrue(comment.imageUrls().isEmpty());
    }

    @Test
    void accessors_shouldReturnCorrectValues() {
        var comment = new RedditComment("t1_abc", "t3_xyz", "t3_xyz", "author",
                "comment body", 42, 100L, 200L, 300L, List.of("url1"));

        assertEquals("t1_abc", comment.id());
        assertEquals("t3_xyz", comment.threadId());
        assertEquals("t3_xyz", comment.parentId());
        assertEquals("author", comment.author());
        assertEquals("comment body", comment.body());
        assertEquals(42, comment.score());
        assertEquals(100L, comment.createdUtc());
        assertEquals(200L, comment.fetchedAt());
        assertEquals(300L, comment.lastUpdatedUtc());
        assertEquals(1, comment.imageUrls().size());
    }

    @Test
    void equality_shouldMatchOnAllFields() {
        var a = new RedditComment("t1_1", "t3_1", "t3_1", "u", "b", 1, 1L, 1L, 1L, Collections.emptyList());
        var b = new RedditComment("t1_1", "t3_1", "t3_1", "u", "b", 1, 1L, 1L, 1L, Collections.emptyList());

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equality_shouldDifferOnId() {
        var a = new RedditComment("t1_1", "t3_1", "t3_1", "u", "b", 1, 1L, 1L, 1L);
        var b = new RedditComment("t1_2", "t3_1", "t3_1", "u", "b", 1, 1L, 1L, 1L);

        assertNotEquals(a, b);
    }

    @Test
    void negativeScore_shouldBeAllowed() {
        var comment = new RedditComment("t1_1", "t3_1", "t3_1", "u", "b", -50, 1L, 1L, 1L);
        assertEquals(-50, comment.score());
    }
}
