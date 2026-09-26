package de.bsommerfeld.tinyreddit.mapping;

import de.bsommerfeld.tinyreddit.RedditFixtures;
import de.bsommerfeld.tinyreddit.model.Discussion;
import de.bsommerfeld.tinyreddit.model.Post;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtomMapperTest {

    @Test
    void listingFeedToPosts() throws Exception {
        List<Post> posts = AtomMapper.posts(AtomFeed.parse(RedditFixtures.LISTING_FEED), "wallstreetbetsGER");
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
        List<Post> posts = AtomMapper.posts(AtomFeed.parse(RedditFixtures.LISTING_FEED), "wallstreetbetsGER");
        assertEquals(List.of("https://i.redd.it/chart.png"), posts.get(1).imageUrls());
        assertEquals("bär", posts.get(1).author());
    }

    @Test
    void commentFeedToFlatDiscussion() throws Exception {
        Discussion discussion = AtomMapper.discussion(AtomFeed.parse(RedditFixtures.DISCUSSION_FEED), "wallstreetbetsGER");
        assertEquals("t3_a1", discussion.post().id());
        assertEquals(1, discussion.comments().size());
        assertEquals("t3_a1", discussion.comments().getFirst().parentId(), "Atom has no parent linkage");
        assertEquals("Mond!", discussion.comments().getFirst().body());
        assertEquals(1_782_345_700L, discussion.comments().getFirst().createdUtc(), "falls back to updated");
    }

    @Test
    void feedWithoutPostIsNoDiscussion() throws Exception {
        assertNull(AtomMapper.discussion(AtomFeed.parse("<feed xmlns=\"http://www.w3.org/2005/Atom\"/>"), "x"));
    }

    @Test
    void externalEntitiesAreRefused() {
        String hostile = """
                <?xml version="1.0"?>
                <!DOCTYPE feed [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <feed><entry><id>t3_x</id><title>&secret;</title></entry></feed>
                """;
        assertThrows(Exception.class, () -> AtomFeed.parse(hostile));
    }
}
