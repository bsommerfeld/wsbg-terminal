package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;

import java.util.List;
import java.util.Set;

/**
 * Renders a thread's comments as the REPLY TREE the source preserved — conversation
 * order, replies indented under what they answer — with the fresh/covered/late-image
 * split. The densest recursion of {@link ReportBuilder}, extracted verbatim so the
 * report builder reads as top-level structure. Also owns the image-freshness checks
 * (a slow image finishing analysis after its carrier was covered), shared with the
 * builder's thread split.
 */
final class CommentTreeRenderer {

    private final RedditRepository repository;
    private final AgentBrain brain;

    CommentTreeRenderer(RedditRepository repository, AgentBrain brain) {
        this.repository = repository;
        this.brain = brain;
    }

    void render(StringBuilder sb, String threadId, Set<String> coveredCommentIds,
            Set<String> shown, BriefLabels lbl) {
        // Render the comments as the REPLY TREE the source preserved, in
        // conversation order, replies indented under what they answer — so a
        // pick named deep in a chain ("E.ON und Constellation") stays attached
        // to the thesis it responds to. (On RSS, where Reddit strips parent
        // linkage, every comment is a root and this collapses to a flat list.)
        // No count cap: WSBG comments are short, the covered-filter trims the
        // list across ticks, and the 1:1-sentiment mirror wants every voice.
        //
        // Uncovered comments render in full (text + all images). A COVERED
        // comment is normally suppressed — but if one of its images only just
        // finished analysing (cached, not yet shown), it re-surfaces image-only
        // and flagged, so a late gain-screenshot still reaches a headline; and a
        // covered comment that anchors fresh replies emits a thin context stub
        // so those replies don't read as orphans.
        List<RedditComment> all = repository.getCommentsForThread(threadId, 0);
        if (all.isEmpty())
            return;
        CommentTree tree = CommentTree.of(all);

        StringBuilder block = new StringBuilder();
        for (RedditComment root : tree.roots()) {
            renderCommentSubtree(block, root, 0, tree, coveredCommentIds, shown, lbl);
        }
        if (block.length() == 0)
            return;
        sb.append(lbl.relevantCommentsHeader());
        sb.append(block);
    }

    /**
     * Renders one comment and its reply subtree depth-first into {@code out},
     * returning {@code true} when anything was emitted. A comment renders when
     * it is fresh (full text + images) or carries a just-analysed image
     * (image-only). A covered comment with nothing fresh of its own still emits
     * a one-line context stub <em>if</em> a descendant rendered — so the reply
     * keeps the conversational anchor it answered.
     */
    private boolean renderCommentSubtree(StringBuilder out, RedditComment c, int depth,
            CommentTree tree, Set<String> coveredCommentIds, Set<String> shown, BriefLabels lbl) {
        String indent = "  ".repeat(depth);
        StringBuilder self = new StringBuilder();
        boolean selfEmitted = renderComment(self, c, indent, coveredCommentIds, shown, lbl);

        StringBuilder kids = new StringBuilder();
        boolean kidsEmitted = false;
        for (RedditComment child : tree.childrenOf(c.id())) {
            kidsEmitted |= renderCommentSubtree(kids, child, depth + 1, tree, coveredCommentIds, shown, lbl);
        }

        if (selfEmitted) {
            out.append(self).append(kids);
            return true;
        }
        if (kidsEmitted) {
            // Covered with nothing fresh, but a fresh reply hangs under it:
            // emit a thin, clearly-flagged anchor so the reply has context.
            String stub = c.body() == null ? "" : RedditAnonymizer.stripHandles(c.body());
            if (stub.length() > 140) stub = stub.substring(0, 140) + "…";
            out.append(indent).append("- [").append(c.id()).append("] ").append(RedditAnonymizer.ANON_AUTHOR)
                    .append(lbl.earlierCoveredTag()).append(stub).append("\n");
            out.append(kids);
            return true;
        }
        return false;
    }

    /**
     * Emits the single line(s) for {@code c} itself (no descendants), returning
     * whether anything was written. Fresh → full body + all images; covered with
     * a freshly-cached image → image-only and flagged; covered with nothing new
     * → nothing.
     */
    private boolean renderComment(StringBuilder out, RedditComment c, String indent,
            Set<String> coveredCommentIds, Set<String> shown, BriefLabels lbl) {
        if (!coveredCommentIds.contains(c.id())) {
            String body = RedditAnonymizer.stripHandles(c.body() != null ? c.body() : "[deleted]");
            out.append(indent).append("- [").append(c.id()).append("] ");
            out.append(RedditAnonymizer.ANON_AUTHOR).append(" (").append(lbl.scoreTag(c.score())).append("): ")
                    .append(body).append("\n");
            appendCommentImages(out, c, shown, false, indent, lbl);
            return true;
        } else if (hasUnshownCachedImage(c.imageUrls(), shown)) {
            out.append(indent).append("- [").append(c.id()).append("] ").append(RedditAnonymizer.ANON_AUTHOR)
                    .append(lbl.newImageEvidence());
            appendCommentImages(out, c, shown, true, indent, lbl);
            return true;
        }
        return false;
    }

    /**
     * Renders cached vision descriptions for ALL of a comment's images, one
     * line each — including downvoted comments, because their screenshots are
     * sentiment too (often by inversion). Cache-only: vision happens
     * asynchronously in {@code PassiveMonitorService.prefetchCommentImages},
     * so a cold image is silently skipped and re-surfaces in a later tick once
     * it settles. Each rendered URL is marked in {@code shown}; when
     * {@code onlyNew} is set, already-shown images are skipped so only the
     * freshly-analysed ones print.
     */
    private void appendCommentImages(StringBuilder sb, RedditComment c, Set<String> shown,
            boolean onlyNew, String indent, BriefLabels lbl) {
        if (c.imageUrls().isEmpty()) return;
        for (String url : c.imageUrls()) {
            if (onlyNew && shown.contains(url)) continue;
            String desc = brain.describeImageIfCached(url);
            if (desc.isEmpty()) continue;
            sb.append(indent).append(lbl.commentImage()).append(desc).append("\n");
            shown.add(url);
        }
    }

    /**
     * True if any URL has a cached vision description that hasn't been shown
     * to the agent yet — i.e. a slow image finished analysing after the
     * carrier was covered. This is the trigger that re-surfaces late image
     * evidence instead of losing it.
     */
    boolean hasUnshownCachedImage(List<String> urls, Set<String> shown) {
        if (urls == null) return false;
        for (String url : urls) {
            if (shown.contains(url)) continue;
            if (!brain.describeImageIfCached(url).isEmpty()) return true;
        }
        return false;
    }

    /** True if any comment of the thread carries an unshown, now-cached image. */
    boolean threadHasCommentWithUnshownImage(String threadId, Set<String> shown) {
        for (RedditComment c : repository.getCommentsForThread(threadId, 0)) {
            if (hasUnshownCachedImage(c.imageUrls(), shown)) return true;
        }
        return false;
    }

    /** True if the thread has at least one comment not already cited in a prior headline. */
    boolean hasUncoveredComments(String threadId, Set<String> coveredCommentIds) {
        List<RedditComment> all = repository.getCommentsForThread(threadId, 0);
        for (RedditComment c : all) {
            if (!coveredCommentIds.contains(c.id())) return true;
        }
        return false;
    }
}
