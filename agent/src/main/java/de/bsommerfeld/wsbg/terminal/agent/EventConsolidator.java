package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.SubjectUnit.EvidenceRef;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Event consolidation (extracted from {@link SubjectAttributor}): given the pass's
 * attributed {@link Candidate}s it decides ONE primary per event, folds every
 * candidate's evidence into the {@link SubjectRegistry}, distributes the co-subjects
 * as named context on the primary, attaches the thread's remaining atmosphere, and
 * marks the units dirty.
 *
 * <h3>Consolidation — one headline per STORY, not per extracted name (2026-07-01)</h3>
 * Per attribution pass ONE subject is the event's <b>primary</b> — ranked: named in
 * the cluster's initial thread title AND tradeable → title-named → tradeable with
 * most real mentions → most real mentions — and the primary always composes. A
 * co-subject also composes when it is tradeable (or merely Yahoo-rate-limit
 * unresolved) AND carries at least one real mention the primary does not share (its
 * own comment = its own story); otherwise it is context of the same event and stays
 * silent, its mentions riding on the primary as {@code reddit-context} refs prefixed
 * with the co-subject's name.
 */
final class EventConsolidator {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(EventConsolidator.class);

    private EventConsolidator() {
    }

    /** One attributable subject of the pass: its resolution + the evidence found for it. */
    record Candidate(ResolvedSubject rs, List<EvidenceRef> found) {}

    /**
     * Consolidates the pass's candidates into {@code registry}. Callers guarantee
     * {@code candidates} is non-empty.
     */
    static void consolidate(SubjectRegistry registry, InvestigationCluster cluster,
            RedditRepository repository, List<Candidate> candidates,
            Set<String> ambiguous, String modelPrimary, long now) {
        // Consolidation: ONE primary per event (see class doc). Every candidate's
        // unit accumulates its evidence feed-wide; the primary always composes.
        // A co-subject composes TOO only when it is its own story (own-pick rule
        // below) — otherwise it is context of the primary's event and stays silent.
        Candidate primary = pickModelPrimary(candidates, modelPrimary);
        boolean modelPicked = primary != null;
        if (primary == null) primary = pickPrimary(cluster, candidates, ambiguous);
        Set<String> primaryKeys = new HashSet<>();
        for (EvidenceRef ref : primary.found()) primaryKeys.add(ref.key());

        SubjectUnit primaryUnit = null;
        boolean primaryGained = false;
        int ownStories = 0;
        for (Candidate c : candidates) {
            SubjectUnit unit = registry.findOrCreate(SubjectAttributor.unitKey(c.rs()), c.rs().canonicalName());
            unit.updateResolved(c.rs().canonicalName(), c.rs().ticker(), c.rs().snapshot(), c.rs().news());
            // Dirty only on genuinely-new REAL evidence: re-running attribution over
            // an unchanged cluster (same comments) must NOT re-wake a unit — otherwise
            // every pass re-composes the whole feed — and a price refresh alone
            // (updateResolved) is not a reason to re-write a headline. Conversation
            // CONTEXT (the ↳ ancestor chain) accumulates silently: it is "erwähnt im
            // Verhältnis zu", background that colours the next line, never a story of
            // its own.
            boolean gained = false;
            for (EvidenceRef ref : c.found()) {
                boolean added = unit.addEvidence(ref);
                if (!"reddit-context".equals(ref.source())) gained |= added;
            }
            if (c == primary) {
                primaryUnit = unit;
                primaryGained = gained;
                continue;
            }
            // Own-pick rule: a co-subject that is TRADEABLE (or merely un-enriched by a
            // Yahoo rate limit — never punish the gem for Yahoo being down) AND carries
            // at least one real mention the primary does NOT share (its own comment) is
            // its own story — "Nutzer nennt XYZ als Investment" — and composes its own
            // line. That keeps the quiet pennystock one-liner alive inside a picks
            // thread (the product's core discovery case). A co-subject whose every
            // mention is shared with the primary (same title, same comment, same
            // screenshot) — or that isn't tradeable at all (NSF, Chips Act, themes) —
            // is context of the SAME story and stays silent.
            boolean ownStory = (c.rs().isInstrument() || c.rs().unresolved())
                    && c.found().stream().anyMatch(ref ->
                            !"reddit-context".equals(ref.source()) && !primaryKeys.contains(ref.key()));
            if (ownStory && gained) {
                registry.markDirty(unit.id);
                ownStories++;
            }
        }
        // The co-subjects' mentions ALSO ride on the primary as named context, so the
        // event's headline sees the whole thread — including fresh evidence that only
        // named a co-subject (that too advances the primary's story, and is what
        // re-dirties it — the ONE deliberate exception to "context never wakes a
        // unit" above: these copies are fresh REAL mentions of the event, merely
        // demoted in rank). Key-level dedupe drops the copy when the primary already
        // holds the same comment as a real mention.
        for (Candidate c : candidates) {
            if (c == primary) continue;
            String label = displayName(c.rs());
            for (EvidenceRef ref : c.found()) {
                if ("reddit-context".equals(ref.source())) continue; // don't chain context-of-context
                primaryGained |= primaryUnit.addEvidence(new EvidenceRef(ref.threadId(),
                        ref.commentId(), "[" + label + "] " + ref.snippet(), "reddit-context",
                        ref.addedAtEpoch()));
            }
        }
        // NOTHING from the thread is thrown away: comments that name NO subject at
        // all (mood, jokes, the room's voice around the event) still belong to the
        // event's story — they attach to the PRIMARY as conversation context,
        // "erwähnt im Verhältnis zu". Silent by design: atmosphere never wakes a
        // unit (the brief's char budget bounds what the model ultimately sees, the
        // TTL prune bounds retention, key-dedupe makes re-attribution a no-op).
        Set<String> matchedKeys = new HashSet<>(primaryKeys);
        for (Candidate c : candidates) {
            for (EvidenceRef ref : c.found()) matchedKeys.add(ref.key());
        }
        for (String threadId : cluster.activeThreadIds) {
            for (RedditComment cm : repository.getCommentsForThread(threadId, 0)) {
                if (cm.body() == null || cm.body().isBlank()) continue;
                String key = threadId + "/" + cm.id();
                if (matchedKeys.contains(key)) continue;
                primaryUnit.addEvidence(new EvidenceRef(threadId, cm.id(),
                        EvidenceText.contextSnippet(cm.body()), "reddit-context", now));
            }
        }

        if (primaryGained) registry.markDirty(primaryUnit.id);
        if (candidates.size() > 1) {
            LOG.info("[CONSOLIDATE] cluster {} → primary {} ({}, {}), {} own-story co-subject(s), {} demoted to context",
                    cluster.id, primaryUnit.id, displayName(primary.rs()),
                    modelPicked ? "model" : "heuristic", ownStories,
                    candidates.size() - 1 - ownStories);
        }
    }

