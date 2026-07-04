package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feed-wide store of {@link SubjectUnit}s (#2). The subject-level counterpart to
 * {@link ClusterRegistry}: where the cluster registry groups <em>threads</em>,
 * this accumulates evidence per <em>subject</em> across all threads/clusters, and
 * is the source the per-unit editorial reads. Units are found/created by their
 * identity key (ticker or normalised name), so the same subject mentioned in
 * five different threads folds into one unit.
 *
 * <p>Dirty-tracking mirrors {@link ClusterRegistry}: a unit that gained fresh
 * evidence is marked dirty so the editorial layer knows it has something new to
 * (re)compose.
 */
@Singleton
public final class SubjectRegistry {

    private final ConcurrentHashMap<String, SubjectUnit> byId = new ConcurrentHashMap<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    /** Returns the existing unit for {@code id} or creates one with the given display name. */
    public SubjectUnit findOrCreate(String id, String canonicalName) {
        return byId.computeIfAbsent(id, k -> new SubjectUnit(k, canonicalName));
    }

    public SubjectUnit get(String id) { return byId.get(id); }
    public Collection<SubjectUnit> all() { return byId.values(); }
    public int size() { return byId.size(); }

    public void markDirty(String id) { if (id != null) dirty.add(id); }

    /**
     * Returns and clears the current dirty set. Per-element remove (not a bulk
     * {@code removeAll} over a snapshot) so a unit re-marked between the snapshot
     * and the clear isn't silently wiped — a lost mark here means "the headline
     * never came", which is miserable to debug. A mark that lands after its
     * element was drained simply survives into the next drain.
     */
    public Set<String> drainDirty() {
        Set<String> drained = new HashSet<>();
        for (String id : dirty) {
            if (dirty.remove(id)) drained.add(id);
        }
        return drained;
    }

    public void remove(String id) {
        byId.remove(id);
        dirty.remove(id);
    }

    /**
     * Conservative identity-merge (#2 step 2.5): folds a ticker-less name unit
     * into a ticker unit ONLY when they share BOTH a piece of evidence (the same
     * comment) AND a significant name word — so "The Metals Company" absorbs into
     * "TMC the metals company" but two genuinely different subjects are never
     * welded together. Deterministic, no embedding/threshold guesswork: a false
     * merge would <em>swallow</em> data, so we'd rather leave a harmless duplicate.
     * Ticker↔ticker units never merge (distinct tickers = distinct subjects).
     *
     * <p>A second pass folds a name unit into ANOTHER name unit when the shorter
     * name's significant words are a SUBSET of the longer's AND they share evidence
     * — "Merz" into "Friedrich Merz" (the twin units once published the same
     * Reformpaket line twice, 5 min apart). The subset test is stricter than the
     * ticker pass's any-shared-word on purpose: "Deutsche Bank" and "Deutsche
     * Telekom" share a word but are no subset of each other.
     *
     * <p><b>Not atomic:</b> it iterates a snapshot of {@code byId.values()} while
     * removing entries, so the caller must hold the {@code EditorialPipeline} merge
     * write lock (the {@code MERGE_INTERVAL_MS} cadence). Tests call it directly on
     * a quiescent registry.
     *
     * @return how many units were absorbed
     */
    public int mergeIdentities() {
        List<SubjectUnit> tickerUnits = new ArrayList<>();
        List<SubjectUnit> nameUnits = new ArrayList<>();
        for (SubjectUnit u : byId.values()) {
            (u.isInstrument() ? tickerUnits : nameUnits).add(u);
        }
        int merged = 0;
        for (SubjectUnit n : nameUnits) {
            if (byId.get(n.id) != n) continue; // defensive
            Set<String> nWords = NameMatcher.significantWords(n.canonicalName());
            Set<String> nKeys = n.evidenceKeys();
            if (nWords.isEmpty() || nKeys.isEmpty()) continue;
            for (SubjectUnit t : tickerUnits) {
                boolean sharesWord = !Collections.disjoint(nWords,
                        NameMatcher.significantWords(t.canonicalName()));
                boolean sharesEvidence = !Collections.disjoint(nKeys, t.evidenceKeys());
                if (sharesWord && sharesEvidence) {
                    fold(t, n);
                    merged++;
                    break;
                }
            }
        }
        // Pass 2 — name-unit into name-unit. Longer names are the absorbers, so the
        // surviving unit carries the more specific canonical form.
        for (SubjectUnit n : nameUnits) {
            if (byId.get(n.id) != n) continue; // absorbed above or by an earlier pair
            Set<String> nWords = NameMatcher.significantWords(n.canonicalName());
            Set<String> nKeys = n.evidenceKeys();
            if (nWords.isEmpty() || nKeys.isEmpty()) continue;
            for (SubjectUnit m : nameUnits) {
                if (m == n || byId.get(m.id) != m) continue;
                Set<String> mWords = NameMatcher.significantWords(m.canonicalName());
                boolean subset = mWords.size() > nWords.size() && mWords.containsAll(nWords);
                if (subset && !Collections.disjoint(nKeys, m.evidenceKeys())) {
                    fold(m, n);
                    merged++;
                    break;
                }
            }
        }
        return merged;
    }

    /**
     * Absorbs {@code victim} into {@code absorber} and drops it from the store —
     * the single place the subtle dirty-transfer invariant lives. The merge itself
     * is not new evidence: the absorber only inherits a PENDING headline claim
     * (dirty) if the absorbed unit carried one. Unconditionally dirtying here let a
     * demoted co-subject (consolidation) sneak back into a compose via a later
     * identity-merge. Must run under the caller's merge write lock (see
     * {@link #mergeIdentities()}).
     */
    private void fold(SubjectUnit absorber, SubjectUnit victim) {
        absorber.absorb(victim);
        byId.remove(victim.id);
        if (dirty.remove(victim.id)) dirty.add(absorber.id);
    }

    /**
     * Context relief: prunes already-consumed <em>evidence</em> older than
     * {@code maxAge} from every unit, while leaving the units themselves standing.
     * A subject may live as long as it likes; the model just never sees hour-old
     * comments. Published headlines are NOT pruned — they're the unit's story
     * memory (see {@link SubjectUnit#pruneOlderThan}). Tied to the snapshot TTL
     * (a session that old is wiped on restart anyway).
     *
     * @return total number of evidence entries dropped
     */
    public int pruneContentOlderThan(java.time.Duration maxAge) {
        int pruned = 0;
        for (SubjectUnit u : byId.values()) pruned += u.pruneOlderThan(maxAge);
        return pruned;
    }

    /** Wipes every unit. Used by the lab "Reset". */
    public void clear() {
        byId.clear();
        dirty.clear();
    }

    /** Snapshots every unit for short-TTL session persistence. */
    public List<SubjectUnit.Snapshot> snapshotAll() {
        List<SubjectUnit.Snapshot> out = new ArrayList<>();
        for (SubjectUnit u : byId.values()) out.add(u.toSnapshot());
        return out;
    }

    /**
     * Restores units verbatim from snapshots (quick restart within the TTL).
     * Replaces the current set so a restore is idempotent; units do NOT come back
     * dirty (no evidence was added, so nothing to re-compose until fresh activity).
     */
    public void restore(List<SubjectUnit.Snapshot> snapshots) {
        if (snapshots == null) return;
        for (SubjectUnit.Snapshot s : snapshots) {
            if (s == null || s.id() == null) continue;
            SubjectUnit u = new SubjectUnit(s);
            byId.put(u.id, u);
        }
    }
}
