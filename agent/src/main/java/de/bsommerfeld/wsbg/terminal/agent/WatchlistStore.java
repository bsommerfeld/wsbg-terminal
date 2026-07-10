package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Persistence for the AI watchlist ({@code <app-data>/watchlist.json}). Unlike the
 * short-TTL session snapshots this file is PERMANENT: the watchlist report is the
 * standing dossier that tomorrow's evidence keeps revising — it must survive any
 * restart and any snapshot TTL, like the headline archive does. The file is small
 * (a handful of entries, ~1-2 KB each), so a full rewrite per mutation is fine;
 * writes go through a temp file + atomic move so a crash can never tear it.
 */
@Singleton
public final class WatchlistStore {

    private static final Logger LOG = LoggerFactory.getLogger(WatchlistStore.class);
    private static final String FILE_NAME = "watchlist.json";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public WatchlistStore() {
        this(StorageUtils.getAppDataDir().resolve(FILE_NAME));
    }

    /** Test seam: explicit file location. */
    WatchlistStore(Path file) {
        this.file = file;
    }

    /** One persisted watchlist entry — the durable half of the service's runtime entry. */
    public record PersistedEntry(String id, String name, String tldr, String report,
            long createdAtEpoch, long updatedAtEpoch) {
    }

    /** Top-level file shape (versioned so a future migration can branch on it). */
    public record PersistedWatchlist(int version, List<PersistedEntry> entries) {
    }

    /** Loads the persisted entries; any I/O/parse failure degrades to an empty list. */
    public synchronized List<PersistedEntry> load() {
        if (!Files.exists(file)) return List.of();
        try {
            PersistedWatchlist w = mapper.readValue(file.toFile(), PersistedWatchlist.class);
            List<PersistedEntry> entries = w.entries() == null ? List.of() : w.entries();
            LOG.info("Loaded watchlist: {} entrie(s) from {}", entries.size(), file);
            return entries;
        } catch (Exception e) {
            LOG.warn("Failed to read watchlist file {} ({}); starting empty.", file, e.getMessage());
            return List.of();
        }
    }

    /** Rewrites the whole file (temp + atomic move). Best-effort — a failure only logs. */
    public synchronized void save(List<PersistedEntry> entries) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            mapper.writeValue(tmp.toFile(), new PersistedWatchlist(1, entries));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOG.warn("Failed to save watchlist file {}: {}", file, e.getMessage());
        }
    }
}
