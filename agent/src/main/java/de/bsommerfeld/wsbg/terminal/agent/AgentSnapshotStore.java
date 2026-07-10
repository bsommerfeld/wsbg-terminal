package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.SnapshotFreshness;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Companion to {@code RedditSnapshotStore} for the AI-derived state, so a quick
 * restart resumes the <em>exact</em> prior session — not an approximation:
 * <ul>
 *   <li>the per-URL vision-analysis cache (already-analysed images aren't
 *       recomputed),</li>
 *   <li>the published headlines (the UI shows them immediately instead of a
 *       fresh, empty view, and the agent's coverage logic still sees which
 *       threads/comments already produced a headline),</li>
 *   <li>the full {@link InvestigationCluster} state — centroid, evidence log,
 *       shown-image markers, headlineCount — restored verbatim, so the clusters
 *       the agent built up survive the restart with the same IDs (no
 *       re-embedding) and the same shown-image bookkeeping.</li>
 * </ul>
 *
 * <p>Same freshness contract as the Reddit snapshot ({@code
 * reddit.snapshot-ttl-minutes}, day-or-TTL rule via {@link SnapshotFreshness}):
 * restored when from today or younger than the TTL, deleted otherwise.
 * Best-effort — any I/O/parse failure degrades to "no snapshot".
 */
@Singleton
public final class AgentSnapshotStore {

    private static final Logger LOG = LoggerFactory.getLogger(AgentSnapshotStore.class);
    private static final String FILE_NAME = "agent-snapshot.json";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public AgentSnapshotStore() {
        this.file = StorageUtils.getSnapshotsDir().resolve(FILE_NAME);
    }

    /** Deletes the persisted snapshot file so a restart can't restore it — for the "Daten löschen" full reset. */
    public synchronized void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            LOG.warn("Failed to delete agent snapshot file: {}", e.getMessage());
        }
    }

    public synchronized void save(Map<String, String> visionCache,
            List<HeadlineRecord> headlines, List<InvestigationCluster.Snapshot> clusters,
            List<SubjectUnit.Snapshot> subjectUnits) {
        try {
            Files.createDirectories(file.getParent());
            AgentSnapshot snapshot = new AgentSnapshot(
                    Instant.now().getEpochSecond(), visionCache, headlines, clusters, subjectUnits);
            mapper.writeValue(file.toFile(), snapshot);
            LOG.info("Saved agent snapshot: {} vision, {} headlines, {} clusters, {} subject units → {}",
                    size(visionCache), size(headlines), size(clusters), size(subjectUnits), file);
        } catch (Exception e) {
            LOG.warn("Failed to save agent snapshot: {}", e.getMessage());
        }
    }

    public synchronized Optional<AgentSnapshot> loadIfFresh(long ttlMinutes) {
        if (ttlMinutes <= 0 || !Files.exists(file)) return Optional.empty();
        try {
            AgentSnapshot snapshot = mapper.readValue(file.toFile(), AgentSnapshot.class);
            long ageMinutes = (Instant.now().getEpochSecond() - snapshot.savedAtEpochSeconds()) / 60;
            if (!SnapshotFreshness.isFresh(snapshot.savedAtEpochSeconds(), ttlMinutes)) {
                LOG.info("Agent snapshot is stale ({} min old, not from today); discarding.",
                        ageMinutes);
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            LOG.info("Agent snapshot is fresh ({} min old, day-or-TTL rule, TTL {} min): {} vision, {} headlines, {} clusters, {} subject units.",
                    ageMinutes, ttlMinutes, size(snapshot.visionCache()),
                    size(snapshot.headlines()), size(snapshot.clusters()), size(snapshot.subjectUnits()));
            return Optional.of(snapshot);
        } catch (Exception e) {
            LOG.warn("Failed to read agent snapshot ({}); ignoring.", e.getMessage());
            return Optional.empty();
        }
    }

    private static int size(Map<?, ?> m) {
        return m != null ? m.size() : 0;
    }

    private static int size(List<?> l) {
        return l != null ? l.size() : 0;
    }

    public record AgentSnapshot(
            long savedAtEpochSeconds,
            Map<String, String> visionCache,
            List<HeadlineRecord> headlines,
            List<InvestigationCluster.Snapshot> clusters,
            List<SubjectUnit.Snapshot> subjectUnits) {
    }
}
