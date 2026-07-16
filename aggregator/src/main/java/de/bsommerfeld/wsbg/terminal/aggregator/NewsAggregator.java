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
        return newsFor(symbol, name, null, limit);
    }

    /**
     * The full triangulation: symbol AND name AND ISIN fanned per source,
     * union-merged — the ISIN is ADDITIVE, never replacing (an article naming
     * the company without printing the ISIN must not be lost), and it is the
     * one key that never chases a wrong same-named twin.
     */
    /**
     * ARCHIVE window fan-out: every source's {@code newsForNameWindow} for one
     * date window, deduplicated by title - the multi-year press-history leg
     * (2026-07-16). Sources without an archive contribute nothing.
     */
    public List<RawNewsItem> historyFor(String name, String isin, String fromIsoDate,
            String toIsoDateExclusive, int limit) {
        if (limit <= 0 || ((name == null || name.isBlank()) && (isin == null || isin.isBlank()))) {
            return List.of();
        }
        List<RawNewsItem> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (NewsSource source : sources) {
            if (source.socialSentiment()) continue; // the archive is a press leg
            try {
                for (RawNewsItem item : source.newsForNameWindow(
                        name, isin, fromIsoDate, toIsoDateExclusive, limit)) {
                    String key = item.title() == null ? "" :
                            item.title().strip().toLowerCase(java.util.Locale.ROOT);
                    if (!key.isEmpty() && seen.add(key)) out.add(item);
                }
            } catch (Exception e) {
                LOG.debug("history window from {} failed: {}", source.sourceName(), e.getMessage());
            }
            if (out.size() >= limit) break;
        }
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    public List<RawNewsItem> newsFor(String symbol, String name, String isin, int limit) {
        boolean haveSymbol = symbol != null && !symbol.isBlank();
        boolean haveName = name != null && !name.isBlank();
        boolean haveIsin = isin != null && !isin.isBlank();
        if ((!haveSymbol && !haveName && !haveIsin) || limit <= 0) {
            return List.of();
        }
        Map<String, RawNewsItem> byId =
                gather(haveSymbol, haveName, haveIsin, symbol, name, isin, limit, false);
        return byId.values().stream()
                .sorted(NewsRelevanceRanker.forName(name))
                .limit(limit)
                .toList();
    }

    /**
     * The SENTIMENT fan: the same symbol/name/ISIN triangulation, but across
     * the {@link NewsSource#socialSentiment() social} sources ONLY — forum
     * posts and social chatter, room opinion rather than reported news. Kept
     * strictly apart from {@link #newsFor}: the press loom never sees these
     * items, and this fan never sees articles. Ordered by pure recency (a
     * forum post's relevance IS its freshness — title-naming tiers don't
     * apply to posts that rarely name the company), capped at {@code limit}.
     *
     * @return matching items, or an empty list — never {@code null}
     */
    public List<RawNewsItem> sentimentFor(String symbol, String name, String isin, int limit) {
        boolean haveSymbol = symbol != null && !symbol.isBlank();
        boolean haveName = name != null && !name.isBlank();
        boolean haveIsin = isin != null && !isin.isBlank();
        if ((!haveSymbol && !haveName && !haveIsin) || limit <= 0) {
            return List.of();
        }
        Map<String, RawNewsItem> byId =
                gather(haveSymbol, haveName, haveIsin, symbol, name, isin, limit, true);
        return byId.values().stream()
                .sorted(java.util.Comparator.comparing(RawNewsItem::publishedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .limit(limit)
                .toList();
    }

    /**
     * Fans the symbol/name/ISIN queries across every source of the requested
     * kind ({@code social} splits press from sentiment sources) with
     * per-source error isolation, merging into an id-keyed map.
     * {@code putIfAbsent} keeps the first occurrence of a given id; iteration
     * order of the source set is the tie-break (load-bearing dedup semantics
     * — first-seen id wins).
     */
    private Map<String, RawNewsItem> gather(boolean haveSymbol, boolean haveName,
                                            boolean haveIsin, String symbol, String name,
                                            String isin, int limit, boolean social) {
        Map<String, RawNewsItem> byId = new LinkedHashMap<>();
        Set<String> seenStories = new java.util.HashSet<>();
        for (NewsSource src : sources) {
            if (src.socialSentiment() != social) continue;
            try {
                if (haveSymbol) collect(byId, seenStories, src.newsFor(symbol, limit));
                if (haveName) collect(byId, seenStories, src.newsForName(name, limit));
                if (haveIsin) collect(byId, seenStories, src.newsForIsin(isin, limit));
            } catch (Exception e) {
                LOG.warn("news source {} failed for '{}'/'{}': {}",
                        safeName(src), symbol, name, e.getMessage());
            }
        }
        return byId;
    }

    private static void collect(Map<String, RawNewsItem> byId, Set<String> seenStories,
                                List<RawNewsItem> items) {
        if (items == null) return;
        for (RawNewsItem it : items) {
            if (it == null) continue;
            // Second net beside the id: the IDENTICAL article often arrives
            // under two different links (the symbol query and the name query
            // both hit it, Google mints a fresh redirect per query) — same
            // normalized title + publisher = same story, keep the first.
            String story = storyKey(it);
            if (story != null && !seenStories.add(story)) continue;
            byId.putIfAbsent(dedupKey(it), it);
        }
    }

    /** Item id is the dedup key; falls back to the link when a source left it blank. */
    private static String dedupKey(RawNewsItem it) {
        if (it.uuid() != null && !it.uuid().isBlank()) {
            return it.uuid();
        }
        return it.link() == null ? "" : it.link();
    }

    /** Normalized title + publisher, or null when there is no usable title. */
    private static String storyKey(RawNewsItem it) {
        if (it.title() == null || it.title().isBlank()) return null;
        String title = it.title().strip().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", " ");
        String publisher = it.publisher() == null ? ""
                : it.publisher().strip().toLowerCase(java.util.Locale.ROOT);
        return title + "|" + publisher;
    }

    private static String safeName(NewsSource src) {
        try {
            return src.sourceName();
        } catch (Exception e) {
            return src.getClass().getSimpleName();
        }
    }
}