    /**
     * The candidate matching extraction's own primary pick, or {@code null} when the
     * model named none or its pick has no evidence (phantom guard — the heuristic
     * then decides). Matched against both the extracted query and the resolved
     * canonical name, case-insensitively.
     */
    private static Candidate pickModelPrimary(List<Candidate> candidates, String modelPrimary) {
        if (modelPrimary == null || modelPrimary.isBlank()) return null;
        String want = modelPrimary.trim().toLowerCase(Locale.ROOT);
        for (Candidate c : candidates) {
            if (want.equals(EvidenceText.nz(c.rs().query()).trim().toLowerCase(Locale.ROOT))
                    || want.equals(EvidenceText.nz(c.rs().canonicalName()).trim().toLowerCase(Locale.ROOT))) {
                return c;
            }
        }
        return null;
    }

    /**
     * Picks the event's PRIMARY subject: named in the cluster's initial thread title
     * AND tradeable beats title-named beats tradeable beats the rest; within a tier,
     * the most real mentions win. The initial title is stable for the cluster's whole
     * life (cluster id == initial thread id), so the pick doesn't flip on re-preps.
     */
    private static Candidate pickPrimary(InvestigationCluster cluster,
            List<Candidate> candidates, Set<String> ambiguous) {
        String title = EvidenceText.nz(cluster.initialTitle);
        Candidate best = null;
        long bestScore = -1;
        for (Candidate c : candidates) {
            String ticker = c.rs().isInstrument() ? c.rs().ticker().toUpperCase(Locale.ROOT) : null;
            Set<String> words = NameMatcher.nameWords(c.rs().query(), c.rs().canonicalName());
            boolean titleNamed = NameMatcher.matches(title, words, ticker, ambiguous);
            long score = (titleNamed ? 2_000_000L : 0)
                    + (c.rs().isInstrument() ? 1_000_000L : 0)
                    + realMentions(c.found());
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /** Real mentions (reddit/vision), excluding conversation-context refs. */
    private static long realMentions(List<EvidenceRef> found) {
        return found.stream().filter(r -> !"reddit-context".equals(r.source())).count();
    }

    /** The room-facing short name of a subject — the extracted form, not Yahoo's legal name. */
    private static String displayName(ResolvedSubject rs) {
        return rs.query() != null && !rs.query().isBlank() ? rs.query() : rs.canonicalName();
    }
}
