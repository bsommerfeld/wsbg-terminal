package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reply-tree reconstruction from {@code parentId} (OAuth/.json) and graceful
 * collapse to a flat list when the source carries no parent linkage (RSS).
 */
class CommentTreeTest {

    /** A comment at {@code created} replying to {@code parentId}. */
    private static RedditComment c(String id, String parentId, long created) {
        long now = System.currentTimeMillis() / 1000;
        return new RedditComment(id, "t3_T", parentId, "user", "body of " + id, 0, created, now, now);
    }

    @Test
    void rootsAreTopLevelCommentsInChronologicalOrder() {
        // Two top-level comments (parent = the thread), out of input order.
        var tree = CommentTree.of(List.of(
                c("t1_b", "t3_T", 200),
                c("t1_a", "t3_T", 100)));
        assertEquals(List.of("t1_a", "t1_b"),
                tree.roots().stream().map(RedditComment::id).toList());
    }

    @Test
    void childrenHangUnderTheirParent() {
        // thesis (t1_thesis) ← OP question (t1_q) ← the pick (t1_pick)
        var tree = CommentTree.of(List.of(
                c("t1_thesis", "t3_T", 100),
                c("t1_q", "t1_thesis", 200),
                c("t1_pick", "t1_q", 300)));
        assertEquals(List.of("t1_thesis"), tree.roots().stream().map(RedditComment::id).toList());
        assertEquals(List.of("t1_q"), tree.childrenOf("t1_thesis").stream().map(RedditComment::id).toList());
        assertEquals(List.of("t1_pick"), tree.childrenOf("t1_q").stream().map(RedditComment::id).toList());
    }

    @Test
    void ancestorsAreRootFirstExcludingSelf() {
        var tree = CommentTree.of(List.of(
                c("t1_thesis", "t3_T", 100),
                c("t1_q", "t1_thesis", 200),
                c("t1_pick", "t1_q", 300)));
        // The pick answers the question, which answers the thesis: thesis first.
        assertEquals(List.of("t1_thesis", "t1_q"),
                tree.ancestorsOf("t1_pick").stream().map(RedditComment::id).toList());
        assertTrue(tree.ancestorsOf("t1_thesis").isEmpty(), "a root has no ancestors");
    }

    @Test
    void rssFlatCollapsesToAllRootsNoAncestors() {
        // RSS path: every comment's parentId is the thread id — no real tree.
        var tree = CommentTree.of(List.of(
                c("t1_x", "t3_T", 100),
                c("t1_y", "t3_T", 200),
                c("t1_z", "t3_T", 300)));
        assertEquals(3, tree.roots().size(), "all comments are roots");
        assertTrue(tree.childrenOf("t1_x").isEmpty());
        assertTrue(tree.ancestorsOf("t1_z").isEmpty(), "flat list has no ancestor chains");
    }

    @Test
    void cyclesAndSelfParentsDoNotLoop() {
        // Defensive: a comment whose parent points back at it must not hang.
        var tree = CommentTree.of(List.of(
                c("t1_self", "t1_self", 100),
                c("t1_a", "t1_b", 200),
                c("t1_b", "t1_a", 300)));
        assertTrue(tree.ancestorsOf("t1_self").isEmpty());
        // a↔b cycle: walking up terminates (bounded by the seen-set).
        assertTrue(tree.ancestorsOf("t1_a").size() <= 2);
    }
}
