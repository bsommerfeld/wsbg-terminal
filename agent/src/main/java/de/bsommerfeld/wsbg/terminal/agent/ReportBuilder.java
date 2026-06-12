package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.PollData;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds AI-ready context reports from an {@link InvestigationCluster}
 * by fetching thread/comment data and performing optional vision analysis.
 */
public final class ReportBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(ReportBuilder.class);

    /**
     * Reddit handles carry zero content signal and actively hurt: a username
     * like {@code NASX_Trader} gets mis-read by the subject extractor as a
     * ticker ({@code $NASX}). So every author handle the model sees is masked.
     * Sources are cited by comment ID ({@code t1_…}), never by name, so nothing
     * is lost. The author field is replaced wholesale; {@code u/…} mentions
     * inside comment/post text are scrubbed by {@link #stripHandles}.
     */
    private static final String ANON_AUTHOR = "[user]";
    private static final java.util.regex.Pattern HANDLE =
            java.util.regex.Pattern.compile("(?i)(?<![A-Za-z0-9])/?u/[A-Za-z0-9_-]{3,20}");

    private final RedditRepository repository;
    private final AgentBrain brain;

    public ReportBuilder(RedditRepository repository, AgentBrain brain) {
        this.repository = repository;
        this.brain = brain;
    }

    /** Convenience overload used when no prior-headline context is available. */
    public String buildReportData(InvestigationCluster inv) {
        return buildReportData(inv, Collections.emptyList());
    }

    /**
     * Builds a structured text block containing cluster metadata, thread content,
     * comments, and vision analysis for up to 3 source threads. This is the raw
     * evidence the AI uses to assess the cluster.
     *
     * <p>
     * When {@code priorHeadlines} is non-empty the report flags every thread
     * and comment that was already cited as a source in a prior headline with
     * a {@code [✓ HEADLINE n]} marker. A "PRIOR HEADLINES" block lists those
     * headlines numbered chronologically, so the agent can see which evidence
     * has been covered and which is genuinely new — and either skip or write
     * a follow-up that addresses only the new material.
     */
    public String buildReportData(InvestigationCluster inv, List<HeadlineRecord> priorHeadlines) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- CASE ID: ").append(inv.id).append(" ---\n");
        sb.append("Cluster Topic: ").append(inv.initialTitle).append("\n");
        sb.append("Cluster Age: ").append(formatAge(inv.threadCreatedUTC)).append("\n");
        sb.append("Active Threads: ").append(inv.activeThreadIds.size()).append("\n");
        if (!inv.tickers.isEmpty()) {
            sb.append("Tickers seen: ").append(inv.tickers).append("\n");
        }

        // Index sources used by prior headlines so we can tell which
        // threads/comments are already covered and must NOT seed a new
        // headline. The PRIOR HEADLINES block is the only context the
        // agent gets about that covered material — the evidence itself is
        // omitted below, so the agent can only build headlines out of
        // genuinely new threads/comments. Every new headline is therefore
        // a de-facto follow-up by construction; we never have to label one
        // explicitly.
        Set<String> coveredThreadIds = new HashSet<>();
        Set<String> coveredCommentIds = new HashSet<>();
        if (!priorHeadlines.isEmpty()) {
            sb.append("\nPRIOR HEADLINES FOR THIS CLUSTER (chronological — already published, do NOT repeat):\n");
            for (int i = 0; i < priorHeadlines.size(); i++) {
                HeadlineRecord h = priorHeadlines.get(i);
                long secsAgo = Instant.now().getEpochSecond() - h.createdAt();
                sb.append("  H").append(i + 1)
                        .append(" [").append(formatRelativeSeconds(secsAgo)).append(" ago]: ")
                        .append(h.headline()).append("\n");
                if (h.sourceThreadIds() != null) coveredThreadIds.addAll(h.sourceThreadIds());
                if (h.sourceCommentIds() != null) coveredCommentIds.addAll(h.sourceCommentIds());
            }
            sb.append("Only the FRESH evidence below (threads/comments not yet cited above) is shown "
                    + "in full. If it carries new information, publish a follow-up. If nothing fresh "
                    + "is shown, this cluster has nothing new — move on to the next dirty cluster.\n");
        }
        sb.append("\n");

        appendThreadSources(sb, inv, coveredThreadIds, coveredCommentIds, priorHeadlines);

        sb.append("-----------------------------\n\n");
        return sb.toString();
    }

    /**
     * Renders the cluster's thread evidence, split into "fresh" and
     * "already covered". A thread is fresh when the thread itself was never
     * cited in a prior headline, OR it has at least one comment that wasn't
     * cited — so a covered thread that picked up new comments still surfaces
     * (with only its new comments). Fresh threads render in full, newest
     * first. No cap: the covered-evidence split already shrinks the fresh
     * set to "what's actually new", and the per-thread comment limit (25)
     * bounds each block — a single report hitting the model's context window
     * would need a record-breaking simultaneous thread burst.
     *
     * <p>Covered threads with no fresh material collapse to a one-line
     * reference showing the headline the agent ALREADY wrote from them — so
     * it recognises "I've said this" rather than re-deriving it from a raw
     * Reddit title.
     */
    private void appendThreadSources(StringBuilder sb, InvestigationCluster inv,
            Set<String> coveredThreadIds, Set<String> coveredCommentIds,
            List<HeadlineRecord> priorHeadlines) {
        // Map each covered thread to the most recent headline that cited it.
        // priorHeadlines is chronological, so a later entry overwrites an
        // earlier one — leaving the freshest headline per thread.
        Map<String, String> threadToHeadline = new java.util.HashMap<>();
        for (HeadlineRecord h : priorHeadlines) {
            if (h.sourceThreadIds() == null) continue;
            for (String tid : h.sourceThreadIds()) {
                threadToHeadline.put(tid, h.headline());
            }
        }

        Set<String> shown = inv.shownImageUrls;

        Set<String> ids = new HashSet<>(inv.activeThreadIds);
        if (inv.bestThreadId != null) ids.add(inv.bestThreadId);

        List<RedditThread> fresh = new ArrayList<>();
        List<RedditThread> covered = new ArrayList<>();
        for (String threadId : ids) {
            try {
                RedditThread thread = repository.getThread(threadId);
                if (thread == null) continue;
                // A thread is fresh — worth a full/partial render — when it
                // isn't covered, OR it has uncovered comments, OR a slow image
                // (thread slide or comment image) only just finished analysing
                // since it was covered. That last case is what stops a late
                // gain-screenshot from being lost behind the coverage filter.
                boolean threadCovered = coveredThreadIds.contains(threadId);
                boolean freshMaterial = !threadCovered
                        || hasUncoveredComments(threadId, coveredCommentIds)
                        || hasUnshownCachedImage(thread.imageUrls(), shown)
                        || threadHasCommentWithUnshownImage(threadId, shown);
                if (freshMaterial) {
                    fresh.add(thread);
                } else {
                    covered.add(thread);
                }
            } catch (Exception e) {
                LOG.warn("Failed to fetch context for {}", threadId);
            }
        }

        // Newest first — the post that just triggered this tick is the one
        // the agent most needs to see at the top.
        fresh.sort(Comparator.comparingLong(RedditThread::createdUtc).reversed());

        int idx = 1;
        for (RedditThread thread : fresh) {
            boolean threadCovered = coveredThreadIds.contains(thread.id());
            sb.append("=== THREAD SOURCE ").append(idx++)
                    .append(threadCovered ? " [✓ POST COVERED — only new comments/images below]" : "")
                    .append(" ===\n");
            sb.append("ID: ").append(thread.id()).append("\n");
            sb.append("Title: ").append(thread.title()).append("\n");
            if (isCommunityQuestion(thread)) {
                sb.append("POST TYPE: COMMUNITY QUESTION — the title is a prompt;"
                        + " the real signal lives in the comments below.\n");
            }
            // A covered post's body already fed a headline; show only its NEW
            // material (late images + fresh comments). A fresh post shows its
            // body + all images + poll too.
            if (!threadCovered) {
                appendImages(sb, thread.imageUrls(), shown, false);
                appendTextSnippet(sb, thread.textContent());
                appendPollIfPresent(sb, thread.pollData());
            } else {
                appendImages(sb, thread.imageUrls(), shown, true);
            }
            appendComments(sb, thread.id(), coveredCommentIds, shown);
            sb.append("\n");
        }

        if (!covered.isEmpty()) {
            sb.append("ALREADY COVERED (you already published these — do NOT repeat them):\n");
            for (RedditThread thread : covered) {
                String headline = threadToHeadline.get(thread.id());
                sb.append("  - [").append(thread.id()).append("] ");
                if (headline != null && !headline.isBlank()) {
                    sb.append("→ „").append(headline).append("\"");
                } else {
                    sb.append(thread.title());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
    }

    /** True if the thread has at least one comment not already cited in a prior headline. */
    private boolean hasUncoveredComments(String threadId, Set<String> coveredCommentIds) {
        List<RedditComment> all = repository.getCommentsForThread(threadId, 0);
        for (RedditComment c : all) {
            if (!coveredCommentIds.contains(c.id())) return true;
        }
        return false;
    }

    /**
     * True if any URL has a cached vision description that hasn't been shown
     * to the agent yet — i.e. a slow image finished analysing after the
     * carrier was covered. This is the trigger that re-surfaces late image
     * evidence instead of losing it.
     */
    private boolean hasUnshownCachedImage(List<String> urls, Set<String> shown) {
        if (urls == null) return false;
        for (String url : urls) {
            if (shown.contains(url)) continue;
            if (!brain.describeImageIfCached(url).isEmpty()) return true;
        }
        return false;
    }

    /** True if any comment of the thread carries an unshown, now-cached image. */
    private boolean threadHasCommentWithUnshownImage(String threadId, Set<String> shown) {
        for (RedditComment c : repository.getCommentsForThread(threadId, 0)) {
            if (hasUnshownCachedImage(c.imageUrls(), shown)) return true;
        }
        return false;
    }

    private static String formatRelativeSeconds(long secs) {
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m";
        return (secs / 3600) + "h " + ((secs % 3600) / 60) + "m";
    }

    /**
     * Renders the vision descriptions for a thread's images, cache-only —
     * this never blocks getCluster on vision. Slide 1 (and the first few
     * gallery slides) are warm from the clustering embedding; deeper slides
     * land asynchronously and appear once ready. Each rendered URL is marked
     * in {@code shown} so a later tick won't re-surface it. When
     * {@code onlyNew} is set (a covered post being re-surfaced), already-shown
     * slides are skipped — only the freshly-analysed ones print.
     */
    private void appendImages(StringBuilder sb, List<String> imageUrls, Set<String> shown, boolean onlyNew) {
        if (imageUrls == null || imageUrls.isEmpty())
            return;
        int total = imageUrls.size();
        for (int i = 0; i < total; i++) {
            String url = imageUrls.get(i);
            if (onlyNew && shown.contains(url)) continue;
            String desc = brain.describeImageIfCached(url);
            if (desc.isEmpty()) continue;
            String label = total == 1 ? "[IMAGE ANALYSIS]"
                    : "[IMAGE " + (i + 1) + "/" + total + " ANALYSIS]";
            if (onlyNew) label = "[NEW IMAGE " + (i + 1) + "/" + total
                    + " — analysed since the last headline]";
            sb.append(label).append(": ").append(desc).append("\n");
            shown.add(url);
        }
    }

    private void appendTextSnippet(StringBuilder sb, String text) {
        if (text == null || text.isEmpty())
            return;
        String clean = stripHandles(text);
        String snippet = clean.length() > 500 ? clean.substring(0, 500) + "..." : clean;
        sb.append("Content Snippet: ").append(snippet).append("\n");
    }

    /** Replaces {@code u/handle} / {@code /u/handle} mentions in free text with {@link #ANON_AUTHOR}. */
    private static String stripHandles(String text) {
        if (text == null || text.isEmpty()) return text;
        return HANDLE.matcher(text).replaceAll(ANON_AUTHOR);
    }

    /**
     * Renders the Reddit poll attached to a thread as a single-line vote
     * distribution. Polls are high-signal sentiment data the agent must
     * read — a 27-vote split at one-minute-old beats waiting for
     * comments. We tag the block POLL so the agent can spot it in the
     * report; the trailing „(LIVE)" / „(ENDED)" marker keeps the
     * temporal context obvious.
     */
    private void appendPollIfPresent(StringBuilder sb, PollData poll) {
        if (poll == null || poll.options() == null || poll.options().isEmpty()) return;
        sb.append("POLL: ");
        boolean first = true;
        for (PollData.PollOption opt : poll.options()) {
            if (!first) sb.append(" · ");
            sb.append(opt.text()).append(" ").append(opt.voteCount());
            first = false;
        }
        sb.append("  (total ").append(poll.totalVoteCount());
        long now = Instant.now().getEpochSecond();
        if (poll.votingEndsAtEpoch() > 0) {
            sb.append(poll.votingEndsAtEpoch() > now ? ", LIVE" : ", ENDED");
        }
        sb.append(")\n");
    }

    private void appendComments(StringBuilder sb, String threadId, Set<String> coveredCommentIds,
            Set<String> shown) {
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
            renderCommentSubtree(block, root, 0, tree, coveredCommentIds, shown);
        }
        if (block.length() == 0)
            return;
        sb.append("RELEVANT COMMENTS (fresh + new image evidence, in conversation order — "
                + "replies are indented under the comment they answer):\n");
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
            CommentTree tree, Set<String> coveredCommentIds, Set<String> shown) {
        String indent = "  ".repeat(depth);
        StringBuilder self = new StringBuilder();
        boolean selfEmitted = renderComment(self, c, indent, coveredCommentIds, shown);

        StringBuilder kids = new StringBuilder();
        boolean kidsEmitted = false;
        for (RedditComment child : tree.childrenOf(c.id())) {
            kidsEmitted |= renderCommentSubtree(kids, child, depth + 1, tree, coveredCommentIds, shown);
        }

        if (selfEmitted) {
            out.append(self).append(kids);
            return true;
        }
        if (kidsEmitted) {
            // Covered with nothing fresh, but a fresh reply hangs under it:
            // emit a thin, clearly-flagged anchor so the reply has context.
            String stub = c.body() == null ? "" : stripHandles(c.body());
            if (stub.length() > 140) stub = stub.substring(0, 140) + "…";
            out.append(indent).append("- [").append(c.id()).append("] ").append(ANON_AUTHOR)
                    .append(" [earlier — already covered]: ").append(stub).append("\n");
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
            Set<String> coveredCommentIds, Set<String> shown) {
        if (!coveredCommentIds.contains(c.id())) {
            String body = stripHandles(c.body() != null ? c.body() : "[deleted]");
            String scoreTag = c.score() < 0
                    ? "Score: " + c.score() + " — downvoted by the crowd"
                    : "Score: " + c.score();
            out.append(indent).append("- [").append(c.id()).append("] ");
            out.append(ANON_AUTHOR).append(" (").append(scoreTag).append("): ")
                    .append(body).append("\n");
            appendCommentImages(out, c, shown, false, indent);
            return true;
        } else if (hasUnshownCachedImage(c.imageUrls(), shown)) {
            out.append(indent).append("- [").append(c.id()).append("] ").append(ANON_AUTHOR)
                    .append(" [new image evidence since the last headline]:\n");
            appendCommentImages(out, c, shown, true, indent);
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
            boolean onlyNew, String indent) {
        if (c.imageUrls().isEmpty()) return;
        for (String url : c.imageUrls()) {
            if (onlyNew && shown.contains(url)) continue;
            String desc = brain.describeImageIfCached(url);
            if (desc.isEmpty()) continue;
            sb.append(indent).append("    [COMMENT IMAGE]: ").append(desc).append("\n");
            shown.add(url);
        }
    }

    /**
     * A thread counts as a community question when the title carries the
     * question mark and the body is too thin to stand on its own — the post
     * is a prompt, the answers live in the comments.
     */
    private boolean isCommunityQuestion(RedditThread thread) {
        String title = thread.title();
        String text = thread.textContent();
        boolean isQuestion = title != null && title.contains("?");
        boolean thinBody = text == null || text.trim().length() < 120;
        return isQuestion && thinBody;
    }

    private String formatAge(long createdUTC) {
        if (createdUTC <= 0)
            return "Unknown";
        long minutes = Duration.between(Instant.ofEpochSecond(createdUTC), Instant.now()).toMinutes();
        return String.format("%dh %dm", minutes / 60, minutes % 60);
    }
}
