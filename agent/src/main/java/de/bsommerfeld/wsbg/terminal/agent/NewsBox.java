package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The news half of a {@link SubjectUnit} (extracted). Carries the per-unit news
 * items (merged by uuid, capped, newest first) plus the ids of news a published
 * headline has already cited. Self-contained — no coupling to evidence or headlines.
 *
 * <p><b>Not internally synchronized:</b> every access is made by {@link SubjectUnit}
 * while it holds its own {@code synchronized(this)} monitor, so this helper stays
 * lock-free and the unit keeps ONE lock (see {@link SubjectUnit}'s locking note).
 * The {@code news} reference is {@code volatile} so the unsynchronised
 * {@link SubjectUnit#news()} read stays safe, exactly as before the extraction.
 */
final class NewsBox {

    /**
     * News items carried per unit. Merged (not replaced) on every re-resolve so an
     * item a headline already leaned on can't vanish just because Yahoo's next
     * search returned a different set; capped so a long-lived unit doesn't hoard
     * a session's worth of headlines-context. Oldest items fall off first.
     */
    static final int MAX_NEWS = 12;

    private volatile List<RawNewsItem> news = List.of();

    /**
     * IDs of news items a published headline has already cited (#2 step 3b).
     * A covered item is filtered out of the next compose so two headlines never
     * rest on the same piece of news.
     */
    private final Set<String> coveredNewsIds = new HashSet<>();

    /**
     * Merges {@code fresh} into the held news (by uuid, existing wins, newest first,
     * capped at {@link #MAX_NEWS}) and drops the covered-ids of any item that fell
     * off the cap.
     */
    void merge(List<RawNewsItem> fresh) {
        this.news = mergeNews(this.news, fresh);
        Set<String> kept = new HashSet<>();
        for (RawNewsItem n : this.news) {
            if (n.uuid() != null) kept.add(n.uuid());
        }
        coveredNewsIds.retainAll(kept);
    }

    List<RawNewsItem> news() {
        return news;
    }

    void markCovered(Collection<String> ids) {
        if (ids == null) return;
        for (String id : ids) {
            if (id != null && !id.isBlank()) coveredNewsIds.add(id);
        }
    }

    boolean isCovered(String id) {
        return id != null && coveredNewsIds.contains(id);
    }

    Set<String> coveredIdsCopy() {
        return new HashSet<>(coveredNewsIds);
    }

    /** Restore hook — seeds the covered ids from a snapshot (live news is not snapshotted). */
    void restore(Collection<String> coveredIds) {
        if (coveredIds != null) coveredNewsIds.addAll(coveredIds);
    }

    /** Existing ∪ fresh, deduped by uuid (existing wins), newest first, capped at {@link #MAX_NEWS}. */
    private static List<RawNewsItem> mergeNews(List<RawNewsItem> existing, List<RawNewsItem> fresh) {
        Map<String, RawNewsItem> byUuid = new LinkedHashMap<>();
        for (RawNewsItem n : existing) {
            if (n != null && n.uuid() != null && !n.uuid().isBlank()) byUuid.putIfAbsent(n.uuid(), n);
        }
        if (fresh != null) {
            for (RawNewsItem n : fresh) {
                if (n != null && n.uuid() != null && !n.uuid().isBlank()) byUuid.putIfAbsent(n.uuid(), n);
            }
        }
        List<RawNewsItem> merged = new ArrayList<>(byUuid.values());
        // Newest first; items without a timestamp sort last (oldest), so they're
        // the first to fall off the cap.
        merged.sort((a, b) -> {
            Instant pa = a.publishedAt();
            Instant pb = b.publishedAt();
            if (pa == null && pb == null) return 0;
            if (pa == null) return 1;
            if (pb == null) return -1;
            return pb.compareTo(pa);
        });
        return merged.size() <= MAX_NEWS ? merged : new ArrayList<>(merged.subList(0, MAX_NEWS));
    }
}
