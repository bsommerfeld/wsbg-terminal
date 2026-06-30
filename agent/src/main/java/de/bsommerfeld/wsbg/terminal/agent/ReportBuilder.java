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

    /** English (default/fallback) report — language-neutral callers and tests. */
    public String buildReportData(InvestigationCluster inv, List<HeadlineRecord> priorHeadlines) {
        return buildReportData(inv, priorHeadlines, "en");
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
    public String buildReportData(InvestigationCluster inv, List<HeadlineRecord> priorHeadlines, String langCode) {
        BriefLabels lbl = BriefLabels.of(langCode);
        StringBuilder sb = new StringBuilder();
        sb.append(lbl.caseId(inv.id));
        sb.append(lbl.clusterTopic()).append(inv.initialTitle).append("\n");
        sb.append(lbl.clusterAge()).append(formatAge(inv.threadCreatedUTC, lbl)).append("\n");
        sb.append(lbl.activeThreads()).append(inv.activeThreadIds.size()).append("\n");
        if (!inv.tickers.isEmpty()) {
            sb.append(lbl.tickersSeen()).append(inv.tickers).append("\n");
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
            sb.append(lbl.priorHeadlinesHeader());
            long coveredBeforeEpoch = 0L;
            for (int i = 0; i < priorHeadlines.size(); i++) {
                HeadlineRecord h = priorHeadlines.get(i);
                long secsAgo = Instant.now().getEpochSecond() - h.createdAt();
                sb.append(lbl.priorHeadlineLine(i + 1, formatRelativeSeconds(secsAgo), h.headline()));
                if (h.createdAt() > coveredBeforeEpoch) coveredBeforeEpoch = h.createdAt();
            }
            // Time-based coverage (robust, model-citation-independent): everything
            // that existed at/before the most recent prior headline was already on
            // the table when that line was written → covered, so it must NOT seed a
            // new headline. The prior headlines above ARE the context for that
            // covered material; only fresh evidence is shown in full below. Mirrors
            // the per-unit covered boundary. Late-analysed images still re-surface
            // (keyed on `shown`, not on coverage — see appendComments/appendImages).
            Set<String> tids = new HashSet<>(inv.activeThreadIds);
            if (inv.bestThreadId != null) tids.add(inv.bestThreadId);
            for (String tid : tids) {
                RedditThread t = repository.getThread(tid);
                if (t != null && t.createdUtc() <= coveredBeforeEpoch) coveredThreadIds.add(tid);
                for (RedditComment c : repository.getCommentsForThread(tid, 0)) {
                    if (c.createdUtc() <= coveredBeforeEpoch) coveredCommentIds.add(c.id());
                }
            }
            sb.append(lbl.onlyFreshEvidence());
        }
        sb.append("\n");

        appendThreadSources(sb, inv, coveredThreadIds, coveredCommentIds, priorHeadlines, lbl);

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
            List<HeadlineRecord> priorHeadlines, BriefLabels lbl) {
        // A covered thread collapses to a reference showing the most recent
        // headline this cluster already produced — so the agent recognises "I've
        // said this" instead of re-deriving it. priorHeadlines is chronological,
        // so the last entry is the freshest line.
        Map<String, String> threadToHeadline = new java.util.HashMap<>();
        String latestHeadline = priorHeadlines.isEmpty() ? null
                : priorHeadlines.get(priorHeadlines.size() - 1).headline();
        if (latestHeadline != null) {
            for (String tid : coveredThreadIds) threadToHeadline.put(tid, latestHeadline);
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
            sb.append(lbl.threadSourceHeader(idx++, threadCovered));
            sb.append(lbl.idLine(thread.id()));
            sb.append(lbl.titleLine(thread.title()));
            if (isCommunityQuestion(thread)) {
                sb.append(lbl.communityQuestion());
            }
            // A covered post's body already fed a headline; show only its NEW
            // material (late images + fresh comments). A fresh post shows its
            // body + all images + poll too.
            if (!threadCovered) {
                appendImages(sb, thread.imageUrls(), shown, false, lbl);
                appendTextSnippet(sb, thread.textContent(), lbl);
                appendPollIfPresent(sb, thread.pollData(), lbl);
            } else {
                appendImages(sb, thread.imageUrls(), shown, true, lbl);
            }
            appendComments(sb, thread.id(), coveredCommentIds, shown, lbl);
            sb.append("\n");
        }

        if (!covered.isEmpty()) {
            sb.append(lbl.alreadyCoveredHeader());
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
    private void appendImages(StringBuilder sb, List<String> imageUrls, Set<String> shown, boolean onlyNew,
            BriefLabels lbl) {
        if (imageUrls == null || imageUrls.isEmpty())
            return;
        int total = imageUrls.size();
        for (int i = 0; i < total; i++) {
            String url = imageUrls.get(i);
            if (onlyNew && shown.contains(url)) continue;
            String desc = brain.describeImageIfCached(url);
            if (desc.isEmpty()) continue;
            String label = onlyNew ? lbl.newImageLabel(i + 1, total) : lbl.imageLabel(i + 1, total);
            sb.append(label).append(": ").append(desc).append("\n");
            shown.add(url);
        }
    }

    private void appendTextSnippet(StringBuilder sb, String text, BriefLabels lbl) {
        if (text == null || text.isEmpty())
            return;
        String clean = stripHandles(text);
        String snippet = clean.length() > 500 ? clean.substring(0, 500) + "..." : clean;
        sb.append(lbl.contentSnippet()).append(snippet).append("\n");
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
    private void appendPollIfPresent(StringBuilder sb, PollData poll, BriefLabels lbl) {
        if (poll == null || poll.options() == null || poll.options().isEmpty()) return;
        sb.append(lbl.pollPrefix());
        boolean first = true;
        for (PollData.PollOption opt : poll.options()) {
            if (!first) sb.append(" · ");
            sb.append(opt.text()).append(" ").append(opt.voteCount());
            first = false;
        }
        sb.append(lbl.pollTotalOpen()).append(poll.totalVoteCount());
        long now = Instant.now().getEpochSecond();
        if (poll.votingEndsAtEpoch() > 0) {
            sb.append(poll.votingEndsAtEpoch() > now ? lbl.pollLive() : lbl.pollEnded());
        }
        sb.append(")\n");
    }

    private void appendComments(StringBuilder sb, String threadId, Set<String> coveredCommentIds,
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
            String stub = c.body() == null ? "" : stripHandles(c.body());
            if (stub.length() > 140) stub = stub.substring(0, 140) + "…";
            out.append(indent).append("- [").append(c.id()).append("] ").append(ANON_AUTHOR)
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
            String body = stripHandles(c.body() != null ? c.body() : "[deleted]");
            out.append(indent).append("- [").append(c.id()).append("] ");
            out.append(ANON_AUTHOR).append(" (").append(lbl.scoreTag(c.score())).append("): ")
                    .append(body).append("\n");
            appendCommentImages(out, c, shown, false, indent, lbl);
            return true;
        } else if (hasUnshownCachedImage(c.imageUrls(), shown)) {
            out.append(indent).append("- [").append(c.id()).append("] ").append(ANON_AUTHOR)
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

    private String formatAge(long createdUTC, BriefLabels lbl) {
        if (createdUTC <= 0)
            return lbl.unknown();
        long minutes = Duration.between(Instant.ofEpochSecond(createdUTC), Instant.now()).toMinutes();
        return String.format("%dh %dm", minutes / 60, minutes % 60);
    }
}
