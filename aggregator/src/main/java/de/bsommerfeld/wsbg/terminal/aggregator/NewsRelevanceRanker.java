package de.bsommerfeld.wsbg.terminal.aggregator;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;

/**
 * The relevance-ranking policy for the merged news pool: how a gathered set of
 * {@link RawNewsItem}s is ordered before the aggregator caps it.
 *
 * <p>Tiered relevance: fresh + company named in the title → fresh → stale,
 * newest first within each tier. Falls back to pure recency when no name is
 * known. Deliberately deterministic — no scoring model, just the two signals
 * that matter for "is this the catalyst": is it current, does it name us.
 *
 * <p>Pure and side-effect free, so the tiering is unit-testable without
 * exercising the fan-out. Package-private policy object of the aggregator.
 */
final class NewsRelevanceRanker {

    private NewsRelevanceRanker() {
    }

    /** Newest first; items without a timestamp sort to the end. */
    static final Comparator<RawNewsItem> BY_RECENCY =
            Comparator.comparing(RawNewsItem::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    /** Items younger than this count as "fresh" for the relevance tiering. */
    private static final Duration FRESH_WINDOW = Duration.ofHours(36);

    /**
     * The comparator ordering the merged pool for the given company {@code name}
     * (fresh+named → fresh → stale, recency within tier), or pure recency when
     * no name is known.
     */
    static Comparator<RawNewsItem> forName(String name) {
        if (name == null || name.isBlank()) return BY_RECENCY;
        String needle = name.toLowerCase(Locale.ROOT);
        Instant freshEdge = Instant.now().minus(FRESH_WINDOW);
        Comparator<RawNewsItem> byTier = Comparator.comparingInt(it -> {
            boolean fresh = it.publishedAt() != null && it.publishedAt().isAfter(freshEdge);
            boolean named = it.title() != null
                    && it.title().toLowerCase(Locale.ROOT).contains(needle);
            return fresh && named ? 0 : fresh ? 1 : 2;
        });
        return byTier.thenComparing(BY_RECENCY);
    }
}
