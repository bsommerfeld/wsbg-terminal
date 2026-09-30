package de.bsommerfeld.tinyreddit.mapping;

import de.bsommerfeld.tinyreddit.RedditFixtures;
import de.bsommerfeld.tinyreddit.model.Comment;
import de.bsommerfeld.tinyreddit.model.Discussion;
import de.bsommerfeld.tinyreddit.model.Post;
import de.bsommerfeld.tinyrss.api.FeedParser;
import de.bsommerfeld.tinyrss.model.Entry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RssMapperTest {

    private static final String REDDIT = "https://www.reddit.com/";

    private static List<Entry> entries(String feed) throws Exception {
        return FeedParser.parse(feed, REDDIT).entries();
    }

    @Test
    void listingFeedToPosts() throws Exception {
        List<Post> posts = RssMapper.posts(entries(RedditFixtures.LISTING_FEED), "wallstreetbetsGER");
        assertEquals(2, posts.size());

        Post first = posts.getFirst();
        assertEquals("t3_a1", first.id());
        assertEquals("affe42", first.author());
        assertEquals("Calls gekauft & Hopium", first.body(), "footer dropped, tags stripped, entities undone");
        assertEquals("/r/wallstreetbetsGER/comments/a1/sap_uber_250/", first.permalink());
        assertEquals(1_782_345_600L, first.createdUtc());
        assertEquals(0, first.score(), "Atom carries no score");
    }

    @Test
    void fullSizeImageWinsOverPreviewAndThumbnail() throws Exception {
        List<Post> posts = RssMapper.posts(entries(RedditFixtures.LISTING_FEED), "wallstreetbetsGER");
        assertEquals(List.of("https://i.redd.it/chart.png"), posts.get(1).imageUrls());
        assertEquals("bär", posts.get(1).author());
        assertEquals("", posts.get(1).body(), "an image post has no text before its footer");
    }

    @Test
    void commentFeedToFlatDiscussion() throws Exception {
        Discussion discussion = RssMapper.discussion(entries(RedditFixtures.DISCUSSION_FEED), "wallstreetbetsGER");
        assertEquals("t3_a1", discussion.post().id());
        assertEquals(1, discussion.comments().size());
        assertEquals("t3_a1", discussion.comments().getFirst().parentId(), "Atom has no parent linkage");
        assertEquals("Mond!", discussion.comments().getFirst().body());
        assertEquals(1_782_345_700L, discussion.comments().getFirst().createdUtc(), "falls back to updated");
    }

    @Test
    void streamCommentsFindTheirPostFromTheirLink() throws Exception {
        List<Comment> comments = RssMapper.streamComments(entries(RedditFixtures.DISCUSSION_FEED));
        assertEquals(1, comments.size(), "the post entry is no comment");
        assertEquals("t3_a1", comments.getFirst().postId());
        assertEquals("bulle", comments.getFirst().author());
    }

    @Test
    void aPostQuotingTheFooterKeepsItsWords() throws Exception {
        String feed = """
                <feed xmlns="http://www.w3.org/2005/Atom"><entry><id>t3_q</id><title>Q</title>
                <content type="html">&lt;p&gt;Wer hat das submitted by Bot gemeint?&lt;/p&gt; submitted by &lt;a href="https://www.reddit.com/user/x"&gt; /u/x &lt;/a&gt; &lt;a href="https://www.reddit.com/r/x/comments/q/"&gt;[link]&lt;/a&gt;</content>
                <link href="https://www.reddit.com/r/x/comments/q/q/"/></entry></feed>""";
        assertEquals("Wer hat das submitted by Bot gemeint?", RssMapper.posts(entries(feed), "x").getFirst().body());
    }

    @Test
    void feedWithoutPostIsNoDiscussion() throws Exception {
        assertNull(RssMapper.discussion(entries("<feed xmlns=\"http://www.w3.org/2005/Atom\"/>"), "x"));
    }
}
