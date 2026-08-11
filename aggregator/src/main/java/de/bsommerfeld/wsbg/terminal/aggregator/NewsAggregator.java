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

    /**
     * How many sources are asked at once. A fan touches one host per source, so
     * the width is a network-latency lever, not a load one - no host sees more
     * than its own single request. Measured on the deep dive of 2026-08-09: the
     * serial fan over 36 sources (symbol + name + ISIN each) plus 11 serial
     * archive windows made the press leg 353 s of the 8:45 collect phase, with
     * the machine waiting on exactly one socket at a time.
     */
    private static final int FAN_WIDTH = 8;

    private final Set<NewsSource> sources;
    /** Lazily built, daemon, shared by every fan of this singleton. */
    private volatile java.util.concurrent.ExecutorService fanPool;

    @Inject
    public NewsAggregator(Set<NewsSource> sources) {
        this.sources = sources;
    }

    private java.util.concurrent.ExecutorService pool() {
        java.util.concurrent.ExecutorService p = fanPool;
        if (p != null) return p;
        synchronized (this) {
            if (fanPool == null) {
                java.util.concurrent.atomic.AtomicInteger n =
                        new java.util.concurrent.atomic.AtomicInteger();
                fanPool = java.util.concurrent.Executors.newFixedThreadPool(FAN_WIDTH, r -> {
                    Thread t = new Thread(r, "news-fan-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
            }
            return fanPool;
        }
    }

    /**
     * Runs one task per source concurrently and hands the results back <b>in the
     * source set's own iteration order</b> - the merge downstream therefore sees
     * exactly the sequence it saw when the fan was serial, which is load-bearing:
     * first-seen id wins the dedupe. Overlapping the WAITING is the whole change;
     * the ORDER of what comes out is untouched.
     *
     * <p>A source that throws yields {@code null} in its slot (logged by the
     * task itself) - per-source error isolation, as before. An interrupt cancels
     * the outstanding tasks and restores the flag so a cancelled deep dive stops
     * instead of finishing the fan.
     */
    private <T> List<T> fanOut(List<NewsSource> ordered, java.util.function.Function<NewsSource, T> task) {
        if (ordered.isEmpty()) return List.of();
        if (ordered.size() == 1) {
            return java.util.Collections.singletonList(task.apply(ordered.get(0)));
        }
        List<java.util.concurrent.Future<T>> futures = new java.util.ArrayList<>(ordered.size());
        for (NewsSource src : ordered) futures.add(pool().submit(() -> task.apply(src)));
        List<T> out = new java.util.ArrayList<>(ordered.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                out.add(futures.get(i).get());
            } catch (InterruptedException e) {
                for (int j = i; j < futures.size(); j++) futures.get(j).cancel(true);
                Thread.currentThread().interrupt();
                return out;
            } catch (java.util.concurrent.ExecutionException e) {
                LOG.warn("news source {} failed in the fan: {}",
                        safeName(ordered.get(i)), e.getCause() == null ? e : e.getCause().toString());
                out.add(null);
            }
        }
        return out;
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
     * The WIRE fan: like {@link #newsFor(String, String, int)}, but across the
     * live-wire sources only — every source that declares itself
     * {@link NewsSource#dossierOnly() dossier-only} stays out. The headline
     * loom asks for the day's catalysts, not for the research depth a dossier
     * lives on; fanning the research legs into the wire drowns the catalyst in
     * venue notes and quadruples the same agency piece (2026-08-03).
     *
     * @return matching items, or an empty list — never {@code null}
     */
    public List<RawNewsItem> wireNewsFor(String symbol, int limit) {
        return wireNewsFor(symbol, null, limit);
    }

    /** The wire fan by symbol AND name — see {@link #wireNewsFor(String, int)}. */
    public List<RawNewsItem> wireNewsFor(String symbol, String name, int limit) {
        return newsFor(symbol, name, null, limit, null, true, true);
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
        // Every source answers up to the full ask, then a ROUND-ROBIN merge
        // interleaves them (2026-07-16 fix: the old first-wins break let the
        // first source fill the budget and the archive sources were never
        // asked). Dedupe by title across sources.
        // Side by side across sources, merged in the source set's own order -
        // see fanOut: the round-robin below sees the identical input sequence.
        List<List<RawNewsItem>> answers = fanOut(List.copyOf(sources), source -> {
            try {
                return stamp(source.newsForNameWindow(
                        name, isin, fromIsoDate, toIsoDateExclusive, limit), source);
            } catch (Exception e) {
                LOG.debug("history window from {} failed: {}", source.sourceName(), e.getMessage());
                return null;
            }
        });
        List<List<RawNewsItem>> perSource = new java.util.ArrayList<>();
        for (List<RawNewsItem> items : answers) {
            if (items != null && !items.isEmpty()) perSource.add(items);
        }
        List<RawNewsItem> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int round = 0; out.size() < limit; round++) {
            boolean any = false;
            for (List<RawNewsItem> items : perSource) {
                if (round >= items.size() || out.size() >= limit) continue;
                any = true;
                RawNewsItem item = items.get(round);
                String key = item.title() == null ? "" :
                        item.title().strip().toLowerCase(java.util.Locale.ROOT);
                if (!key.isEmpty() && seen.add(key)) out.add(item);
            }
            if (!any) break;
        }
        return out;
    }

    public List<RawNewsItem> newsFor(String symbol, String name, String isin, int limit) {
        return newsFor(symbol, name, isin, limit, null);
    }

    /**
     * The STREAMING variant: {@code onItem} fires for every item the moment
     * its source delivers it (post-dedupe, pre-ranking) — the live view lists
     * finds WHILE the fan still runs instead of after the last source. The
     * returned list stays the ranked, capped pool; a streamed item may not
     * survive the cap, so callers reconcile against the return value.
     */
    public List<RawNewsItem> newsFor(String symbol, String name, String isin, int limit,
            java.util.function.Consumer<RawNewsItem> onItem) {
        return newsFor(symbol, name, isin, limit, onItem, false, true);
    }

    /**
     * The UNCAPPED fan (user mandate 2026-08-03 "MAX_NEWS_CANDIDATES darf es
     * nicht geben"): {@code perSourceLimit} stays a pure TRANSPORT budget -
     * how deep each single source is asked - but the merged pool is returned
     * WHOLE, only ranked. The dossier's judge is the filter; a pool cut here
     * would drop finds before anything ever looked at them. Wire callers keep
     * {@link #newsFor} with its ranked cap.
     */
    public List<RawNewsItem> newsForAll(String symbol, String name, String isin,
            int perSourceLimit, java.util.function.Consumer<RawNewsItem> onItem) {
        return newsFor(symbol, name, isin, perSourceLimit, onItem, false, false);
    }

    /**
     * @param wireOnly drops the dossier-only sources from the fan.
     * @param capPool  caps the merged, ranked pool at {@code limit} — off for
     *                 the dossier fan, which keeps every find.
     */
    private List<RawNewsItem> newsFor(String symbol, String name, String isin, int limit,
            java.util.function.Consumer<RawNewsItem> onItem, boolean wireOnly, boolean capPool) {
        boolean haveSymbol = symbol != null && !symbol.isBlank();
        boolean haveName = name != null && !name.isBlank();
        boolean haveIsin = isin != null && !isin.isBlank();
        if ((!haveSymbol && !haveName && !haveIsin) || limit <= 0) {
            return List.of();
        }
        Map<String, RawNewsItem> byId =
                gather(haveSymbol, haveName, haveIsin, symbol, name, isin, limit, false, onItem,
                        wireOnly);
        var ranked = byId.values().stream().sorted(NewsRelevanceRanker.forName(name));
        return capPool ? ranked.limit(limit).toList() : ranked.toList();
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
                gather(haveSymbol, haveName, haveIsin, symbol, name, isin, limit, true, null, false);
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
                                            String isin, int limit, boolean social,
                                            java.util.function.Consumer<RawNewsItem> onItem,
                                            boolean wireOnly) {
        List<NewsSource> asked = new java.util.ArrayList<>();
        for (NewsSource src : sources) {
            if (src.socialSentiment() != social) continue;
            if (wireOnly && src.dossierOnly()) continue;
            asked.add(src);
        }
        // The three queries of ONE source stay on ONE thread and keep their
        // symbol → name → ISIN order; the sources run side by side. The merge
        // below then walks the answers in the original source order, so the
        // dedupe's "first-seen id wins" verdict is bit-identical to the serial
        // fan - only the waiting overlaps.
        List<List<List<RawNewsItem>>> perSource = fanOut(asked, src -> {
            List<List<RawNewsItem>> answers = new java.util.ArrayList<>(3);
            try {
                if (haveSymbol) answers.add(stamp(src.newsFor(symbol, limit), src));
                if (haveName) answers.add(stamp(src.newsForName(name, limit), src));
                if (haveIsin) answers.add(stamp(src.newsForIsin(isin, limit), src));
            } catch (Exception e) {
                LOG.warn("news source {} failed for '{}'/'{}': {}",
                        safeName(src), symbol, name, e.getMessage());
            }
            return answers;
        });
        Map<String, RawNewsItem> byId = new LinkedHashMap<>();
        Set<String> seenStories = new java.util.HashSet<>();
        for (List<List<RawNewsItem>> answers : perSource) {
            if (answers == null) continue;
            for (List<RawNewsItem> items : answers) collect(byId, seenStories, items, onItem);
        }
        return byId;
    }

    private static void collect(Map<String, RawNewsItem> byId, Set<String> seenStories,
                                List<RawNewsItem> items,
                                java.util.function.Consumer<RawNewsItem> onItem) {
        if (items == null) return;
        for (RawNewsItem it : items) {
            if (it == null) continue;
            // Second net beside the id: the IDENTICAL article often arrives
            // under two different links (the symbol query and the name query
            // both hit it, Google mints a fresh redirect per query) — same
            // normalized title + publisher = same story, keep the first.
            String story = storyKey(it);
            if (story != null && !seenStories.add(story)) continue;
            if (byId.putIfAbsent(dedupKey(it), it) == null && onItem != null) {
                // A listener must never kill the fan — announce best-effort.
                try {
                    onItem.accept(it);
                } catch (Exception e) {
                    LOG.debug("news stream listener failed: {}", e.getMessage());
                }
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

    /** Normalized title + publisher, or null when there is no usable title. */
    private static String storyKey(RawNewsItem it) {
        if (it.title() == null || it.title().isBlank()) return null;
        String title = it.title().strip().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", " ");
        String publisher = it.publisher() == null ? ""
                : it.publisher().strip().toLowerCase(java.util.Locale.ROOT);
        return title + "|" + publisher;
    }

    /**
     * Stamps every item of one answer with the ANSWERING source's origin - the
     * only seam that still knows which source produced which item (the merge
     * below has already forgotten). A source that declares no origin, or an
     * item that carries one of its own, passes through untouched.
     */
    private static List<RawNewsItem> stamp(List<RawNewsItem> items, NewsSource src) {
        if (items == null || items.isEmpty()) return items;
        de.bsommerfeld.wsbg.terminal.source.SourceOrigin origin;
        try {
            origin = src.origin();
        } catch (Exception e) {
            return items;
        }
        if (origin == null || !origin.known()) return items;
        List<RawNewsItem> out = new java.util.ArrayList<>(items.size());
        for (RawNewsItem it : items) out.add(it == null ? null : it.withOrigin(origin));
        return out;
    }

    private static String safeName(NewsSource src) {
        try {
            return src.sourceName();
        } catch (Exception e) {
            return src.getClass().getSimpleName();
        }
    }
}
