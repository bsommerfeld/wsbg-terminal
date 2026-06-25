package de.bsommerfeld.wsbg.terminal.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The permanent headline archive: every headline the wire ever published,
 * append-only, <b>never deleted</b>. This is the one store that deliberately
 * breaks the "everything is in-memory" rule — and safely so: headlines are the
 * app's own <em>output</em>, not a mirror of Reddit state, so unlike persisted
 * threads/clusters they can never go stale or produce ghost clusters. The
 * headline is the compressed memory of its cluster; the raw material stays
 * TTL-bound, the conclusion is forever.
 *
 * <p><b>Format:</b> JSONL ({@code archive/headlines.jsonl} in the app data
 * dir) — one {@link HeadlineRecord} per line. Appends are atomic enough for
 * this rate (a few hundred lines/day); a torn final line from a crash is
 * skipped tolerantly on load, losing at most that one record. No SQLite by
 * design: no native dependency, human-greppable, trivially backed up.
 *
 * <p><b>Index:</b> the full archive is loaded at construction and indexed
 * in memory — {@link #byTicker(String)} (primary symbol + named subjects) and
 * {@link #search(String)} (case-insensitive over text/ticker/sectors) are the
 * primitives a later search/watchlist UI builds on. At ~1–2&nbsp;KB per record
 * this stays cheap for years of wire output.
 *
 * <p>The lab "Reset" never touches history. The only way to wipe the archive is
 * the explicit, user-triggered {@link #clear()} ("Archiv löschen" in Settings) —
 * a deliberate, destructive action, not part of any automatic lifecycle.
 */
@Singleton
public class HeadlineArchive {

    private static final Logger LOG = LoggerFactory.getLogger(HeadlineArchive.class);
    static final String FILE_NAME = "headlines.jsonl";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    private final List<HeadlineRecord> records = new ArrayList<>();
    private final Map<String, List<HeadlineRecord>> tickerIndex = new HashMap<>();
    /** Identity keys of everything archived — appends are idempotent. */
    private final Set<String> identities = new HashSet<>();

    @Inject
    public HeadlineArchive() {
        this(StorageUtils.getAppDataDir().resolve("archive").resolve(FILE_NAME));
    }

    /** Archive at an explicit path — for tests and (future) export/maintenance tooling. */
    public HeadlineArchive(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        int broken = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    index(mapper.readValue(line, HeadlineRecord.class));
                } catch (Exception e) {
                    broken++; // torn tail from a crash, or a hand-edited line — skip it
                }
            }
        } catch (IOException e) {
            LOG.warn("Headline archive unreadable ({}): {}", file, e.getMessage());
            return;
        }
        LOG.info("Headline archive loaded: {} record(s){} ← {}", records.size(),
                broken > 0 ? " (" + broken + " broken line(s) skipped)" : "", file);
    }

    /**
     * Archives one published headline <b>in full</b>, so it can be re-displayed
     * 1:1 days later — including its intraday spark series (the sparkline chart).
     * Idempotent on the record's identity (createdAt + clusterId + text), so a
     * snapshot-restore replay can't duplicate history.
     */
    public synchronized void append(HeadlineRecord record) {
        if (record == null || record.headline() == null || record.headline().isBlank()) return;
        if (!index(record)) return; // already archived
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            // The in-memory index keeps the record for this session either way.
            LOG.warn("Failed to append headline to archive: {}", e.getMessage());
        }
    }

    /** Adds the record to the in-memory list + indexes. Returns false on a duplicate identity. */
    private synchronized boolean index(HeadlineRecord r) {
        if (!identities.add(identity(r))) return false;
        records.add(r);
        if (r.tickerSymbol() != null && !r.tickerSymbol().isBlank()) {
            tickerIndex.computeIfAbsent(r.tickerSymbol().toUpperCase(Locale.ROOT),
                    k -> new ArrayList<>()).add(r);
        }
        if (r.subjects() != null) {
            for (HeadlineSubject s : r.subjects()) {
                if (s.ticker() == null || s.ticker().isBlank()) continue;
                String key = s.ticker().toUpperCase(Locale.ROOT);
                List<HeadlineRecord> list =
                        tickerIndex.computeIfAbsent(key, k -> new ArrayList<>());
                if (!list.contains(r)) list.add(r);
            }
        }
        return true;
    }

    private static String identity(HeadlineRecord r) {
        return r.createdAt() + "|" + r.clusterId() + "|" + r.headline();
    }

    /**
     * Wipes the permanent archive — the file and every in-memory index. Triggered
     * only by the user's explicit "Archiv löschen" action. The live wire is the
     * caller's concern (it keeps the current session; see
     * {@code AgentRepository.clearArchiveKeepSession}).
     */
    public synchronized void clear() {
        records.clear();
        tickerIndex.clear();
        identities.clear();
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Failed to delete archive file {}: {}", file, e.getMessage());
        }
        LOG.info("Headline archive cleared.");
    }

    // ---- read API (search / watchlist primitives) ----

    public synchronized int size() {
        return records.size();
    }

    /** Every archived headline, oldest first (a fresh copy). */
    public synchronized List<HeadlineRecord> all() {
        return new ArrayList<>(records);
    }

    /** Headlines younger than {@code maxAge}, oldest first — the wire's restart seed. */
    public synchronized List<HeadlineRecord> recent(Duration maxAge) {
        long cutoff = Instant.now().minus(maxAge).getEpochSecond();
        List<HeadlineRecord> out = new ArrayList<>();
        for (HeadlineRecord r : records) {
            if (r.createdAt() >= cutoff) out.add(r);
        }
        return out;
    }

    /**
     * Every headline that names {@code symbol} — as its primary ticker or among
     * its subjects. Newest first. The watchlist primitive: "show me everything
     * the wire ever said about NVDA".
     */
    public synchronized List<HeadlineRecord> byTicker(String symbol) {
        if (symbol == null || symbol.isBlank()) return List.of();
        List<HeadlineRecord> hits = tickerIndex.get(symbol.trim().toUpperCase(Locale.ROOT));
        if (hits == null) return List.of();
        List<HeadlineRecord> out = new ArrayList<>(hits);
        out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return out;
    }

    /**
     * The scroll-back page: up to {@code limit} headlines strictly OLDER than
     * {@code beforeEpoch}, newest-first. Pass the lowest {@code createdAt} of the
     * previous page as the next cursor; a non-positive cursor pages from the newest.
     * Filters then sorts the survivors, so it's robust to out-of-order appends.
     */
    public synchronized List<HeadlineRecord> page(long beforeEpoch, int limit) {
        if (limit <= 0) return List.of();
        long cursor = beforeEpoch <= 0 ? Long.MAX_VALUE : beforeEpoch;
        List<HeadlineRecord> out = new ArrayList<>();
        for (HeadlineRecord r : records) {
            if (r.createdAt() < cursor) out.add(r);
        }
        out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return out.size() <= limit ? out : new ArrayList<>(out.subList(0, limit));
    }

    /**
     * Case-insensitive substring search over headline text, primary ticker,
     * subject names/tickers, and sector chips. Newest first.
     */
    public synchronized List<HeadlineRecord> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<HeadlineRecord> out = new ArrayList<>();
        for (HeadlineRecord r : records) {
            if (matches(r, q)) out.add(r);
        }
        out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return out;
    }

    private static boolean matches(HeadlineRecord r, String q) {
        if (contains(r.headline(), q) || contains(r.tickerSymbol(), q)) return true;
        if (r.subjects() != null) {
            for (HeadlineSubject s : r.subjects()) {
                if (contains(s.name(), q) || contains(s.ticker(), q)) return true;
            }
        }
        if (r.sectors() != null) {
            for (String sector : r.sectors()) {
                if (contains(sector, q)) return true;
            }
        }
        return false;
    }

    private static boolean contains(String haystack, String q) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(q);
    }
}
