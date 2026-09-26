package de.bsommerfeld.tinyreddit.mapping;

import de.bsommerfeld.tinyreddit.RedditFixtures;
import de.bsommerfeld.tinyreddit.json.Json;
import de.bsommerfeld.tinyreddit.model.Comment;
import de.bsommerfeld.tinyreddit.model.Discussion;
import de.bsommerfeld.tinyreddit.model.Poll;
import de.bsommerfeld.tinyreddit.model.Post;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonMapperTest {

    private final List<Post> posts = JsonMapper.posts(Json.parse(RedditFixtures.LISTING));

    @Test
    void listingKeepsPostsOnlyInOrder() {
        assertEquals(List.of("t3_a1", "t3_b2", "t3_c3", "t3_d4", "t3_e5"),
                posts.stream().map(Post::id).toList());
    }

    @Test
    void selfPostCarriesEverything() {
        Post post = posts.getFirst();
        assertEquals("wallstreetbetsGER", post.subreddit());
        assertEquals("SAP über 250?", post.title());
        assertEquals("affe42", post.author());
        assertEquals("Calls gekauft, Hopium & Mondfahrt", post.body());
        assertNull(post.linkUrl());
        assertEquals(1_782_345_600L, post.createdUtc());
        assertEquals("/r/wallstreetbetsGER/comments/a1/sap_uber_250/", post.permalink());
        assertEquals(128, post.score());
        assertEquals(0.93, post.upvoteRatio());
        assertEquals(41, post.commentCount());
        assertTrue(post.imageUrls().isEmpty());
        assertTrue(post.pollIfAny().isEmpty());
    }

    @Test
    void imagePostKeepsItsLinkAndUnescapedImage() {
        Post post = posts.get(1);
        assertEquals("", post.body());
        assertEquals("https://i.redd.it/chart.png?width=640&s=x", post.linkUrl());
        assertEquals(List.of("https://i.redd.it/chart.png?width=640&s=x"), post.imageUrls());
        assertEquals("[deleted]", post.author());
    }

    @Test
    void galleryInDisplayOrderWithoutVideo() {
        assertEquals(List.of("https://preview.redd.it/m2.png", "https://preview.redd.it/m1.jpg?a=1&b=2"),
                posts.get(2).imageUrls());
    }

    @Test
    void crosspostInheritsTextAndImages() {
        Post post = posts.get(3);
        assertEquals("Original-Text", post.body());
        assertEquals(List.of("https://i.redd.it/orig.jpg"), post.imageUrls());
    }

    @Test
    void pollSkipsBlankOptionsAndNormalisesTheEnd() {
        Poll poll = posts.get(4).poll();
        assertEquals(List.of("rot", "grün"), poll.options().stream().map(Poll.Option::text).toList());
        assertEquals(27, poll.totalVotes());
        assertEquals(1_782_400_000L, poll.endsUtc());
    }

    @Test
    void discussionWalksTheTreeDepthFirst() {
        Discussion discussion = JsonMapper.discussion(Json.parse(RedditFixtures.DISCUSSION));
        assertEquals("t3_a1", discussion.post().id());

        List<Comment> comments = discussion.comments();
        assertEquals(List.of("t1_c1", "t1_c2", "t1_c4"), comments.stream().map(Comment::id).toList(),
                "removed comment skipped, its reply kept, 'more' stub skipped");
        assertEquals(List.of(0, 1, 1), comments.stream().map(Comment::depth).toList());
        assertEquals("t1_c1", comments.get(1).parentId());
        assertEquals("t3_a1", comments.get(1).postId());
        assertEquals(-3, comments.get(1).score());
    }

    @Test
    void commentImagesFromTextAndInlineMedia() {
        List<Comment> comments = JsonMapper.discussion(Json.parse(RedditFixtures.DISCUSSION)).comments();
        assertEquals(List.of("https://i.redd.it/rakete.gif"), comments.getFirst().imageUrls());
        assertEquals("Mond! https://i.redd.it/rakete.gif.", comments.getFirst().body(), "body stays as written");
        assertTrue(comments.get(2).imageUrls().isEmpty(), "no inline reference in the text, no image");
    }

    @Test
    void streamCommentsFindTheirPost() {
        List<Comment> comments = JsonMapper.streamComments(Json.parse(RedditFixtures.COMMENT_STREAM));
        assertEquals("t3_a1", comments.get(0).postId());
        assertEquals("t1_c1", comments.get(0).parentId());
        assertEquals("t3_q7", comments.get(1).postId(), "from the permalink when link_id is missing");
    }
}
