package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.PollData;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
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
 *
 * <p>The self-contained sub-renderers live in collaborators: {@link ClusterCoverage}
 * (the time-based covered-thread/comment index), {@link CommentTreeRenderer} (the
 * reply-tree recursion + late-image resurfacing), {@link RedditAnonymizer} (handle
 * masking). This class is the top-level structure: metadata → prior headlines →
 * thread sources.
 */
public final class ReportBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(ReportBuilder.class);

    private final RedditRepository repository;
    private final AgentBrain brain;
    private final CommentTreeRenderer commentTree;

    public ReportBuilder(RedditRepository repository, AgentBrain brain) {
        this.repository = repository;
        this.brain = brain;
        this.commentTree = new CommentTreeRenderer(repository, brain);
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
        ClusterCoverage coverage = ClusterCoverage.of(inv, priorHeadlines, repository);
        Set<String> coveredThreadIds = coverage.threadIds();
        Set<String> coveredCommentIds = coverage.commentIds();
        if (!priorHeadlines.isEmpty()) {
            sb.append(lbl.priorHeadlinesHeader());
            for (int i = 0; i < priorHeadlines.size(); i++) {
                HeadlineRecord h = priorHeadlines.get(i);
                long secsAgo = Instant.now().getEpochSecond() - h.createdAt();
                sb.append(lbl.priorHeadlineLine(i + 1, formatRelativeSeconds(secsAgo), h.headline()));
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
                        || commentTree.hasUncoveredComments(threadId, coveredCommentIds)
                        || commentTree.hasUnshownCachedImage(thread.imageUrls(), shown)
                        || commentTree.threadHasCommentWithUnshownImage(threadId, shown);
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
            commentTree.render(sb, thread.id(), coveredCommentIds, shown, lbl);
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
        String clean = RedditAnonymizer.stripHandles(text);
        String snippet = clean.length() > 500 ? clean.substring(0, 500) + "..." : clean;
        sb.append(lbl.contentSnippet()).append(snippet).append("\n");
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
