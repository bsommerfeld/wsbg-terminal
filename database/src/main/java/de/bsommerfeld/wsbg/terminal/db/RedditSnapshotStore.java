package de.bsommerfeld.wsbg.terminal.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Short-lived on-disk snapshot of the Reddit repository, written so a quick
 * restart doesn't have to re-fetch everything from Reddit.
 *
 * <p>
 * This is the one deliberate crack in the "everything in-memory" rule. The
 * project keeps Reddit state in memory precisely because persisting old
 * snapshots produced <em>ghost clusters</em> — clusters built from posts that
 * had since vanished from the live feed. The mitigation is a hard time-to-live
 * (default 60 min, {@code reddit.snapshot-ttl-minutes}): a snapshot older than
 * the TTL is discarded on load, so only data fresh enough that its posts almost
 * certainly still exist is ever restored. The cold-start scan of a busy
 * subreddit costs hundreds of requests and minutes of wall-clock under the
 * anonymous rate budget; skipping it on a quick restart is a large saving,
 * especially while developing/testing.
 *
 * <p>Single JSON file in the app data directory. Best-effort: any I/O failure
 * degrades to "no snapshot" rather than propagating — a broken snapshot must
 * never block startup.
 */
@Singleton
public final class RedditSnapshotStore {

    private static final Logger LOG = LoggerFactory.getLogger(RedditSnapshotStore.class);
    private static final String FILE_NAME = "reddit-snapshot.json";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public RedditSnapshotStore() {
        this.file = StorageUtils.getSnapshotsDir().resolve(FILE_NAME);
    }

    /** Deletes the persisted snapshot file so a restart can't restore it — for the "Daten löschen" full reset. */
    public synchronized void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            LOG.warn("Failed to delete reddit snapshot file: {}", e.getMessage());
        }
    }

    /** Writes the current repository contents to disk, stamped with "now". */
    public synchronized void save(List<RedditThread> threads, List<RedditComment> comments) {
        try {
            Files.createDirectories(file.getParent());
            RedditSnapshot snapshot = new RedditSnapshot(
                    Instant.now().getEpochSecond(), threads, comments);
            mapper.writeValue(file.toFile(), snapshot);
            LOG.info("Saved Reddit snapshot: {} threads, {} comments → {}",
                    threads.size(), comments.size(), file);
        } catch (Exception e) {
            LOG.warn("Failed to save Reddit snapshot: {}", e.getMessage());
        }
    }

    /**
     * Loads the snapshot only if it exists and is younger than {@code
     * ttlMinutes}. A stale snapshot is deleted and {@link Optional#empty()}
     * returned. {@code ttlMinutes <= 0} disables restore entirely.
     */
    public synchronized Optional<RedditSnapshot> loadIfFresh(long ttlMinutes) {
        if (ttlMinutes <= 0 || !Files.exists(file)) return Optional.empty();
        try {
            RedditSnapshot snapshot = mapper.readValue(file.toFile(), RedditSnapshot.class);
            long ageMinutes = (Instant.now().getEpochSecond() - snapshot.savedAtEpochSeconds()) / 60;
            if (ageMinutes > ttlMinutes) {
                LOG.info("Reddit snapshot is stale ({} min > {} min TTL); discarding.",
                        ageMinutes, ttlMinutes);
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            LOG.info("Reddit snapshot is fresh ({} min ≤ {} min TTL): {} threads, {} comments.",
                    ageMinutes, ttlMinutes,
                    snapshot.threads() != null ? snapshot.threads().size() : 0,
                    snapshot.comments() != null ? snapshot.comments().size() : 0);
            return Optional.of(snapshot);
        } catch (Exception e) {
            LOG.warn("Failed to read Reddit snapshot ({}); ignoring.", e.getMessage());
            return Optional.empty();
        }
    }

    /** Serialised snapshot: when it was taken plus the full repository contents. */
    public record RedditSnapshot(
            long savedAtEpochSeconds,
            List<RedditThread> threads,
            List<RedditComment> comments) {
    }
}
