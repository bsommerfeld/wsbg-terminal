package de.bsommerfeld.wsbg.terminal.aggregator;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        return newsFor(symbol, null, limit);
    }

    /**
     * News referencing {@code symbol} OR the company {@code name}, merged across
     * all sources. Symbol-addressed sources (Yahoo) answer the symbol query;
     * name-addressed German venues (wallstreet-online) answer the name query —
     * both fan per source, then merge/dedupe. The cap is <b>relevance-ranked</b>,
     * not purely recency-cut: within the pool, fresh items whose TITLE names the
     * company outrank fresh ones that don't, which outrank stale ones — so the
     * top-N the model actually sees leads with the real catalysts (density of
     * SOURCES, bounded count IN the brief).
     *
     * @param name the company's name for name-addressed sources; {@code null}
     *             keeps the query symbol-only and the ordering pure recency
     * @return matching items, or an empty list — never {@code null}
     */
    public List<RawNewsItem> newsFor(String symbol, String name, int limit) {
        boolean haveSymbol = symbol != null && !symbol.isBlank();
        boolean haveName = name != null && !name.isBlank();
        if ((!haveSymbol && !haveName) || limit <= 0) {
            return List.of();
        }
        Map<String, RawNewsItem> byId = gather(haveSymbol, haveName, symbol, name, limit);
        return byId.values().stream()
                .sorted(NewsRelevanceRanker.forName(name))
                .limit(limit)
                .toList();
    }

    /**
     * Fans the symbol and/or name query across every source with per-source
     * error isolation, merging into an id-keyed map. {@code putIfAbsent} keeps
     * the first occurrence of a given id; iteration order of the source set is
     * the tie-break (load-bearing dedup semantics — first-seen id wins).
     */
    private Map<String, RawNewsItem> gather(boolean haveSymbol, boolean haveName,
                                            String symbol, String name, int limit) {
        Map<String, RawNewsItem> byId = new LinkedHashMap<>();
        for (NewsSource src : sources) {
            try {
                if (haveSymbol) collect(byId, src.newsFor(symbol, limit));
                if (haveName) collect(byId, src.newsForName(name, limit));
            } catch (Exception e) {
                LOG.warn("news source {} failed for '{}'/'{}': {}",
                        safeName(src), symbol, name, e.getMessage());
            }
        }
        return byId;
    }

    private static void collect(Map<String, RawNewsItem> byId, List<RawNewsItem> items) {
        if (items == null) return;
        for (RawNewsItem it : items) {
            if (it != null) {
                byId.putIfAbsent(dedupKey(it), it);
            }
        }
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
