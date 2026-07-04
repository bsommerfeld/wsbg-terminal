package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.SubjectUnit.EvidenceRef;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Wires a cluster's resolved subjects into the {@link SubjectRegistry} (#2):
 * for each subject it finds the thread/comments that mention it and attaches
 * them as evidence to the unit (by ticker or normalised name). Multi-membership
 * falls out naturally — a comment naming "NVIDIA, AMD oder Intel" is attributed
 * to all three units.
 *
 * <p>The word-level matching engine lives in {@link NameMatcher}; the one-headline-
 * per-event consolidation (primary pick, own-story vs context distribution) lives
 * in {@link EventConsolidator}. This class owns evidence <b>collection</b> — walking
 * the cluster's threads/comments/images to build each subject's {@link EvidenceRef}
 * list, plus the conversation-context ancestor chaining — and orchestrates the pass.
 *
 * <h3>Images attach twice — standalone and inherited</h3>
 * An attached image is evidence in two independent ways, both as plain
 * {@code "vision"} source:
 * <ul>
 *   <li><b>standalone</b> — the image's own transcript names a subject, so it
 *       lands at that subject regardless of what its post said (an AMD chart under
 *       an NVIDIA thread is also attributed to AMD);</li>
 *   <li><b>inherited</b> — the carrying post/comment named a subject, so the image
 *       rides along as the poster's own context for it, even when the transcript
 *       never says the name (an unlabelled chart, a logo-only depot, a meme on an
 *       NVIDIA thread is attributed to NVIDIA). The placement IS the link.</li>
 * </ul>
 * Both can fire at once across different subjects; for the same image+subject the
 * per-image evidence key dedupes them to one ref (standalone snippet preferred).
 *
 * <h3>No phantom units</h3>
 * A subject the model named but no thread/comment actually mentions (a
 * hallucination, or a name the matcher genuinely can't tie to the text) gets
 * <b>zero evidence and is dropped</b> — a unit must reflect something the room
 * really said.
 */
public final class SubjectAttributor {

    private final RedditRepository repository;
    private final AgentBrain brain;

    public SubjectAttributor(RedditRepository repository, AgentBrain brain) {
        this.repository = repository;
        this.brain = brain;
    }

    /** Attributes every resolved subject of {@code cluster} into {@code registry} (heuristic primary). */
    public void attribute(SubjectRegistry registry, InvestigationCluster cluster,
            List<ResolvedSubject> resolved) {
        attribute(registry, cluster, resolved, null);
    }

    /**
     * Attributes every resolved subject of {@code cluster} into {@code registry}.
     * {@code modelPrimary} is extraction's own event cut — the protagonist the model
     * named after reading the whole thread (tradeable or not). When it maps to an
     * evidence-backed candidate it wins the primary pick outright; the
     * title/tradeable/mention-count heuristic only backstops a missing or
     * phantom (evidence-less) model pick.
     */
    public void attribute(SubjectRegistry registry, InvestigationCluster cluster,
            List<ResolvedSubject> resolved, String modelPrimary) {
        long now = Instant.now().getEpochSecond();
        // Reply tree per thread, built once for the whole cluster: when a subject
        // is named deep in a chain ("E.ON und Constellation"), the conversation it
        // answers (the energy thesis it hangs under) is attached as CONTEXT to the
        // same unit, so the headline can carry the shared story. Cheap, and on RSS
        // (no parent linkage) every chain is empty — context simply isn't added.
        Map<String, CommentTree> trees = buildTrees(cluster);
        // Words that appear in ≥2 of THIS cluster's subject names are "ambiguous"
        // (e.g. "msci" across MSCI World + MSCI Emerging Markets). A match on an
        // ambiguous word ALONE isn't enough — otherwise MSCI EM would glom onto the
        // "Core MSCI World −1,84%" row. A distinctive word ("world", "berkshire",
        // "meta") still matches on its own, so short forms keep working.
        Set<String> ambiguous = NameMatcher.ambiguousWords(resolved);
        List<EventConsolidator.Candidate> candidates = new ArrayList<>();
        for (ResolvedSubject rs : resolved) {
            // A Yahoo-rate-limited subject (rs.unresolved()) is NOT skipped — the
            // ROOM is the story, Yahoo only enriches. It's attributed as a tickerless
            // unit and still gets a headline from the evidence; when Yahoo recovers it
            // re-resolves to its ticker and the identity-merge folds the duplicate.
            String ticker = rs.isInstrument() ? rs.ticker().toUpperCase(Locale.ROOT) : null;
            Set<String> words = NameMatcher.nameWords(rs.query(), rs.canonicalName());
            if (words.isEmpty() && ticker == null) continue;

            // Collect evidence first — a subject with no real mention is dropped.
            List<EvidenceRef> found = collectEvidence(cluster, words, ticker, ambiguous, now);
            if (found.isEmpty()) continue; // no real mention → no phantom unit

            // Conversation context: for every real comment-mention, pull in the
            // chain it replies to as CONTEXT evidence (root-first, the thesis first).
            found.addAll(conversationContext(found, trees, now));

            if (unitKey(rs) == null) continue;
            candidates.add(new EventConsolidator.Candidate(rs, found));
        }
        if (candidates.isEmpty()) return;

        EventConsolidator.consolidate(registry, cluster, repository, candidates, ambiguous, modelPrimary, now);
    }

    /** Reply tree per thread, built once for the whole cluster (empty on RSS: no parent linkage). */
    private Map<String, CommentTree> buildTrees(InvestigationCluster cluster) {
        Map<String, CommentTree> trees = new HashMap<>();
        for (String threadId : cluster.activeThreadIds) {
            trees.put(threadId, CommentTree.of(repository.getCommentsForThread(threadId, 0)));
        }
        return trees;
    }

    /**
     * Walks the cluster's threads/comments/images and returns every REAL mention
     * of the subject (thread title/body, comments, standalone/inherited images) as
     * {@link EvidenceRef}s. A subject with no real mention yields an empty list and
     * is dropped by the caller.
     */
    private List<EvidenceRef> collectEvidence(InvestigationCluster cluster,
            Set<String> words, String ticker, Set<String> ambiguous, long now) {
        List<EvidenceRef> found = new ArrayList<>();
        for (String threadId : cluster.activeThreadIds) {
            RedditThread t = repository.getThread(threadId);
            if (t == null) continue;
            boolean threadNames = NameMatcher.matches(
                    EvidenceText.nz(t.title()) + " " + EvidenceText.nz(t.textContent()),
                    words, ticker, ambiguous);
            if (threadNames) {
                found.add(new EvidenceRef(threadId, null, EvidenceText.snippet(t.title()), "reddit", now));
            }
            // A post's images attach to this subject in TWO ways, both as plain
            // "vision" evidence: (1) standalone — the image's own transcript names the
            // subject; or (2) inherited — the POST that carries it named the subject,
            // so the image rides along as the poster's own context even when the
            // transcript never says the name. The placement IS the link.
            attachImages(found, threadId, "_vision#", t.imageUrls(),
                    threadNames, words, ticker, ambiguous, now);
            for (RedditComment c : repository.getCommentsForThread(threadId, 0)) {
                boolean commentNames = c.body() != null && !c.body().isBlank()
                        && NameMatcher.matches(c.body(), words, ticker, ambiguous);
                if (commentNames) {
                    found.add(new EvidenceRef(threadId, c.id(), EvidenceText.snippet(c.body()), "reddit", now));
                }
                // Same dual rule for a comment's images: standalone, or inherited
                // when the comment body itself named the subject.
                attachImages(found, threadId, c.id() + "#img", c.imageUrls(),
                        commentNames, words, ticker, ambiguous, now);
            }
        }
        return found;
    }

    /**
     * For every real comment-mention in {@code found}, pulls in the chain it replies
     * to as CONTEXT evidence (root-first, the thesis first). An ancestor that is
     * itself a real mention of THIS subject stays a real mention — it is not
     * downgraded to context. Returns only the freshly-derived context refs.
     */
    private List<EvidenceRef> conversationContext(List<EvidenceRef> found,
            Map<String, CommentTree> trees, long now) {
        Set<String> realKeys = new HashSet<>();
        for (EvidenceRef ref : found) realKeys.add(ref.key());
        List<EvidenceRef> context = new ArrayList<>();
        Set<String> contextKeys = new HashSet<>();
        for (EvidenceRef ref : found) {
            String commentId = baseCommentId(ref.commentId());
            if (commentId == null) continue; // post body / thread image — no ancestors
            CommentTree tree = trees.get(ref.threadId());
            if (tree == null) continue;
            for (RedditComment anc : tree.ancestorsOf(commentId)) {
                if (anc.body() == null || anc.body().isBlank()) continue;
                String key = ref.threadId() + "/" + anc.id();
                if (realKeys.contains(key) || !contextKeys.add(key)) continue;
                context.add(new EvidenceRef(ref.threadId(), anc.id(),
                        EvidenceText.contextSnippet(anc.body()), "reddit-context", now));
            }
        }
        return context;
    }

    /** Identity key: ticker for instruments, normalised canonical name otherwise. */
    static String unitKey(ResolvedSubject rs) {
        if (rs.isInstrument()) return rs.ticker().toUpperCase(Locale.ROOT);
        String name = rs.canonicalName();
        if (name == null || name.isBlank()) name = rs.query();
        if (name == null || name.isBlank()) return null;
        return "name:" + name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Package-private delegator kept for callers outside this class (e.g.
     * {@code EditorialAgent}) that tokenise text the same way — the implementation
     * lives in {@link NameMatcher#deUmlaut(String)}.
     */
    static String deUmlaut(String s) {
        return NameMatcher.deUmlaut(s);
    }

    /**
     * Attaches a thread's or comment's images as evidence for one subject. Each
     * image is keyed individually ({@code prefix + index}) so a multi-image post
     * doesn't collapse into a single evidence slot. An image attaches when its own
     * transcript names the subject (standalone — the snippet is the matching row),
     * OR when {@code containerNames} is set, i.e. the carrying post/comment named
     * the subject and the image rides along as inherited context (the snippet is
     * then the transcript's lead clause, since no row names the subject). Vision is
     * cache-only here — a not-yet-analysed image is simply skipped, never blocked on.
     */
    private void attachImages(List<EvidenceRef> found, String threadId, String prefix,
            List<String> urls, boolean containerNames,
            Set<String> words, String ticker, Set<String> ambiguous, long now) {
        if (urls == null || urls.isEmpty() || brain == null) return;
        for (int i = 0; i < urls.size(); i++) {
            String d = brain.describeImageIfCached(urls.get(i));
            if (d == null || d.isBlank()) continue;
            boolean imageNames = NameMatcher.matches(d, words, ticker, ambiguous);
            if (!imageNames && !containerNames) continue;
            String snip = imageNames
                    ? EvidenceText.snippet(NameMatcher.matchingLine(d, words, ticker, ambiguous))
                    : EvidenceText.snippet(NameMatcher.leadLine(d));
            found.add(new EvidenceRef(threadId, prefix + i, snip, "vision", now));
        }
    }

    /**
     * The underlying comment id of an evidence ref, or {@code null} when the ref
     * isn't a comment. Strips the {@code #img<n>} suffix a comment-image vision ref
     * carries and filters the post-level markers ({@code null} = post body,
     * {@code _vision<n>} = a thread image) that have no comment ancestors.
     */
    private static String baseCommentId(String commentId) {
        if (commentId == null || commentId.startsWith("_vision")) return null;
        int img = commentId.indexOf("#img");
        return img >= 0 ? commentId.substring(0, img) : commentId;
    }
}
