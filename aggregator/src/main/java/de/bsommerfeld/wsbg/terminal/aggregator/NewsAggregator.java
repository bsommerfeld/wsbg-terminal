package de.bsommerfeld.wsbg.terminal.aggregator;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fans a {@code newsFor(symbol)} query across every bound {@link NewsSource}
 * (Yahoo, the RSS feed index, …), merges the results into one de-duplicated,
 * recency-ordered list, and hands that back to the editorial pipeline.
 *
 * <p>This is the seam the resolver will call instead of reaching for Yahoo
 * directly: sources are injected as a {@code Set<NewsSource>} (Guice
 * multibindings), so adding, dropping or swapping a source is a wiring change in
 * {@code AppModule}, never a change here. A source that throws or returns
 * {@code null} is skipped, not fatal — partial news beats no news.
 */
@Singleton
public class NewsAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(NewsAggregator.class);

    /** Newest first; items without a timestamp sort to the end. */
    private static final Comparator<RawNewsItem> BY_RECENCY =
            Comparator.comparing(RawNewsItem::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    private final Set<NewsSource> sources;

    @Inject
    public NewsAggregator(Set<NewsSource> sources) {
        this.sources = sources;
    }

    /**
     * News referencing {@code symbol}, merged across all sources, de-duplicated
     * by item id, newest first, capped at {@code limit}.
     *
     * @return matching items, or an empty list — never {@code null}
     */
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        if (symbol == null || symbol.isBlank() || limit <= 0) {
            return List.of();
        }
        // putIfAbsent keeps the first occurrence of a given id; iteration order of
        // the source set is the tie-break, then BY_RECENCY re-orders the survivors.
        Map<String, RawNewsItem> byId = new LinkedHashMap<>();
        for (NewsSource src : sources) {
            List<RawNewsItem> items;
            try {
                items = src.newsFor(symbol, limit);
            } catch (Exception e) {
                LOG.warn("news source {} failed for '{}': {}", safeName(src), symbol, e.getMessage());
                continue;
            }
            if (items == null) {
                continue;
            }
            for (RawNewsItem it : items) {
                if (it != null) {
                    byId.putIfAbsent(dedupKey(it), it);
                }
            }
        }
        return byId.values().stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /** Item id is the dedup key; falls back to the link when a source left it blank. */
    private static String dedupKey(RawNewsItem it) {
        if (it.uuid() != null && !it.uuid().isBlank()) {
            return it.uuid();
        }
        return it.link() == null ? "" : it.link();
    }

    private static String safeName(NewsSource src) {
        try {
            return src.sourceName();
        } catch (Exception e) {
            return src.getClass().getSimpleName();
        }
    }
}
