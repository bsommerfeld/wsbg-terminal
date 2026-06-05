package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;

import java.util.Collection;
import java.util.HashSet;
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

    /** Returns and clears the current dirty set atomically. */
    public Set<String> drainDirty() {
        Set<String> drained = new HashSet<>(dirty);
        dirty.removeAll(drained);
        return drained;
    }

    /** Wipes every unit. Used by the lab "Reset". */
    public void clear() {
        byId.clear();
        dirty.clear();
    }
}
