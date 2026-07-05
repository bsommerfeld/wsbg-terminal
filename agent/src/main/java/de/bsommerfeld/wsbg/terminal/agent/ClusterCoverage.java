package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The time-based coverage index for one cluster: every thread/comment that existed
 * at or before the most recent prior headline was already on the table when that
 * line was written, so it must NOT seed a new headline. Computed once and read by
 * both the report header and the thread-source split. Extracted verbatim from
 * {@link ReportBuilder#buildReportData}.
 *
 * @param threadIds          covered thread ids (created at/before the last headline)
 * @param commentIds         covered comment ids (created at/before the last headline)
 * @param coveredBeforeEpoch epoch of the most recent prior headline (0 when none)
 */
record ClusterCoverage(Set<String> threadIds, Set<String> commentIds, long coveredBeforeEpoch) {

    static ClusterCoverage of(InvestigationCluster inv, List<HeadlineRecord> priorHeadlines,
            RedditRepository repository) {
        Set<String> coveredThreadIds = new HashSet<>();
        Set<String> coveredCommentIds = new HashSet<>();
        long coveredBeforeEpoch = 0L;
        for (HeadlineRecord h : priorHeadlines) {
            if (h.createdAt() > coveredBeforeEpoch) coveredBeforeEpoch = h.createdAt();
        }
        // Time-based coverage (robust, model-citation-independent): everything
        // that existed at/before the most recent prior headline was already on
        // the table when that line was written → covered, so it must NOT seed a
        // new headline. Late-analysed images still re-surface (keyed on `shown`,
        // not on coverage — see CommentTreeRenderer / appendImages).
        if (!priorHeadlines.isEmpty()) {
            Set<String> tids = new HashSet<>(inv.activeThreadIds);
            if (inv.bestThreadId != null) tids.add(inv.bestThreadId);
            for (String tid : tids) {
                RedditThread t = repository.getThread(tid);
                if (t != null && t.createdUtc() <= coveredBeforeEpoch) coveredThreadIds.add(tid);
                for (RedditComment c : repository.getCommentsForThread(tid, 0)) {
                    if (c.createdUtc() <= coveredBeforeEpoch) coveredCommentIds.add(c.id());
                }
            }
        }
        return new ClusterCoverage(coveredThreadIds, coveredCommentIds, coveredBeforeEpoch);
    }
}
