package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconstructs the reply tree of one thread's comments from each comment's
 * {@code parentId}. A comment's parent is another comment when {@code parentId}
 * matches a known comment id ({@code t1_…}); otherwise the parent is the thread
 * itself ({@code t3_…}) — or simply unknown — and the comment is a <b>root</b>
 * (top-level reply to the post).
 *
 * <p>This is the structure the OAuth/{@code .json} sources preserve and the
 * editorial pipeline had been throwing away: a pick named deep in a reply chain
 * ("E.ON und Constellation") only makes sense as the <em>answer</em> to the
 * thesis it hangs under ("Potential im Energiebereich…"). {@link #ancestorsOf}
 * recovers that conversation, {@link #roots}/{@link #childrenOf} let a renderer
 * walk it in conversation order.
 *
 * <p><b>RSS degrades gracefully.</b> The RSS source can't carry parent linkage
 * (Reddit strips it from Atom), so every comment arrives with
 * {@code parentId == threadId}. Here that means every comment is a root, no
 * comment has children, and {@link #ancestorsOf} is always empty — i.e. a flat
 * list, exactly what RSS can honestly offer.
 *
 * <p>Sibling order is chronological (oldest first), so a thread reads top-down
 * the way the conversation actually unfolded. Community score is deliberately
 * <em>not</em> an ordering key here — it's sentiment carried per line, never a
 * reason to tear a reply away from what it answers.
 */
final class CommentTree {

    private final Map<String, RedditComment> byId;
    private final Map<String, List<RedditComment>> children;
    private final List<RedditComment> roots;

    private CommentTree(Map<String, RedditComment> byId,
            Map<String, List<RedditComment>> children, List<RedditComment> roots) {
        this.byId = byId;
        this.children = children;
        this.roots = roots;
    }

    /** Builds the tree from a thread's comments (order of the input is irrelevant). */
    static CommentTree of(List<RedditComment> comments) {
        Map<String, RedditComment> byId = new HashMap<>();
        for (RedditComment c : comments) {
            if (c.id() != null) byId.put(c.id(), c);
        }

        Comparator<RedditComment> byTime = Comparator
                .comparingLong(RedditComment::createdUtc)
                .thenComparing(c -> c.id() == null ? "" : c.id());

        Map<String, List<RedditComment>> children = new HashMap<>();
        List<RedditComment> roots = new ArrayList<>();
        for (RedditComment c : comments) {
            String parent = c.parentId();
            boolean parentIsComment = parent != null
                    && !parent.equals(c.id())
                    && byId.containsKey(parent);
            if (parentIsComment) {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(c);
            } else {
                roots.add(c);
            }
        }
        roots.sort(byTime);
        for (List<RedditComment> sibs : children.values()) sibs.sort(byTime);
        return new CommentTree(byId, children, roots);
    }

    /** Top-level comments (direct replies to the post), oldest first. */
    List<RedditComment> roots() {
        return roots;
    }

    /** Direct replies to the given comment, oldest first (empty when none). */
    List<RedditComment> childrenOf(String commentId) {
        return children.getOrDefault(commentId, List.of());
    }

    /**
     * The chain of comments {@code commentId} is a reply to, <b>root-first</b>
     * (the oldest ancestor — the thesis — first, the immediate parent last),
     * excluding the comment itself. The thread root ({@code t3_…}) is not a
     * comment, so it never appears. Empty when the comment is top-level or its
     * parent isn't in this thread's set (the RSS-flat case).
     */
    List<RedditComment> ancestorsOf(String commentId) {
        LinkedList<RedditComment> chain = new LinkedList<>();
        Set<String> seen = new HashSet<>();
        RedditComment cur = byId.get(commentId);
        while (cur != null && seen.add(cur.id())) {
            RedditComment parent = byId.get(cur.parentId());
            if (parent == null || parent == cur) break;
            chain.addFirst(parent);
            cur = parent;
        }
        return chain;
    }
}
