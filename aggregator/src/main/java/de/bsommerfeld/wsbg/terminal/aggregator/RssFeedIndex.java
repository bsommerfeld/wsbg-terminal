package de.bsommerfeld.wsbg.terminal.aggregator;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The symbol-addressable face of the feed (push) sources. RSS sources
 * (finanznachrichten, FinancialJuice, newswires) emit a <em>stream</em> of
 * {@link RawNewsItem} that isn't addressed by symbol; this index ingests that
 * stream and keys each item under the ticker symbols and ISIN it references, so
 * the aggregator can answer {@code newsFor(symbol)} from it exactly like it does
 * from Yahoo's pull-by-symbol path.
 *
 * <p>It is itself a {@link NewsSource}, so once it's bound into the
 * {@code Set<NewsSource>} it joins Yahoo transparently. Everything is in-memory
 * and bounded per key (oldest evicted) in keeping with the app's no-persistence
 * design. Ingest happens on feed-poller threads while the editorial pipeline
 * queries; a single coarse lock keeps it correct — news volume is low (feeds
 * refresh on the order of minutes), so contention is a non-issue.
 *
 * <p><b>Not yet fed or bound.</b> This is the data structure; wiring the Fn/Fj
 * streams into {@link #ingest} and adding it to the multibinding is a later step.
 */
@Singleton
public final class RssFeedIndex implements NewsSource {

    /** Items retained per instrument key; oldest dropped past this. */
    private static final int MAX_PER_KEY = 64;

    /** Newest first; items without a timestamp sort to the end. */
    private static final Comparator<RawNewsItem> BY_RECENCY =
            Comparator.comparing(RawNewsItem::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    private final Object lock = new Object();
    /** upper-cased ticker symbol → recent items (newest first). */
    private final Map<String, List<RawNewsItem>> bySymbol = new HashMap<>();
    /** ISIN → recent items (newest first). */
    private final Map<String, List<RawNewsItem>> byIsin = new HashMap<>();
    /** uuids already ingested, so a feed re-emitting an item doesn't duplicate it. */
    private final java.util.Set<String> seen = new java.util.HashSet<>();

    @Override
    public String sourceName() {
        return "rss-index";
    }

    /**
     * Indexes one item under every instrument key it carries (each related
     * ticker symbol and its ISIN). Items with no instrument key are dropped —
     * they're un-addressable, so they'd never be returned by {@link #newsFor}.
     * Re-ingesting a previously-seen uuid is a no-op.
     */
    public void ingest(RawNewsItem item) {
        if (item == null) return;
        String id = item.uuid();
        if (id == null || id.isBlank()) return;

        synchronized (lock) {
            if (!seen.add(id)) return; // already indexed

            boolean keyed = false;
            if (item.relatedTickers() != null) {
                for (String raw : item.relatedTickers()) {
                    String sym = normalizeSymbol(raw);
                    if (sym != null) {
                        addTo(bySymbol, sym, item);
                        keyed = true;
                    }
                }
            }
            String isin = item.isin();
            if (isin != null && !isin.isBlank()) {
                addTo(byIsin, isin.trim().toUpperCase(Locale.ROOT), item);
                keyed = true;
            }
            if (!keyed) {
                // un-addressable; release the id so a later, better-tagged copy can index
                seen.remove(id);
            }
        }
    }

    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        if (symbol == null || symbol.isBlank() || limit <= 0) {
            return List.of();
        }
        String sym = symbol.trim().toUpperCase(Locale.ROOT);
        // de-dup across the symbol and ISIN buckets (an item tagged with both)
        Map<String, RawNewsItem> merged = new LinkedHashMap<>();
        synchronized (lock) {
            collect(merged, bySymbol.get(sym));
            collect(merged, byIsin.get(sym)); // caller may pass an ISIN
        }
        return merged.values().stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    private static void collect(Map<String, RawNewsItem> into, List<RawNewsItem> from) {
        if (from == null) return;
        for (RawNewsItem it : from) {
            into.putIfAbsent(it.uuid(), it);
        }
    }

    /** Inserts newest-first and trims the bucket to {@link #MAX_PER_KEY}. Caller holds {@link #lock}. */
    private static void addTo(Map<String, List<RawNewsItem>> map, String key, RawNewsItem item) {
        List<RawNewsItem> bucket = map.computeIfAbsent(key, k -> new ArrayList<>());
        bucket.add(item);
        bucket.sort(BY_RECENCY);
        while (bucket.size() > MAX_PER_KEY) {
            bucket.remove(bucket.size() - 1); // drop the oldest
        }
    }

    private static String normalizeSymbol(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}
