package de.bsommerfeld.wsbg.terminal.db;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
 * <p><b>Structure:</b> this class is a thin facade over two collaborators:
 * {@link HeadlineJsonlCodec} (the file IO + torn-line-tolerant load) and
 * {@link HeadlineIndex} (the in-memory records + ticker fan-out + the
 * {@link #byTicker(String)} / {@link #search(String)} / {@link #recent(Duration)}
 * / {@link #page(long, int)} query primitives a later search/watchlist UI builds
 * on). The facade owns the <b>single lock</b> over both, so an append's
 * index-mutation and file-write stay atomic against a concurrent clear/read.
 *
 * <p>The lab "Reset" never touches history. The only way to wipe the archive is
 * the explicit, user-triggered {@link #clear()} ("Archiv löschen" in Settings) —
 * a deliberate, destructive action, not part of any automatic lifecycle.
 */
@Singleton
public class HeadlineArchive {

    private static final Logger LOG = LoggerFactory.getLogger(HeadlineArchive.class);
    static final String FILE_NAME = "headlines.jsonl";

    private final HeadlineJsonlCodec codec;
    private final HeadlineIndex index = new HeadlineIndex();

    @Inject
    public HeadlineArchive() {
        this(StorageUtils.getAppDataDir().resolve("archive").resolve(FILE_NAME));
    }

    /** Archive at an explicit path — for tests and (future) export/maintenance tooling. */
    public HeadlineArchive(Path file) {
        this.codec = new HeadlineJsonlCodec(file);
        load();
    }

    private synchronized void load() {
        HeadlineJsonlCodec.LoadResult result = codec.readAll();
        for (HeadlineRecord r : result.records()) index.add(r);
        int broken = result.broken();
        LOG.info("Headline archive loaded: {} record(s){} ← {}", index.size(),
                broken > 0 ? " (" + broken + " broken line(s) skipped)" : "", codec.file());
    }

    /**
     * Archives one published headline <b>in full</b>, so it can be re-displayed
     * 1:1 days later — including its intraday spark series (the sparkline chart).
     * Idempotent on the record's identity (createdAt + clusterId + text), so a
     * snapshot-restore replay can't duplicate history.
     */
    public synchronized void append(HeadlineRecord record) {
        if (record == null || record.headline() == null || record.headline().isBlank()) return;
        if (!index.add(record)) return; // already archived — never re-write the file
        codec.append(record);
    }

    /**
     * Wipes the permanent archive — the file and every in-memory index. Triggered
     * only by the user's explicit "Archiv löschen" action. The live wire is the
     * caller's concern (it keeps the current session; see
     * {@code AgentRepository.clearArchiveKeepSession}).
     */
    public synchronized void clear() {
        index.clear();
        codec.delete();
        LOG.info("Headline archive cleared.");
    }

    // ---- read API (search / watchlist primitives) ----

    public synchronized int size() {
        return index.size();
    }

    /** Every archived headline, oldest first (a fresh copy). */
    public synchronized List<HeadlineRecord> all() {
        return index.all();
    }

    /** Headlines younger than {@code maxAge}, oldest first — the wire's restart seed. */
    public synchronized List<HeadlineRecord> recent(Duration maxAge) {
        return index.recent(maxAge);
    }

    /**
     * Every headline that names {@code symbol} — as its primary ticker or among
     * its subjects. Newest first. The watchlist primitive: "show me everything
     * the wire ever said about NVDA".
     */
    public synchronized List<HeadlineRecord> byTicker(String symbol) {
        return index.byTicker(symbol);
    }

    /**
     * The scroll-back page: up to {@code limit} headlines strictly OLDER than
     * {@code beforeEpoch}, newest-first. Pass the lowest {@code createdAt} of the
     * previous page as the next cursor; a non-positive cursor pages from the newest.
     * Filters then sorts the survivors, so it's robust to out-of-order appends.
     */
    public synchronized List<HeadlineRecord> page(long beforeEpoch, int limit) {
        return index.page(beforeEpoch, limit);
    }

    /**
     * Case-insensitive substring search over headline text, primary ticker,
     * subject names/tickers, and sector chips. Newest first.
     */
    public synchronized List<HeadlineRecord> search(String query) {
        return index.search(query);
    }

    /**
     * Subject search — everything the wire ever said about one subject, by
     * name AND ticker together (union of the ticker index and the full-text
     * name search, deduped, newest first). Either argument may be null.
     */
    public synchronized List<HeadlineRecord> searchSubject(String name, String ticker) {
        return index.searchSubject(name, ticker);
    }

    /**
     * The subject vocabulary — every subject the wire ever named, aggregated
     * (display name, nullable ticker, headline count, newest mention),
     * most-named first. The suggestion index behind the search UI.
     */
    public synchronized List<HeadlineSubjectStat> subjectStats() {
        return index.subjectStats();
    }
}
