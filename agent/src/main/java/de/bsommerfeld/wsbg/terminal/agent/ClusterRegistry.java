package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Central in-memory store for active {@link InvestigationCluster} instances.
 *
 * <p>
 * Pulled out of {@link PassiveMonitorService} so the agent layer can read the
 * cluster state without depending on the scanner. Two flavours of read API:
 * <ul>
 * <li>{@link #getCluster(String)} / {@link #getAllClusters()} — return the
 * live {@link InvestigationCluster} objects, used by code inside the agent
 * module that needs to mutate state (cluster-merge, centroid drift). Treat
 * these references as private to the agent package.</li>
 * <li>{@link #view(String)} / {@link #allViews()} — return immutable
 * {@link ClusterView} snapshots safe to hand out to tools or external code.
 * </li>
 * </ul>
 *
 * <p>
 * Change tracking: every {@link #notifyChange(String)} call marks the cluster
 * dirty and broadcasts to all registered subscribers. {@link #drainDirty()}
 * returns and clears the dirty set atomically — used by the
 * {@code AgentCoordinator} to figure out what changed since its last tick.
 */
@Singleton
public class ClusterRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterRegistry.class);

    private final ConcurrentHashMap<String, InvestigationCluster> byId = new ConcurrentHashMap<>();
    private final Set<String> dirtyClusterIds = ConcurrentHashMap.newKeySet();
    private final List<Consumer<Set<String>>> subscribers = new CopyOnWriteArrayList<>();

    // -- mutation API (for the scanner/cluster-merge code) --

    public void add(InvestigationCluster cluster) {
        byId.put(cluster.id, cluster);
        notifyChange(cluster.id);
    }

    /**
     * Restores clusters from a persisted snapshot WITHOUT marking them dirty.
     * They are already-processed state, not fresh activity — notifying here
     * would make the editorial agent re-evaluate everything on startup. New
     * activity arriving after restore will mark them dirty the normal way.
     */
    public void restore(java.util.Collection<InvestigationCluster> clusters) {
        for (InvestigationCluster c : clusters) {
            byId.put(c.id, c);
        }
    }

    public void remove(String id) {
        byId.remove(id);
        dirtyClusterIds.remove(id);
    }

    /** Drops every cluster. Used by the editorial-lab "Reset" action. */
    public void clear() {
        byId.clear();
        dirtyClusterIds.clear();
    }

    public boolean contains(InvestigationCluster cluster) {
        return cluster != null && byId.get(cluster.id) == cluster;
    }

    public InvestigationCluster getCluster(String id) {
        return byId.get(id);
    }

    public Collection<InvestigationCluster> getAllClusters() {
        return byId.values();
    }

    public int size() {
        return byId.size();
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    // -- change tracking --

    /**
     * Marks the cluster dirty and notifies subscribers. Safe to call from
     * any thread; subscribers receive the single dirty id wrapped in a
     * snapshot set.
     */
    public void notifyChange(String clusterId) {
        if (clusterId == null)
            return;
        dirtyClusterIds.add(clusterId);
        Set<String> snapshot = Set.of(clusterId);
        for (Consumer<Set<String>> sub : subscribers) {
            try {
                sub.accept(snapshot);
            } catch (Exception e) {
                LOG.warn("ClusterRegistry subscriber threw: {}", e.getMessage());
            }
        }
    }

    /** Returns and clears the current dirty set atomically. */
    public Set<String> drainDirty() {
        Set<String> drained = new HashSet<>(dirtyClusterIds);
        dirtyClusterIds.removeAll(drained);
        return drained;
    }

    public Set<String> peekDirty() {
        return Collections.unmodifiableSet(new HashSet<>(dirtyClusterIds));
    }

    /**
     * Registers a listener that receives the set of dirty cluster IDs on
     * every {@link #notifyChange(String)} call. Used by
     * {@code AgentCoordinator} to drive the editorial loop.
     */
    public void subscribeToChanges(Consumer<Set<String>> listener) {
        subscribers.add(listener);
    }

    // -- snapshot API (for tools and external readers) --

    public ClusterView view(String id) {
        InvestigationCluster c = byId.get(id);
        return c == null ? null : snapshot(c);
    }

    public List<ClusterView> allViews() {
        List<ClusterView> out = new ArrayList<>(byId.size());
        for (InvestigationCluster c : byId.values()) {
            out.add(snapshot(c));
        }
        return out;
    }

    private ClusterView snapshot(InvestigationCluster c) {
        return new ClusterView(
                c.id,
                c.initialTitle,
                c.threadCount,
                c.totalComments,
                c.totalScore,
                c.currentSignificance,
                c.firstSeen,
                c.lastActivity,
                c.bestThreadId,
                c.latestThreadId,
                List.copyOf(c.activeThreadIds),
                c.headlineCount);
    }

    /**
     * Immutable snapshot of a cluster's externally-relevant state. Safe to
     * hand out to agent tools and the UI — no references to the live
     * {@link InvestigationCluster}.
     */
    public record ClusterView(
            String id,
            String initialTitle,
            int threadCount,
            int totalComments,
            int totalScore,
            double currentSignificance,
            Instant firstSeen,
            Instant lastActivity,
            String bestThreadId,
            String latestThreadId,
            List<String> activeThreadIds,
            int headlineCount) {
    }
}
