package de.bsommerfeld.wsbg.terminal.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.core.domain.PollData;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditSnapshotStore.RedditSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pure serialization round-trip for the snapshot payload — no filesystem. The
 * real risk in {@link RedditSnapshotStore} is whether Jackson can round-trip
 * the domain records (lists, a nullable nested {@link PollData}, records with a
 * second convenience constructor); this nails that down deterministically.
 */
class RedditSnapshotSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTrip_preservesThreadsCommentsImagesAndPoll() throws Exception {
        PollData poll = new PollData(
                List.of(new PollData.PollOption("a", "rot", 13),
                        new PollData.PollOption("b", "grün", 11)),
                24, 1779944000L);

        RedditThread imagePost = new RedditThread(
                "t3_abc", "wallstreetbetsGER", "NOW Rakete 🚀", "u/kartoffel",
                "Body mit Umlaut: höher", 1780000000L, "/r/wallstreetbetsGER/comments/abc/now/",
                0, 0.0, 0, 1780000500L,
                List.of("https://i.redd.it/x.jpeg", "https://i.redd.it/y.png"), null);
        RedditThread pollPost = new RedditThread(
                "t3_def", "wallstreetbetsGER", "Umfrage", "u/anon",
                "", 1780000100L, "/r/wallstreetbetsGER/comments/def/umfrage/",
                0, 0.0, 0, 1780000100L, List.of(), poll);

        RedditComment comment = new RedditComment(
                "t1_c1", "t3_abc", "t3_abc", "segasonic66", "ja chef", 0,
                1780000200L, 1780000300L, 1780000300L,
                List.of("https://i.redd.it/z.gif"));

        RedditSnapshot original = new RedditSnapshot(
                1780000600L, List.of(imagePost, pollPost), List.of(comment));

        String json = mapper.writeValueAsString(original);
        RedditSnapshot restored = mapper.readValue(json, RedditSnapshot.class);

        assertEquals(1780000600L, restored.savedAtEpochSeconds());
        assertEquals(2, restored.threads().size());
        assertEquals(1, restored.comments().size());

        RedditThread rt = restored.threads().get(0);
        assertEquals("t3_abc", rt.id());
        assertEquals("NOW Rakete 🚀", rt.title());
        assertEquals("Body mit Umlaut: höher", rt.textContent());
        assertEquals(2, rt.imageUrls().size());
        assertEquals("https://i.redd.it/x.jpeg", rt.imageUrls().get(0));

        RedditThread rp = restored.threads().get(1);
        assertNotNull(rp.pollData());
        assertEquals(2, rp.pollData().options().size());
        assertEquals("grün", rp.pollData().options().get(1).text());
        assertEquals(24, rp.pollData().totalVoteCount());

        RedditComment rc = restored.comments().get(0);
        assertEquals("t1_c1", rc.id());
        assertEquals("segasonic66", rc.author());
        assertEquals("ja chef", rc.body());
        assertEquals(1, rc.imageUrls().size());
    }
}
