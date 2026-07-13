package de.bsommerfeld.wsbg.terminal.db;

import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;

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
 * The in-memory index over archived headlines: the record list, the ticker
 * fan-out ({@code symbol → records naming it}), the identity set for
 * append-dedup, and the query primitives
 * ({@link #search}/{@link #byTicker}/{@link #recent}/{@link #page}) a search /
 * watchlist UI builds on. Pure in-memory structure — no file IO — so each query
 * feature stands isolated and is testable with zero disk.
 *
 * <p>Not thread-safe on its own: the owning {@link HeadlineArchive} facade
 * serializes access (a single monitor over both this index and the codec), so a
 * mutation and a concurrent read/clear never interleave.
 */
final class HeadlineIndex {

    private final List<HeadlineRecord> records = new ArrayList<>();
    private final Map<String, List<HeadlineRecord>> tickerIndex = new HashMap<>();
    /** Identity keys of everything indexed — appends are idempotent. */
    private final Set<String> identities = new HashSet<>();

    /**
     * Adds the record to the list + ticker fan-out. Returns {@code false} on a
     * duplicate identity (the caller then skips the file write, keeping appends
     * idempotent).
     */
    boolean add(HeadlineRecord r) {
        if (!identities.add(HeadlineIdentity.of(r))) return false;
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

    void clear() {
        records.clear();
        tickerIndex.clear();
        identities.clear();
    }

    int size() {
        return records.size();
    }

    /** Every indexed headline, oldest first (a fresh copy). */
    List<HeadlineRecord> all() {
        return new ArrayList<>(records);
    }

    /** Headlines younger than {@code maxAge}, oldest first — the wire's restart seed. */
    List<HeadlineRecord> recent(Duration maxAge) {
        long cutoff = Instant.now().minus(maxAge).getEpochSecond();
        List<HeadlineRecord> out = new ArrayList<>();
        for (HeadlineRecord r : records) {
            if (r.createdAt() >= cutoff) out.add(r);
        }
        return out;
    }

    /**
     * Every headline that names {@code symbol} — as its primary ticker or among
     * its subjects. Newest first.
     */
    List<HeadlineRecord> byTicker(String symbol) {
        if (symbol == null || symbol.isBlank()) return List.of();
        List<HeadlineRecord> hits = tickerIndex.get(symbol.trim().toUpperCase(Locale.ROOT));
        if (hits == null) return List.of();
        List<HeadlineRecord> out = new ArrayList<>(hits);
        out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return out;
    }

    /**
     * The scroll-back page: up to {@code limit} headlines strictly OLDER than
     * {@code beforeEpoch}, newest-first. A non-positive cursor pages from the newest.
     */
    List<HeadlineRecord> page(long beforeEpoch, int limit) {
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
    List<HeadlineRecord> search(String query) {
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

    /**
     * Subject search: the union of the ticker index and the full-text name
     * search, deduped by record identity, newest first. A subject must never
     * depend on its ticker to be findable — name-only lines (the subject named
     * before/without ticker resolution) count the same as ticker-carrying ones.
     * Either argument may be null/blank; the other leg still runs.
     */
    List<HeadlineRecord> searchSubject(String name, String ticker) {
        Map<String, HeadlineRecord> hits = new HashMap<>();
        for (HeadlineRecord r : byTicker(ticker)) hits.putIfAbsent(HeadlineIdentity.of(r), r);
        for (HeadlineRecord r : search(name)) hits.putIfAbsent(HeadlineIdentity.of(r), r);
        List<HeadlineRecord> out = new ArrayList<>(hits.values());
        out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return out;
    }

    /**
     * The subject vocabulary: every subject the archive ever named, aggregated
     * by identity (ticker when present, else the normalized name), most-named
     * first. Each headline counts a subject at most once, the newest mention's
     * display name wins (name forms drift as the wire canonicalises).
     */
    List<HeadlineSubjectStat> subjectStats() {
        Map<String, SubjectAgg> byKey = new HashMap<>();
        for (HeadlineRecord r : records) {
            // Per record: collect the distinct subject identities first, so a
            // primary ticker that re-appears among the subjects counts once.
            Map<String, HeadlineSubject> inRecord = new HashMap<>();
            if (r.subjects() != null) {
                for (HeadlineSubject s : r.subjects()) {
                    String key = subjectKey(s.ticker(), s.name());
                    if (key != null) inRecord.putIfAbsent(key, s);
                }
            }
            if (r.tickerSymbol() != null && !r.tickerSymbol().isBlank()) {
                String key = subjectKey(r.tickerSymbol(), null);
                inRecord.putIfAbsent(key, new HeadlineSubject(null, r.tickerSymbol()));
            }
            for (Map.Entry<String, HeadlineSubject> e : inRecord.entrySet()) {
                byKey.computeIfAbsent(e.getKey(), k -> new SubjectAgg())
                        .fold(e.getValue(), r.createdAt());
            }
        }
        List<HeadlineSubjectStat> out = new ArrayList<>(byKey.size());
        for (SubjectAgg agg : byKey.values()) out.add(agg.toStat());
        out.sort((a, b) -> a.count() != b.count()
                ? Integer.compare(b.count(), a.count())
                : Long.compare(b.lastSeen(), a.lastSeen()));
        return out;
    }

    /** Identity key: ticker (case-folded) when present, else the normalized name; null when both blank. */
    private static String subjectKey(String ticker, String name) {
        if (ticker != null && !ticker.isBlank()) return "T:" + ticker.trim().toUpperCase(Locale.ROOT);
        if (name != null && !name.isBlank()) return "N:" + name.trim().toLowerCase(Locale.ROOT);
        return null;
    }

    /** Mutable per-subject aggregate while walking the records. */
    private static final class SubjectAgg {
        private String name;
        private String ticker;
        private int count;
        private long lastSeen = Long.MIN_VALUE;

        void fold(HeadlineSubject s, long createdAt) {
            count++;
            boolean newest = createdAt >= lastSeen;
            if (createdAt > lastSeen) lastSeen = createdAt;
            if (s.ticker() != null && !s.ticker().isBlank()) {
                ticker = s.ticker().trim().toUpperCase(Locale.ROOT);
            }
            if (s.name() != null && !s.name().isBlank() && (newest || name == null)) {
                name = s.name().trim();
            }
        }

        HeadlineSubjectStat toStat() {
            return new HeadlineSubjectStat(name != null ? name : ticker, ticker, count, lastSeen);
        }
    }
}
