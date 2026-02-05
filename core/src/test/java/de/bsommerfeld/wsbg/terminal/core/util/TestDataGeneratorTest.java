package de.bsommerfeld.wsbg.terminal.core.util;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TestDataGeneratorTest {

    @Test
    void generateThread_shouldReturnNonNull() {
        RedditThread thread = TestDataGenerator.generateThread();
        assertNotNull(thread);
    }

    @Test
    void generateThread_shouldHaveT3Prefix() {
        RedditThread thread = TestDataGenerator.generateThread();
        assertTrue(thread.id().startsWith("t3_"));
    }

    @Test
    void generateThread_shouldHaveNonNullRequiredFields() {
        RedditThread thread = TestDataGenerator.generateThread();

        assertNotNull(thread.subreddit());
        assertNotNull(thread.title());
        assertNotNull(thread.author());
        assertNotNull(thread.permalink());
        assertTrue(thread.createdUtc() > 0);
    }

    @Test
    void generateThread_shouldProduceUniqueIds() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            ids.add(TestDataGenerator.generateThread().id());
        }
        assertEquals(50, ids.size());
    }

    @Test
    void generateThreads_shouldReturnCorrectCount() {
        List<RedditThread> threads = TestDataGenerator.generateThreads(10);
        assertEquals(10, threads.size());
    }

    @Test
    void generateThreads_shouldBeNewestFirst() {
        List<RedditThread> threads = TestDataGenerator.generateThreads(5);
        for (int i = 1; i < threads.size(); i++) {
            assertTrue(threads.get(i - 1).createdUtc() >= threads.get(i).createdUtc(),
                    "Threads should be sorted newest first");
        }
    }

    @Test
    void generateThreads_shouldSpreadTimestamps() {
        List<RedditThread> threads = TestDataGenerator.generateThreads(5);
        // First and last thread should differ in timestamp (24h spread)
        long oldest = threads.get(threads.size() - 1).createdUtc();
        long newest = threads.get(0).createdUtc();
        assertTrue(newest - oldest > 0, "Timestamps should be spread across time");
    }

    @Test
    void generateThreads_shouldReturnEmptyForZero() {
        List<RedditThread> threads = TestDataGenerator.generateThreads(0);
        assertTrue(threads.isEmpty());
    }

    @Test
    void generateCommentsRecursive_shouldReturnCorrectCount() {
        List<RedditComment> comments = TestDataGenerator.generateCommentsRecursive("t3_test", 15);
        assertEquals(15, comments.size());
    }

    @Test
    void generateCommentsRecursive_shouldHaveT1Prefix() {
        List<RedditComment> comments = TestDataGenerator.generateCommentsRecursive("t3_test", 5);
        for (RedditComment c : comments) {
            assertTrue(c.id().startsWith("t1_"));
        }
    }

    @Test
    void generateCommentsRecursive_shouldHaveMixOfRootAndReplyComments() {
        List<RedditComment> comments = TestDataGenerator.generateCommentsRecursive("t3_test", 20);

        // Some comments should have the thread as parent (root comments),
        // while others should reply to existing comments
        long rootComments = comments.stream()
                .filter(c -> c.parentId().equals("t3_test"))
                .count();
        long replyComments = comments.size() - rootComments;

        assertTrue(rootComments > 0, "Should have root comments");
        assertTrue(replyComments > 0, "Should have reply comments");
    }

    @Test
    void generateCommentsRecursive_shouldReturnAtLeastOneForZero() {
        // Math.max(1, count*0.4) always creates at least 1 root comment
        List<RedditComment> comments = TestDataGenerator.generateCommentsRecursive("t3_test", 0);
        assertEquals(1, comments.size());
    }

    @Test
    void generateCommentsRecursive_shouldReturnSingleForOne() {
        List<RedditComment> comments = TestDataGenerator.generateCommentsRecursive("t3_test", 1);
        assertEquals(1, comments.size());
    }
}
